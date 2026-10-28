package ac.cult.cultac.network.routing;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.routing.MessageHandler.Origin;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

/**
 * This instance's network routing: the roster of every instance, handlers per message type, and messages to one
 * instance, to every other instance, or to this instance's engine generations over the in-process bus. Messages for
 * this instance never reach the broker and need no transport. Only the current generation publishes the roster: a
 * full snapshot every {@value #ROSTER_PERIOD_MILLIS} ms, a delta on each join, quit and backend switch, and an empty
 * snapshot when it stops.
 */
public final class NetworkService implements NetworkRouting {
    static final long ROSTER_PERIOD_MILLIS = 15_000;

    private static final long WARNING_PERIOD_MILLIS = 60_000;

    /** A registered handler, removed by {@link #close}. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final NetworkSettings settings;
    private final NetworkPlatform platform;
    private final @Nullable HostContext host;
    private final String instanceId;
    private final @Nullable MessageTransport transport;
    private final EnvelopeCodec codec;
    private final LongSupplier clock;
    /** Runs every received message and roster tick; null runs messages on the caller's thread and schedules no tick. */
    private final @Nullable ScheduledExecutorService executor;

    private final String directChannel;
    private final String broadcastChannel;
    private final RosterCache roster = new RosterCache();
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();
    private final Map<String, JsonElement> flags = new ConcurrentHashMap<>();
    private final AtomicLong badSignatureWarned = new AtomicLong(Long.MIN_VALUE);

    private @Nullable InProcessBus bus;
    private volatile boolean running;

    NetworkService(
            NetworkSettings settings,
            NetworkPlatform platform,
            @Nullable HostContext host,
            String instanceId,
            byte @Nullable [] key,
            @Nullable MessageTransport transport,
            LongSupplier clock,
            @Nullable ScheduledExecutorService executor) {
        this.settings = settings;
        this.platform = platform;
        this.host = host;
        this.instanceId = instanceId;
        this.transport = transport;
        this.clock = clock;
        this.executor = executor;
        this.codec = new EnvelopeCodec(key, instanceId, this::knownType);
        this.directChannel = settings.channel(instanceId);
        this.broadcastChannel = settings.channel(NetworkSettings.ALL);
    }

    /**
     * A service for {@code settings}, its transport left out, with one error logged, when the settings cannot start
     * one: an unknown transport, an instance id no channel can carry, no secret, or a transport that is unavailable.
     */
    static NetworkService create(NetworkSettings settings, NetworkPlatform platform, @Nullable HostContext host) {
        String instanceId = settings.resolveInstanceId(platform::redisBungeeId, platform::address);
        byte[] key = settings.resolveSecret(platform.defaultSecret());
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "CultAC network");
            thread.setDaemon(true);
            return thread;
        });
        RuntimeEnvironment.track(executor, executor::shutdownNow);
        return new NetworkService(
                settings,
                platform,
                host,
                instanceId,
                key,
                transport(settings, platform, instanceId, key),
                System::currentTimeMillis,
                executor);
    }

    private static @Nullable MessageTransport transport(
            NetworkSettings settings, NetworkPlatform platform, String instanceId, byte @Nullable [] key) {
        String name = settings.transport();
        if (name.equals(NetworkSettings.NONE)) return null;
        if (!settings.knownTransport()) {
            LogUtil.error("Network routing off: network.transport \"" + name + "\" is not none, redis, nats or"
                    + " redisbungee");
            return null;
        }
        String invalid = NetworkSettings.invalidInstanceId(instanceId);
        if (invalid != null) {
            LogUtil.error("Network routing off: the instance id \"" + instanceId + "\" cannot name a channel because "
                    + invalid + "; set network.instance-id");
            return null;
        }
        if (key == null) {
            LogUtil.error(
                    platform.proxy()
                            ? "Network routing off: network.secret is empty and Velocity has no modern-forwarding secret to"
                                    + " use; set network.secret, the same on every instance"
                            : "Network routing off: network.secret is empty, and a server has no secret to fall back on; set"
                                    + " it to the same value on every instance");
            return null;
        }
        try {
            MessageTransport transport = switch (name) {
                case NetworkSettings.REDIS -> RedisTransport.create(settings.redisUri());
                case NetworkSettings.NATS -> NatsTransport.create(settings);
                default -> platform.redisBungee();
            };
            if (transport == null)
                LogUtil.error("Network routing off: network.transport is redisbungee, but RedisBungee is not installed"
                        + " on this proxy or cannot be reached");
            return transport;
        } catch (IllegalArgumentException invalidSetting) {
            LogUtil.error("Network routing off: " + invalidSetting.getMessage());
            return null;
        }
    }

    /** Joins the bus, subscribes and publishes the first roster snapshot. */
    synchronized void start() {
        if (running) return;
        running = true;
        bus = InProcessBus.join(host, text -> execute(() -> receive(text, false, true)));
        if (transport != null) {
            transport.start(
                    List.of(directChannel, broadcastChannel),
                    (channel, text) -> execute(() -> receive(text, channel.equals(broadcastChannel), false)));
            publishRoster(platform.onlinePlayers(), true, List.of());
        }
        if (executor != null)
            executor.scheduleAtFixedRate(this::tick, ROSTER_PERIOD_MILLIS, ROSTER_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Leaves the bus and, while this generation is current, tells the other instances this one is gone. */
    synchronized void stop() {
        if (!running) return;
        running = false;
        if (bus != null) bus.leave();
        if (transport != null) {
            if (current())
                publish(
                        NetworkSettings.ALL,
                        RosterCache.TYPE,
                        RosterCache.body(List.of(), true, List.of(), new JsonObject()));
            transport.close();
        }
        if (executor != null) executor.shutdownNow();
    }

    NetworkSettings settings() {
        return settings;
    }

    /** The running transport's name, or {@code none}. */
    public String transportName() {
        return transport == null ? NetworkSettings.NONE : transport.name();
    }

    /** Adds a handler for messages of {@code type}; a type no handler takes is dropped on arrival. */
    public Registration on(String type, MessageHandler handler) {
        handlers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> handlers.computeIfPresent(type, (ignored, list) -> {
            list.remove(handler);
            return list.isEmpty() ? null : list;
        });
    }

    /**
     * Sends {@code body} to {@code instance}: over the bus when it is this instance, to every other instance when it is
     * {@value NetworkSettings#ALL}. False when nothing could carry it.
     */
    public boolean send(String instance, String type, JsonObject body) {
        if (instance.equals(instanceId)) return sendLocal(type, body);
        if (!running) return false;
        return publish(instance, type, body);
    }

    /** Sends {@code body} to every other instance; false without a transport. */
    public boolean broadcast(String type, JsonObject body) {
        return send(NetworkSettings.ALL, type, body);
    }

    /** Sends {@code body} to every engine generation on this instance, this one included. */
    public boolean sendLocal(String type, JsonObject body) {
        InProcessBus bus = this.bus;
        if (!running || bus == null) return false;
        bus.send(codec.encode(Envelope.create(type, instanceId, instanceId, clock.getAsLong(), body)));
        return true;
    }

    /**
     * Sets {@code key} in the flags each roster message carries, or removes it when {@code value} is null. A change is
     * published at once as a roster delta.
     */
    public void flag(String key, @Nullable JsonElement value) {
        JsonElement previous = value == null ? flags.remove(key) : flags.put(key, value);
        if (running && !Objects.equals(previous, value)) publishRoster(List.of(), false, List.of());
    }

    /** The roster flags of each instance heard from lately, this one left out. */
    public Map<String, JsonObject> peerFlags() {
        return roster.flags(clock.getAsLong());
    }

    /** Reports a join or a backend switch here. Any thread. */
    public void playerChanged(OnlinePlayer player) {
        if (running) publishRoster(List.of(player), false, List.of());
    }

    /** Reports a quit here. Any thread. */
    public void playerLeft(UUID uuid) {
        if (running) publishRoster(List.of(), false, List.of(uuid));
    }

    /** Forgets silent instances and publishes the full snapshot. */
    void tick() {
        try {
            roster.expire(clock.getAsLong());
            if (running) publishRoster(platform.onlinePlayers(), true, List.of());
        } catch (RuntimeException failure) {
            LogUtil.warn("Network roster update failed", failure);
        }
    }

    /** Whether this generation answers for the instance: no host runs others, or the host names it current. */
    boolean current() {
        return host == null || host.current();
    }

    private void publishRoster(Collection<OnlinePlayer> players, boolean full, Collection<UUID> left) {
        if (transport == null || !current()) return;
        JsonObject carried = new JsonObject();
        flags.forEach(carried::add);
        publish(NetworkSettings.ALL, RosterCache.TYPE, RosterCache.body(players, full, left, carried));
    }

    private boolean publish(String to, String type, JsonObject body) {
        MessageTransport transport = this.transport;
        if (transport == null) return false;
        String text = codec.encode(Envelope.create(type, instanceId, to, clock.getAsLong(), body));
        if (EnvelopeCodec.utf8Length(text) > EnvelopeCodec.MAX_BYTES) {
            LogUtil.warn(
                    "Network message of type " + type + " not sent: it is over " + EnvelopeCodec.MAX_BYTES + " bytes");
            return false;
        }
        transport.publish(to.equals(NetworkSettings.ALL) ? broadcastChannel : settings.channel(to), text);
        return true;
    }

    private void execute(Runnable task) {
        if (executor == null) {
            task.run();
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException stopped) {
            // A stopped service takes no more messages.
        }
    }

    void receive(String text, boolean broadcast, boolean local) {
        long now = clock.getAsLong();
        EnvelopeCodec.Result result = codec.decode(text, broadcast, local, now);
        Envelope envelope = result.envelope();
        if (envelope == null) {
            if (result.drop() == EnvelopeCodec.Drop.BAD_SIGNATURE) warnBadSignature(now);
            return;
        }
        if (!local && !envelope.from().equals(instanceId)) {
            if (envelope.type().equals(RosterCache.TYPE)) roster.apply(envelope.from(), envelope.body(), now);
            else roster.heard(envelope.from(), now);
        }
        Origin origin = local ? Origin.LOCAL : envelope.broadcast() ? Origin.BROADCAST : Origin.DIRECT;
        for (MessageHandler handler : handlers.getOrDefault(envelope.type(), List.of())) {
            try {
                handler.handle(envelope, origin);
            } catch (RuntimeException failure) {
                LogUtil.warn(
                        "Network message of type " + envelope.type() + " from " + envelope.from() + " failed", failure);
            }
        }
    }

    private boolean knownType(String type) {
        return type.equals(RosterCache.TYPE) || handlers.containsKey(type);
    }

    private void warnBadSignature(long now) {
        long last = badSignatureWarned.get();
        if (now - last < WARNING_PERIOD_MILLIS || !badSignatureWarned.compareAndSet(last, now)) return;
        LogUtil.warn("Dropped a network message with a bad signature; every instance needs the same network.secret");
    }

    @Override
    public boolean enabled() {
        return running && transport != null;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public Optional<NetworkPlayer> locate(String nameOrUuid) {
        UUID uuid = uuid(nameOrUuid);
        for (OnlinePlayer player : platform.onlinePlayers()) {
            if (matches(player.uuid(), player.name(), uuid, nameOrUuid)) return Optional.of(local(player));
        }
        long now = clock.getAsLong();
        NetworkPlayer found = null;
        long foundHeard = Long.MIN_VALUE;
        for (NetworkPlayer player : roster.players(now)) {
            if (!matches(player.uuid(), player.name(), uuid, nameOrUuid)) continue;
            long heard = roster.heardAt(player.instance(), now);
            if (found == null || heard > foundHeard) {
                found = player;
                foundHeard = heard;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public Collection<NetworkPlayer> roster() {
        long now = clock.getAsLong();
        Map<UUID, NetworkPlayer> players = new LinkedHashMap<>();
        Map<UUID, Long> heard = new HashMap<>();
        for (NetworkPlayer player : roster.players(now)) {
            long at = roster.heardAt(player.instance(), now);
            Long previous = heard.get(player.uuid());
            if (previous == null || at > previous) {
                players.put(player.uuid(), player);
                heard.put(player.uuid(), at);
            }
        }
        for (OnlinePlayer player : platform.onlinePlayers()) players.put(player.uuid(), local(player));
        return List.copyOf(players.values());
    }

    @Override
    public CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where) {
        if (where.instance().equals(instanceId)) {
            sender.performCommand(line);
            return CompletableFuture.completedFuture(true);
        }
        return forward(sender, line, where);
    }

    /** Runs {@code line} on {@code where}'s instance; until commands are forwarded, it tells the sender so. */
    CompletableFuture<Boolean> forward(Sender sender, String line, NetworkPlayer where) {
        sender.sendMessage(Component.text(
                "The command could not be forwarded to " + where.instance() + ", where " + where.name() + " is.",
                NamedTextColor.RED));
        return CompletableFuture.completedFuture(false);
    }

    private NetworkPlayer local(OnlinePlayer player) {
        return new NetworkPlayer(player.uuid(), player.name(), instanceId, player.server());
    }

    private static boolean matches(UUID playerUuid, String name, @Nullable UUID uuid, String query) {
        return uuid != null ? uuid.equals(playerUuid) : name.equalsIgnoreCase(query);
    }

    private static @Nullable UUID uuid(String nameOrUuid) {
        try {
            return UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }
}
