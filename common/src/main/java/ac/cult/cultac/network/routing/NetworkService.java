package ac.cult.cultac.network.routing;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.network.routing.RemoteCommands.Commands;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * This instance's network routing: the roster of every instance, handlers per message type, and messages to one
 * instance, to every other instance, or to this instance's engine generations over the in-process bus. Messages for
 * this instance never reach the broker and need no transport. Only the current generation publishes the roster: a
 * full snapshot every {@value #ROSTER_PERIOD_MILLIS} ms and, at most every {@value #DELTA_PERIOD_MILLIS} ms, one delta
 * with the joins, quits, backend switches and verbose change reported since the last roster message, the last report
 * per player winning. The generation that drew the last roster number sends an empty snapshot when it stops, and its
 * unsent reports are dropped. The generations of
 * one host check that they are current, draw the number and publish one at a time, holding the shared counter's lock.
 * At most {@value #MAX_QUEUED_MESSAGES} received messages of {@value #MAX_QUEUED_BYTES} bytes in all wait to be read;
 * the rest are dropped.
 */
public final class NetworkService implements NetworkRouting {
    static final long ROSTER_PERIOD_MILLIS = 60_000;
    static final long DELTA_PERIOD_MILLIS = 2_000;
    static final long SWEEP_PERIOD_MILLIS = 1_000;
    static final int MAX_QUEUED_MESSAGES = 4096;
    static final long MAX_QUEUED_BYTES = 16L * 1024 * 1024;
    static final long STALE_WARNING_NANOS = TimeUnit.MINUTES.toNanos(5);
    /** The host-shared {@code AtomicLong} each roster message's number is drawn from, seeded with the wall clock. */
    static final String ROSTER_ORDER_KEY = "cultac.network.roster-order";
    /** The host-shared {@code String} naming this JVM in roster messages, to tell a duplicate instance id. */
    static final String NONCE_KEY = "cultac.network.nonce";

    private static final AtomicLong UNHOSTED_ROSTER_ORDER = new AtomicLong(System.currentTimeMillis());
    private static final String UNHOSTED_NONCE = UUID.randomUUID().toString();

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
    /** The direct channel with the instance id left off. */
    private final String channelPrefix;

    private final RosterCache roster = new RosterCache();
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();
    /** Also the lock the host's generations hold from a roster message's current check until it is sent. */
    private final AtomicLong rosterOrder;

    private final String nonce;
    private final Backlog inbox;
    private final LogThrottle badSignatures = new LogThrottle();
    private final LogThrottle staleDrops = new LogThrottle(STALE_WARNING_NANOS, System::nanoTime);
    private final AtomicBoolean rosterTooLarge = new AtomicBoolean();
    private final AtomicBoolean duplicateId = new AtomicBoolean();
    private final RemoteCommands commands;

    private @Nullable InProcessBus bus;
    private volatile boolean running;
    private volatile BooleanSupplier verbose = () -> false;
    private volatile boolean verboseSent;
    /** The last roster number this generation drew; guarded by {@link #rosterOrder}. */
    private long rosterDrawn = Long.MIN_VALUE;
    /** Each player reported since the last roster message, null for a quit; guarded by itself. */
    private final Map<UUID, @Nullable OnlinePlayer> reported = new LinkedHashMap<>();
    /** Whether a verbose change was reported since the last roster message; guarded by {@link #reported}. */
    private boolean verboseReported;

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
            LogThrottle dropWarnings,
            Commands commands) {
        this.settings = settings;
        this.platform = platform;
        this.host = host;
        this.instanceId = instanceId;
        this.transport = transport;
        this.clock = clock;
        this.scheduler = scheduler;
        this.receiver = receiver;
        this.codec = new EnvelopeCodec(key, instanceId, this::knownType);
        this.directChannel = NetworkSettings.channel(key, instanceId);
        this.broadcastChannel = NetworkSettings.channel(key, NetworkSettings.ALL);
        this.channelPrefix = NetworkSettings.channel(key, "");
        this.rosterOrder = host == null
                ? UNHOSTED_ROSTER_ORDER
                : (AtomicLong) host.shared(ROSTER_ORDER_KEY, () -> new AtomicLong(System.currentTimeMillis()));
        this.nonce = host == null
                ? UNHOSTED_NONCE
                : (String) host.shared(NONCE_KEY, () -> UUID.randomUUID().toString());
        this.inbox = new Backlog(
                "Dropped a received network message: " + MAX_QUEUED_MESSAGES + " messages or "
                        + MAX_QUEUED_BYTES / (1024 * 1024) + " MB are already waiting",
                MAX_QUEUED_MESSAGES,
                MAX_QUEUED_BYTES,
                dropWarnings);
        this.commands = new RemoteCommands(this, clock, commands, host);
        this.commands.register();
    }

    /** A service for {@code settings}, without a transport and with one error logged when they cannot start one. */
    static NetworkService create(NetworkSettings settings, NetworkPlatform platform, @Nullable HostContext host) {
        String instanceId = settings.resolveInstanceId(platform::redisBungeeId, platform::address);
        byte[] key = settings.resolveSecret(platform.defaultSecret());
        Redaction redaction = Redaction.of(settings);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("CultAC network"));
        RuntimeEnvironment.track(scheduler, scheduler::shutdownNow);
        ExecutorService receiver = Executors.newSingleThreadExecutor(daemon("CultAC network receiver"));
        RuntimeEnvironment.track(receiver, receiver::shutdownNow);
        MessageTransport transport = transport(settings, platform, instanceId, key, redaction);
        if (transport != null)
            LogUtil.info("Network routing over " + transport.name() + " as instance " + instanceId + ", signing with "
                    + keySource(settings, platform) + ", on channels " + NetworkSettings.channel(key, "") + "*");
        return new NetworkService(
                settings,
                platform,
                host,
                instanceId,
                key,
                transport,
                System::currentTimeMillis,
                scheduler,
                receiver,
                new LogThrottle(),
                RemoteCommands.CULT);
    }

    /** Where the signing key came from, for the start line; instances that name different sources may disagree. */
    private static String keySource(NetworkSettings settings, NetworkPlatform platform) {
        if (!settings.secret().isEmpty()) return "network.secret";
        byte[] platformSecret = platform.defaultSecret();
        if (platformSecret != null && platformSecret.length > 0)
            return platform.proxy() ? "the proxy's forwarding secret" : "the forwarding secret in paper-global.yml";
        return "a key derived from the network." + settings.type() + " credentials";
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
        if (NetworkSettings.NONE.equals(name)) return null;
        if (name == null) {
            LogUtil.error("Network routing off: network.type is not one of " + NetworkSettings.ACCEPTED);
            return null;
        }
        String invalid = NetworkSettings.invalidInstanceId(instanceId);
        if (invalid != null) {
            LogUtil.error("Network routing off: the instance id \"" + instanceId + "\" cannot name a channel because "
                    + invalid + "; set network.instance-id");
            return null;
        }
        if (key == null) {
            LogUtil.error("Cross-server features are off: CultAC has nothing to sign its messages with. Set a password"
                    + " (or a NATS token) on your Redis or NATS server, or run behind Velocity with modern"
                    + " forwarding.");
            return null;
        }
        try {
            MessageTransport transport = switch (name) {
                case NetworkSettings.REDIS ->
                    new RedisTransport(settings.redis(), redaction, RedisTransport.Timings.DEFAULT);
                case NetworkSettings.NATS -> new NatsTransport(settings.nats(), redaction, NatsTransport.NATS);
                default -> platform.redisBungee();
            };
            if (transport == null)
                LogUtil.error("Network routing off: network.type is redisbungee, but RedisBungee is not installed on"
                        + " this proxy or cannot be reached");
            return transport;
        } catch (IllegalArgumentException invalidSetting) {
            LogUtil.error("Network routing off: " + redaction.redact(invalidSetting.getMessage()));
            return null;
        }
    }

    synchronized void start() {
        if (running) return;
        running = true;
        bus = InProcessBus.join(host, text -> enqueue(text, false, true));
        if (transport != null) {
            transport.start(
                    List.of(directChannel, broadcastChannel),
                    (channel, text) -> enqueue(text, channel.equals(broadcastChannel), false));
            publishRoster();
        }
        if (scheduler != null) {
            scheduler.scheduleAtFixedRate(
                    this::tick, ROSTER_PERIOD_MILLIS, ROSTER_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(
                    this::sendDelta, DELTA_PERIOD_MILLIS, DELTA_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(
                    this::sweepCommands, SWEEP_PERIOD_MILLIS, SWEEP_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Leaves the bus; the generation that drew the last roster number says goodbye, as being current cannot tell. */
    void stop() {
        stop(Runnable::run);
    }

    /** As {@link #stop()}, closing the transport, which may wait for its backlog, on {@code closing}. */
    synchronized void stop(Executor closing) {
        if (!running) return;
        running = false;
        drainReports();
        if (bus != null) bus.leave();
        if (transport != null) {
            synchronized (rosterOrder) {
                if (host == null || rosterOrder.get() == rosterDrawn)
                    publish(NetworkSettings.ALL, RosterCache.TYPE, rosterBody(List.of(), true, List.of(), false));
            }
            closing.execute(transport::close);
        }
        if (scheduler != null) scheduler.shutdownNow();
        if (receiver != null) receiver.shutdownNow();
        commands.close();
    }

    NetworkSettings settings() {
        return settings;
    }

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

    /** Sends {@code body} to {@code instance}, over the bus for this one; false when nothing could carry it. */
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

    /** Has each roster message say whether {@code wanted}: staff here take verbose. */
    public void verbose(BooleanSupplier wanted) {
        verbose = wanted;
    }

    /** Reports that whether staff here take verbose may have changed; the next delta carries it. Any thread. */
    public void verboseChanged() {
        if (transport == null) return;
        synchronized (reported) {
            if (running) verboseReported = true;
        }
    }

    /** Whether staff on an instance heard from lately take verbose. */
    public boolean peersWantVerbose() {
        return roster.verbose(clock.getAsLong());
    }

    /** Reports a join or a backend switch here. Any thread. */
    public void playerChanged(OnlinePlayer player) {
        report(player.uuid(), player);
    }

    /** Reports a quit here. Any thread. */
    public void playerLeft(UUID uuid) {
        report(uuid, null);
    }

    private void report(UUID uuid, @Nullable OnlinePlayer player) {
        if (transport == null) return;
        synchronized (reported) {
            if (running) reported.put(uuid, player);
        }
    }

    void tick() {
        try {
            roster.expire(clock.getAsLong());
            if (!running) return;
            publishRoster();
        } catch (RuntimeException failure) {
            LogUtil.warn("Network roster update failed", failure);
        }
    }

    /** Sends what was reported since the last roster message as one delta, when it changes anything. */
    void sendDelta() {
        if (transport == null) return;
        try {
            synchronized (rosterOrder) {
                List<OnlinePlayer> changed = new ArrayList<>();
                List<UUID> left = new ArrayList<>();
                boolean verboseChange;
                synchronized (reported) {
                    for (Map.Entry<UUID, @Nullable OnlinePlayer> report : reported.entrySet()) {
                        OnlinePlayer player = report.getValue();
                        if (player == null) left.add(report.getKey());
                        else changed.add(player);
                    }
                    verboseChange = verboseReported;
                    reported.clear();
                    verboseReported = false;
                }
                if (!running || !current()) return;
                boolean wanted = verbose.getAsBoolean();
                if (changed.isEmpty() && left.isEmpty() && (!verboseChange || wanted == verboseSent)) return;
                verboseSent = wanted;
                publish(NetworkSettings.ALL, RosterCache.TYPE, rosterBody(changed, false, left, wanted));
            }
        } catch (RuntimeException failure) {
            LogUtil.warn("Network roster update failed", failure);
        }
    }

    private void drainReports() {
        synchronized (reported) {
            reported.clear();
            verboseReported = false;
        }
    }

    /** Whether this generation answers for the instance: no host runs others, or the host names it current. */
    boolean current() {
        return host == null || host.current();
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

    void sweepCommands() {
        try {
            commands.sweep();
        } catch (RuntimeException failure) {
            LogUtil.warn("Network command timeout check failed", failure);
        }
    }

    /** Publishes a full snapshot, which covers every report so far; one made while it is taken rides the next delta. */
    private void publishRoster() {
        if (transport == null) return;
        synchronized (rosterOrder) {
            drainReports();
            if (!running || !current()) return;
            boolean wanted = verbose.getAsBoolean();
            Collection<OnlinePlayer> players = platform.onlinePlayers();
            verboseSent = wanted;
            publish(NetworkSettings.ALL, RosterCache.TYPE, rosterBody(players, true, List.of(), wanted));
        }
    }

    /** A roster body with the next number drawn; called holding {@link #rosterOrder}. */
    private JsonObject rosterBody(
            Collection<OnlinePlayer> players, boolean full, Collection<UUID> left, boolean wanted) {
        JsonObject body = RosterCache.body(players, full, left, wanted, nonce);
        rosterDrawn = rosterOrder.incrementAndGet();
        body.addProperty(RosterCache.ORDER, rosterDrawn);
        return body;
    }

    private boolean publish(String to, String type, JsonObject body) {
        MessageTransport transport = this.transport;
        if (transport == null) return false;
        String text = codec.encode(Envelope.create(type, instanceId, to, clock.getAsLong(), body));
        if (EnvelopeCodec.utf8Length(text) > EnvelopeCodec.MAX_BYTES) {
            if (!type.equals(RosterCache.TYPE))
                LogUtil.warn("Network message of type " + type + " not sent: it is over " + EnvelopeCodec.MAX_BYTES
                        + " bytes");
            else if (rosterTooLarge.compareAndSet(false, true))
                LogUtil.warn("Network roster not sent: "
                        + platform.onlinePlayers().size()
                        + " players do not fit in " + EnvelopeCodec.MAX_BYTES + " bytes; other instances cannot route"
                        + " commands to them");
            return false;
        }
        transport.publish(to.equals(NetworkSettings.ALL) ? broadcastChannel : channelPrefix + to, text);
        return true;
    }

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
        if (result.drop() != null || envelope == null) {
            dropped(result.drop(), envelope, now);
            return;
        }
        if (!local && !envelope.from().equals(instanceId)) {
            if (envelope.type().equals(RosterCache.TYPE)) roster.apply(envelope.from(), envelope.body(), now);
            else roster.heard(envelope.from(), now);
        }
        for (MessageHandler handler : handlers.getOrDefault(envelope.type(), List.of())) {
            try {
                handler.handle(envelope);
            } catch (RuntimeException failure) {
                LogUtil.warn(
                        "Network message of type " + envelope.type() + " from " + envelope.from() + " failed", failure);
            }
        }
    }

    private void dropped(EnvelopeCodec.@Nullable Drop drop, @Nullable Envelope envelope, long now) {
        if (drop == EnvelopeCodec.Drop.BAD_SIGNATURE)
            badSignatures.warn(
                    "bad-signature",
                    "Dropped a network message with a bad signature: the servers sign with different keys; give them"
                            + " the same Redis or NATS password, or the same network.secret",
                    null);
        if (envelope == null) return;
        if (drop == EnvelopeCodec.Drop.STALE)
            staleDrops.warn(
                    "stale:" + envelope.from(),
                    "Dropping network messages from " + envelope.from() + ": its clock is " + (envelope.ts() - now)
                            + " ms off this instance's, more than " + EnvelopeCodec.MAX_SKEW_MILLIS
                            + " ms; sync the clocks",
                    null);
        else if (drop == EnvelopeCodec.Drop.UNKNOWN_TYPE)
            LogUtil.debug("Dropped a network message of unknown type " + envelope.type() + " from " + envelope.from());
        else if (drop == EnvelopeCodec.Drop.OWN_BROADCAST
                && envelope.type().equals(RosterCache.TYPE)
                && envelope.body().get(RosterCache.NONCE) instanceof JsonPrimitive other
                && !other.getAsString().equals(nonce)
                && duplicateId.compareAndSet(false, true))
            LogUtil.warn("Another instance uses this instance's id " + instanceId
                    + "; give each instance its own network.instance-id");
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
        return commands.forward(sender, line, where);
    }

    @Override
    public boolean otherGeneration(UUID player) {
        return platform.holder(player) == NetworkPlatform.Holder.OTHER;
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
