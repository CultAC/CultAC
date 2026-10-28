package ac.cult.cultac.network.routing;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.routing.MessageHandler.Origin;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.network.routing.RemoteCommands.Commands;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * This instance's network routing: the roster of every instance, handlers per message type, and messages to one
 * instance, to every other instance, or to this instance's engine generations over the in-process bus. Messages for
 * this instance never reach the broker and need no transport. Only the current generation publishes the roster: a
 * full snapshot every {@value #ROSTER_PERIOD_MILLIS} ms and a delta on each join, quit and backend switch. The
 * generation that published it last sends an empty snapshot when it stops. The generations of one host check that they
 * are current, publish and decide on that goodbye one at a time, and number each roster message from one sequence so
 * that other instances drop one arriving after a later one. At most {@value #MAX_QUEUED_MESSAGES}
 * received messages of {@value #MAX_QUEUED_BYTES} bytes in all wait to be read; the rest are dropped.
 */
public final class NetworkService implements NetworkRouting {
    static final long ROSTER_PERIOD_MILLIS = 15_000;
    static final long SWEEP_PERIOD_MILLIS = 1_000;
    static final int MAX_QUEUED_MESSAGES = 4096;
    static final long MAX_QUEUED_BYTES = 16L * 1024 * 1024;
    /** The host-shared {@code AtomicReference<String>} holding the id of the generation that published last. */
    static final String ROSTER_PUBLISHER_KEY = "cultac.network.roster-publisher";
    /** The host-shared {@code ReentrantLock} held from a roster message's current check until it is sent. */
    static final String ROSTER_LOCK_KEY = "cultac.network.roster-lock";
    /** The host-shared {@code Long}: this JVM's start time in ms, the first half of each roster message's order. */
    static final String ROSTER_EPOCH_KEY = "cultac.network.roster-epoch";
    /** The host-shared {@code AtomicLong} each roster message's sequence number is drawn from, under the roster lock. */
    static final String ROSTER_SEQUENCE_KEY = "cultac.network.roster-sequence";

    /** The roster sequence without a host, shared by every service this runtime starts. */
    private static final AtomicLong UNHOSTED_ROSTER_SEQUENCE = new AtomicLong();

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
    /** Runs the roster tick; null schedules none. */
    private final @Nullable ScheduledExecutorService scheduler;
    /** Reads received messages in arrival order; null reads them on the caller's thread. */
    private final @Nullable ExecutorService receiver;

    private final String directChannel;
    private final String broadcastChannel;
    private final RosterCache roster = new RosterCache();
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();
    private final Map<String, JsonElement> flags = new ConcurrentHashMap<>();
    private final List<Runnable> snapshotTasks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> rosterPublisher;
    private final Lock rosterLock;
    private final long rosterEpoch;
    private final AtomicLong rosterSequence;
    private final Backlog inbox;
    private final LogThrottle badSignatures = new LogThrottle();
    private final RemoteCommands commands;

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
            @Nullable ScheduledExecutorService scheduler,
            @Nullable ExecutorService receiver,
            LogThrottle dropWarnings) {
        this.settings = settings;
        this.platform = platform;
        this.host = host;
        this.instanceId = instanceId;
        this.transport = transport;
        this.clock = clock;
        this.scheduler = scheduler;
        this.receiver = receiver;
        this.codec = new EnvelopeCodec(key, instanceId, this::knownType);
        this.directChannel = settings.channel(instanceId);
        this.broadcastChannel = settings.channel(NetworkSettings.ALL);
        this.rosterPublisher = host == null ? new AtomicReference<>() : rosterPublisher(host);
        this.rosterLock = host == null ? new ReentrantLock() : (Lock) host.shared(ROSTER_LOCK_KEY, ReentrantLock::new);
        this.rosterEpoch = host == null ? jvmStart() : (Long) host.shared(ROSTER_EPOCH_KEY, NetworkService::jvmStart);
        this.rosterSequence = host == null
                ? UNHOSTED_ROSTER_SEQUENCE
                : (AtomicLong) host.shared(ROSTER_SEQUENCE_KEY, AtomicLong::new);
        this.inbox = new Backlog(
                "Dropped a received network message: " + MAX_QUEUED_MESSAGES + " messages or "
                        + MAX_QUEUED_BYTES / (1024 * 1024) + " MB are already waiting",
                MAX_QUEUED_MESSAGES,
                MAX_QUEUED_BYTES,
                dropWarnings);
        this.commands = new RemoteCommands(this, clock, RemoteCommands.CULT, host);
        commands.register();
    }

    private static long jvmStart() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<String> rosterPublisher(HostContext host) {
        return (AtomicReference<String>) host.shared(ROSTER_PUBLISHER_KEY, AtomicReference::new);
    }

    /**
     * A service for {@code settings}, its transport left out, with one error logged, when the settings cannot start
     * one: an unknown transport, an instance id no channel can carry, no secret, or a transport that is unavailable.
     */
    static NetworkService create(NetworkSettings settings, NetworkPlatform platform, @Nullable HostContext host) {
        String instanceId = settings.resolveInstanceId(platform::redisBungeeId, platform::address);
        byte[] key = settings.resolveSecret(platform.defaultSecret());
        Redaction redaction = Redaction.of(settings);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("CultAC network"));
        RuntimeEnvironment.track(scheduler, scheduler::shutdownNow);
        ExecutorService receiver = Executors.newSingleThreadExecutor(daemon("CultAC network receiver"));
        RuntimeEnvironment.track(receiver, receiver::shutdownNow);
        return new NetworkService(
                settings,
                platform,
                host,
                instanceId,
                key,
                transport(settings, platform, instanceId, key, redaction),
                System::currentTimeMillis,
                scheduler,
                receiver,
                new LogThrottle());
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static @Nullable MessageTransport transport(
            NetworkSettings settings,
            NetworkPlatform platform,
            String instanceId,
            byte @Nullable [] key,
            Redaction redaction) {
        String name = settings.transport();
        if (name.equals(NetworkSettings.NONE)) return null;
        if (!settings.knownTransport()) {
            LogUtil.error("Network routing off: network.transport \"" + name + "\" is not none, redis, nats or"
                    + " redisbungee");
            return null;
        }
        String invalidNetwork = NetworkSettings.invalidToken(settings.network());
        if (invalidNetwork != null) {
            LogUtil.error("Network routing off: network.network \"" + settings.network() + "\" cannot name a channel"
                    + " because " + invalidNetwork + "; set network.network");
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
                case NetworkSettings.REDIS -> RedisTransport.create(settings.redisUri(), redaction);
                case NetworkSettings.NATS -> NatsTransport.create(settings, redaction);
                default -> platform.redisBungee();
            };
            if (transport == null)
                LogUtil.error("Network routing off: network.transport is redisbungee, but RedisBungee is not installed"
                        + " on this proxy or cannot be reached");
            return transport;
        } catch (IllegalArgumentException invalidSetting) {
            LogUtil.error("Network routing off: " + redaction.redact(invalidSetting.getMessage()));
            return null;
        }
    }

    /** Joins the bus, subscribes and publishes the first roster snapshot. */
    synchronized void start() {
        if (running) return;
        running = true;
        bus = InProcessBus.join(host, text -> enqueue(text, false, true));
        if (transport != null) {
            transport.start(
                    List.of(directChannel, broadcastChannel),
                    (channel, text) -> enqueue(text, channel.equals(broadcastChannel), false));
            publishRoster(platform.onlinePlayers(), true, List.of());
        }
        if (scheduler != null) {
            scheduler.scheduleAtFixedRate(
                    this::tick, ROSTER_PERIOD_MILLIS, ROSTER_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(
                    this::sweepCommands, SWEEP_PERIOD_MILLIS, SWEEP_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Leaves the bus and, when this generation published the roster last, tells the other instances this one is gone.
     * A host stopping clears its current generation first, so being current cannot decide it.
     */
    synchronized void stop() {
        if (!running) return;
        running = false;
        if (bus != null) bus.leave();
        if (transport != null) {
            rosterLock.lock();
            try {
                if (publishedLast())
                    publish(
                            NetworkSettings.ALL,
                            RosterCache.TYPE,
                            ordered(RosterCache.body(List.of(), true, List.of(), new JsonObject())));
            } finally {
                rosterLock.unlock();
            }
            transport.close();
        }
        if (scheduler != null) scheduler.shutdownNow();
        if (receiver != null) receiver.shutdownNow();
        commands.close();
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

    /** Whether {@code body} sent to {@code instance} as {@code type} fits in one message. */
    boolean fits(String instance, String type, JsonObject body) {
        String text = codec.encode(Envelope.create(type, instanceId, instance, clock.getAsLong(), body));
        return EnvelopeCodec.utf8Length(text) <= EnvelopeCodec.MAX_BYTES;
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

    /** Runs {@code task} on the service's thread before each periodic roster snapshot, so it can refresh a flag. */
    public Registration beforeSnapshot(Runnable task) {
        snapshotTasks.add(task);
        return () -> snapshotTasks.remove(task);
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
            if (!running) return;
            for (Runnable task : snapshotTasks) {
                try {
                    task.run();
                } catch (RuntimeException failure) {
                    LogUtil.warn("A network feature failed before a roster snapshot", failure);
                }
            }
            publishRoster(platform.onlinePlayers(), true, List.of());
        } catch (RuntimeException failure) {
            LogUtil.warn("Network roster update failed", failure);
        }
    }

    /** Whether this generation answers for the instance: no host runs others, or the host names it current. */
    boolean current() {
        return host == null || host.current();
    }

    /** Whether no host runs other generations, or this one published the roster since any other did. */
    private boolean publishedLast() {
        return host == null || host.generationId().equals(rosterPublisher.get());
    }

    /** Whether this generation answers for {@code player}: it holds them, or no generation does and it is current. */
    boolean answers(UUID player) {
        return switch (platform.holder(player)) {
            case THIS -> true;
            case OTHER -> false;
            case NONE -> current();
        };
    }

    /** Whether {@code player} is online on this instance, whichever generation holds them. */
    boolean online(UUID player) {
        for (OnlinePlayer each : platform.onlinePlayers()) {
            if (each.uuid().equals(player)) return true;
        }
        return false;
    }

    @Nullable
    Object remoteSenderBase() {
        return platform.remoteSenderBase();
    }

    void commands(Commands hooks) {
        commands.commands(hooks);
    }

    /** Fails forwarded commands nothing answered in time. */
    void sweepCommands() {
        try {
            commands.sweep();
        } catch (RuntimeException failure) {
            LogUtil.warn("Network command timeout check failed", failure);
        }
    }

    private void publishRoster(Collection<OnlinePlayer> players, boolean full, Collection<UUID> left) {
        if (transport == null) return;
        rosterLock.lock();
        try {
            if (!running || !current()) return;
            if (host != null) rosterPublisher.set(host.generationId());
            JsonObject carried = new JsonObject();
            flags.forEach(carried::add);
            publish(NetworkSettings.ALL, RosterCache.TYPE, ordered(RosterCache.body(players, full, left, carried)));
        } finally {
            rosterLock.unlock();
        }
    }

    /** {@code body} with the next {@code [epoch, sequence]} of this instance's roster; called under the roster lock. */
    private JsonObject ordered(JsonObject body) {
        JsonArray order = new JsonArray(2);
        order.add(rosterEpoch);
        order.add(rosterSequence.incrementAndGet());
        body.add(RosterCache.ORDER, order);
        return body;
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

    /** Queues {@code text} for {@link #receive}, unless this service stopped, it is too large or the queue is full. */
    private void enqueue(String text, boolean broadcast, boolean local) {
        if (!running) return;
        int size = EnvelopeCodec.utf8Length(text);
        if (size > EnvelopeCodec.MAX_BYTES) return;
        ExecutorService receiver = this.receiver;
        if (receiver == null) {
            receive(text, broadcast, local);
            return;
        }
        if (!inbox.admit(size)) return;
        try {
            receiver.execute(() -> {
                try {
                    receive(text, broadcast, local);
                } finally {
                    inbox.release(size);
                }
            });
        } catch (RejectedExecutionException stopped) {
            inbox.release(size);
        }
    }

    void receive(String text, boolean broadcast, boolean local) {
        long now = clock.getAsLong();
        EnvelopeCodec.Result result = codec.decode(text, broadcast, local, now);
        Envelope envelope = result.envelope();
        if (envelope == null) {
            if (result.drop() == EnvelopeCodec.Drop.BAD_SIGNATURE)
                badSignatures.warn(
                        "bad-signature",
                        "Dropped a network message with a bad signature; every instance needs the same network.secret",
                        null);
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

    /**
     * Runs {@code line} here when this generation answers for {@code where}, else on the generation or instance that
     * does. A command forwarded here is never forwarded again.
     */
    @Override
    public CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where) {
        if (where.instance().equals(instanceId) && answers(where.uuid())) return commands.runHere(sender, line, where);
        if (sender instanceof RemoteCommandSource forwarded) {
            forwarded.targetLeft();
            return CompletableFuture.completedFuture(false);
        }
        if (transport == null && !where.instance().equals(instanceId)) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "network-disabled-command",
                    "%prefix% &cNetwork-wide commands are not enabled on this server. Ask a server administrator to"
                            + " configure a messaging backend (Redis or NATS) in the CultAC configuration."));
            return CompletableFuture.completedFuture(false);
        }
        return commands.forward(sender, line, where);
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
