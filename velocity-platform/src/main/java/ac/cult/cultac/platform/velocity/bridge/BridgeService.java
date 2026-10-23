package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ServerStats;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.BridgeProtocolException;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.event.PacketEvent;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/** Opens a bridge session to the companion on each backend a player joins, keyed on the backend connection. */
public final class BridgeService implements BridgeSessions, AutoCloseable {
    private static final long STATS_STALE_NANOS = TimeUnit.SECONDS.toNanos(5);
    public static final int MAX_LOGGED_REASON_CHARS = 256;
    static final long REOPEN_POLL_MILLIS = 50;
    static final int REOPEN_ATTEMPTS = 100;
    /** A shorter {@code bridge.secret} can be guessed offline from a handshake the proxy answers. */
    static final int MIN_SECRET_BYTES = 16;

    private record Stats(float tps, long receivedNanos) {}

    private record Forwarding(@Nullable String mode, byte[] secret) {}

    private final ProxyServer proxy;
    private final Object plugin;
    private final Function<Player, Channel> channels;
    private final Function<Channel, @Nullable CultConnection> connections;
    private final Logger logger;
    private final long helloRetryMillis;
    private final long readyTimeoutMillis;
    /** The clock of the sessions' control-frame budgets. */
    private final LongSupplier nanoClock;

    private final Map<UUID, ProxyBridgeSession> sessions = new ConcurrentHashMap<>();
    private final List<BridgeMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();
    private final Set<String> activeServers = ConcurrentHashMap.newKeySet();
    private final Set<String> missingServers = ConcurrentHashMap.newKeySet();
    private final Set<String> rejections = ConcurrentHashMap.newKeySet();
    private final Set<UUID> malformedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> authFailures = ConcurrentHashMap.newKeySet();
    /** Backend connections whose session ended at a backend configuration phase, awaiting the return to PLAY. */
    private final Map<UUID, ServerConnection> reconfiguring = new ConcurrentHashMap<>();

    private final Set<Integer> unknownTypes = ConcurrentHashMap.newKeySet();
    private volatile byte @Nullable [] secret;
    private volatile Predicate<ServerConnection> backendInPlay = server -> false;
    private volatile boolean closed;
    private volatile boolean registered;

    public BridgeService(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            Function<Channel, @Nullable CultConnection> connections,
            Logger logger) {
        this(
                proxy,
                plugin,
                channels,
                connections,
                logger,
                ProxyBridgeSession.HELLO_RETRY_MILLIS,
                ProxyBridgeSession.READY_TIMEOUT_MILLIS,
                System::nanoTime);
    }

    BridgeService(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            Function<Channel, @Nullable CultConnection> connections,
            Logger logger,
            long helloRetryMillis,
            long readyTimeoutMillis,
            LongSupplier nanoClock) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channels = channels;
        this.connections = connections;
        this.logger = logger;
        this.helloRetryMillis = helloRetryMillis;
        this.readyTimeoutMillis = readyTimeoutMillis;
        this.nanoClock = nanoClock;
    }

    /**
     * Strips the bridge channel from the packet stream and, when a secret is available, starts opening sessions.
     *
     * @param secretOverride CultAC's {@code bridge.secret}; empty uses Velocity's modern-forwarding secret
     * @return whether sessions are enabled
     */
    public boolean start(PacketDispatcher dispatcher, String secretOverride) {
        registerStripRoutes(dispatcher);
        Forwarding forwarding = forwarding(proxy);
        byte[] resolved =
                secretOverride.isEmpty() ? forwarding.secret() : secretOverride.getBytes(StandardCharsets.UTF_8);
        if (resolved.length == 0) {
            String mode = forwarding.mode();
            logger.warning(
                    mode == null || mode.equals("MODERN")
                            ? "CultAC companion bridge disabled: no Velocity forwarding secret and no bridge.secret set"
                            : "CultAC companion bridge disabled: Velocity uses " + mode + " player info forwarding,"
                                    + " not modern, and no bridge.secret is set");
            return false;
        }
        if (!secretOverride.isEmpty() && resolved.length < MIN_SECRET_BYTES) {
            logger.warning("bridge.secret is shorter than " + MIN_SECRET_BYTES + " bytes; use a long random value");
        }
        Predicate<ServerConnection> inPlay;
        try {
            inPlay = VelocityBackend.playStateProbe(proxy.getClass().getClassLoader());
        } catch (ReflectiveOperationException | LinkageError failure) {
            logger.log(
                    Level.WARNING,
                    "CultAC companion bridge disabled: unsupported Velocity backend connection internals",
                    failure);
            return false;
        }
        enable(resolved, inPlay);
        registerSessionRoutes(dispatcher);
        proxy.getEventManager().register(plugin, this);
        registered = true;
        return true;
    }

    void enable(byte[] secret, Predicate<ServerConnection> inPlay) {
        this.backendInPlay = inPlay;
        this.secret = secret.clone();
    }

    /** Always registered, so no client can forge bridge frames even when sessions are off. */
    void registerStripRoutes(PacketDispatcher dispatcher) {
        dispatcher.register(routes -> {
            routes.earlyReceive(
                    ServerboundPackets.CUSTOM_PAYLOAD,
                    PacketListenerPriority.LOWEST,
                    (event, player, packet) -> stripClientPayload(event, packet));
            routes.receiveConnection(
                    ServerboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, this::stripClientPayload);
        });
    }

    /** Connection-level, so players without a CultPlayer (exempt) still get sessions. */
    void registerSessionRoutes(PacketDispatcher dispatcher) {
        dispatcher.register(routes -> {
            routes.sendConnection(
                    ClientboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, this::interceptBackendPayload);
            routes.sendConnection(ClientboundPackets.LOGIN, PacketListenerPriority.LOWEST, (event, packet) -> {
                endSession(event, false);
                rejoinedWorld(event);
            });
            routes.sendConnection(
                    ClientboundPackets.START_CONFIGURATION,
                    PacketListenerPriority.LOWEST,
                    (event, packet) -> endSession(event, true));
        });
    }

    /** Clients never reach the backend on the bridge channel. */
    void stripClientPayload(PacketEvent<?> event, ServerboundCustomPayload packet) {
        if (BridgeProtocol.CHANNEL.equals(packet.channel())) event.cancelFinally();
    }

    void interceptBackendPayload(PacketEvent<?> event, ClientboundCustomPayload packet) {
        if (!BridgeProtocol.CHANNEL.equals(packet.channel())) return;
        event.cancelFinally();
        var user = event.getUser();
        if (user == null) return;
        // A throwing handler would have the dispatcher undo the cancel and forward the frame to the client.
        try {
            receiveFrame(user.getUUID(), packet.data());
        } catch (RuntimeException | LinkageError failure) {
            CultNetworkManager.reportFailure("CultAC bridge frame", failure);
        }
    }

    void receiveFrame(UUID playerId, byte[] frame) {
        var session = sessions.get(playerId);
        if (session == null) return;
        BridgeMessage message;
        try {
            message = BridgeCodec.decode(Unpooled.wrappedBuffer(frame));
        } catch (BridgeProtocolException failure) {
            if (failure.isUnknownType()) {
                if (unknownTypes.add(failure.unknownType())) {
                    logger.warning("Ignoring unknown CultAC bridge message type " + failure.unknownType() + " from "
                            + session.serverName());
                }
                return;
            }
            if (malformedPlayers.add(playerId)) {
                logger.warning("Closing the CultAC bridge for " + playerId + " on " + session.serverName()
                        + ": malformed frame (" + printable(failure.getMessage(), MAX_LOGGED_REASON_CHARS) + ")");
            }
            session.close("malformed frame");
            return;
        }
        session.receive(message);
    }

    void endSession(PacketEvent<?> event, boolean configuration) {
        var user = event.getUser();
        var session = user == null ? null : sessions.get(user.getUUID());
        if (session == null) return;
        if (configuration && session.connection() instanceof ServerConnection server) {
            reconfiguring.put(user.getUUID(), server);
        }
        session.close();
    }

    /**
     * Paper sends a play Login once the player is placed again after a configuration phase; when that phase began on
     * this backend connection, reopen once it is back in PLAY.
     */
    void rejoinedWorld(PacketEvent<?> event) {
        var user = event.getUser();
        if (closed || user == null) return;
        UUID playerId = user.getUUID();
        ServerConnection server = reconfiguring.get(playerId);
        if (server == null) return;
        Channel channel;
        try {
            channel = channels.apply(server.getPlayer());
        } catch (RuntimeException unavailable) {
            reconfiguring.remove(playerId, server);
            return;
        }
        scheduleReopen(playerId, server, channel, REOPEN_ATTEMPTS);
    }

    private void scheduleReopen(UUID playerId, ServerConnection server, Channel channel, int attemptsLeft) {
        channel.eventLoop()
                .schedule(
                        () -> reopen(playerId, server, channel, attemptsLeft),
                        REOPEN_POLL_MILLIS,
                        TimeUnit.MILLISECONDS);
    }

    private void reopen(UUID playerId, ServerConnection server, Channel channel, int attemptsLeft) {
        if (reconfiguring.get(playerId) != server) return;
        var existing = sessions.get(playerId);
        if (closed
                || !channel.isActive()
                || server.getPlayer().getCurrentServer().orElse(null) != server
                || (existing != null && existing.state() != BridgeSession.State.CLOSED)) {
            reconfiguring.remove(playerId, server);
            return;
        }
        if (!backendInPlay.test(server)) {
            if (attemptsLeft > 1) scheduleReopen(playerId, server, channel, attemptsLeft - 1);
            else reconfiguring.remove(playerId, server);
            return;
        }
        reconfiguring.remove(playerId, server);
        open(playerId, server, new VelocityBackend(server, channel, backendInPlay, connections.apply(channel)));
    }

    /**
     * A connection adopted from another runtime at a configuration acknowledgement. When that configuration phase is on
     * the current backend, the session reopens once the backend is back in PLAY.
     */
    public void adopted(Player player) {
        if (closed || secret == null) return;
        player.getCurrentServer().ifPresent(server -> reconfiguring.put(player.getUniqueId(), server));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (closed) return;
        Player player = event.getPlayer();
        reconfiguring.remove(player.getUniqueId());
        Channel channel;
        try {
            channel = channels.apply(player);
        } catch (RuntimeException unavailable) {
            return;
        }
        if (!RuntimeEnvironment.owns(channel)) return;
        channel.eventLoop().execute(() -> {
            if (closed || !channel.isActive()) return;
            ServerConnection server = player.getCurrentServer().orElse(null);
            if (server == null || !backendInPlay.test(server)) return;
            open(
                    player.getUniqueId(),
                    server,
                    new VelocityBackend(server, channel, backendInPlay, connections.apply(channel)));
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        malformedPlayers.remove(playerId);
        reconfiguring.remove(playerId);
        var session = sessions.get(playerId);
        if (session != null
                && session.connection() instanceof ServerConnection server
                && server.getPlayer() == event.getPlayer()) {
            session.close();
        }
    }

    /** Call on the backend's loop. */
    @Nullable
    ProxyBridgeSession open(UUID playerId, Object connection, BridgeBackend backend) {
        byte[] key = secret;
        if (closed || key == null) return null;
        var existing = sessions.get(playerId);
        if (existing != null) {
            if (existing.connection() == connection) return existing;
            existing.close();
        }
        var session = new ProxyBridgeSession(
                playerId, connection, backend, key, this, helloRetryMillis, readyTimeoutMillis, nanoClock);
        sessions.put(playerId, session);
        session.start();
        return session;
    }

    @Override
    public @Nullable BridgeSession current(UUID player) {
        var session = sessions.get(player);
        return session == null || session.state() == BridgeSession.State.CLOSED ? null : session;
    }

    @Override
    public void addListener(BridgeMessageListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(BridgeMessageListener listener) {
        listeners.remove(listener);
    }

    /** TPS last reported by the companion on the player's backend, or NaN. */
    public double tps(UUID player) {
        var session = current(player);
        var latest =
                session == null || !session.has(ServerCapability.SERVER_STATS) ? null : stats.get(session.serverName());
        return latest == null || System.nanoTime() - latest.receivedNanos() > STATS_STALE_NANOS
                ? Double.NaN
                : latest.tps();
    }

    @Override
    public void close() {
        closed = true;
        if (registered) proxy.getEventManager().unregisterListener(plugin, this);
        for (var session : List.copyOf(sessions.values())) session.close("proxy shutting down");
    }

    /** Velocity's player info forwarding mode, null when it cannot be read, and its secret while it is modern. */
    private static Forwarding forwarding(ProxyServer proxy) {
        try {
            Object configuration =
                    proxy.getClass().getMethod("getConfiguration").invoke(proxy);
            Object mode = configuration
                    .getClass()
                    .getMethod("getPlayerInfoForwardingMode")
                    .invoke(configuration);
            if (!(mode instanceof Enum<?> forwarding)) return new Forwarding(null, new byte[0]);
            if (!forwarding.name().equals("MODERN")) return new Forwarding(forwarding.name(), new byte[0]);
            Object secret =
                    configuration.getClass().getMethod("getForwardingSecret").invoke(configuration);
            return new Forwarding(forwarding.name(), secret instanceof byte[] bytes ? bytes.clone() : new byte[0]);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return new Forwarding(null, new byte[0]);
        }
    }

    /** {@code text} cut to {@code maxChars}, with control characters and section signs shown as {@code ?}. */
    public static String printable(String text, int maxChars) {
        int length = Math.min(text.length(), maxChars);
        var printable = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            printable.append(Character.isISOControl(c) || c == '§' ? '?' : c);
        }
        return printable.toString();
    }

    private static String describe(ProxyBridgeSession session) {
        var capabilities = new StringJoiner(", ");
        for (ServerCapability capability : ServerCapability.values()) {
            if (session.has(capability)) capabilities.add(capability.name());
        }
        return capabilities.toString();
    }

    void onReady(ProxyBridgeSession session) {
        String server = session.serverName();
        missingServers.remove(server);
        rejections.remove(server);
        if (activeServers.add(server)) {
            logger.info("CultAC bridge active on " + server + " ("
                    + printable(session.serverVersion(), MAX_LOGGED_REASON_CHARS) + "), capabilities "
                    + describe(session));
        }
        for (var listener : listeners) {
            try {
                listener.onReady(session);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Bridge listener failed on ready", failure);
            }
        }
    }

    void onMessage(ProxyBridgeSession session, BridgeMessage message) {
        if (message instanceof ServerStats update) {
            stats.put(session.serverName(), new Stats(update.tps(), System.nanoTime()));
        }
        for (var listener : listeners) {
            try {
                listener.onMessage(session, message);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Bridge listener failed on message type " + message.type(), failure);
            }
        }
    }

    void onRejected(ProxyBridgeSession session, String reason) {
        if (rejections.add(session.serverName())) {
            logger.warning("CultAC companion on " + session.serverName() + " rejected the bridge: "
                    + printable(reason, MAX_LOGGED_REASON_CHARS));
        }
    }

    void onNoCompanion(ProxyBridgeSession session) {
        if (missingServers.add(session.serverName())) {
            logger.warning("No CultAC companion answered on " + session.serverName()
                    + "; server-side features stay off there until a later join reaches one");
        }
    }

    void onAuthFailed(ProxyBridgeSession session) {
        if (authFailures.add(session.serverName())) {
            logger.warning("CultAC bridge on " + session.serverName() + ": backend failed authentication, so its "
                    + "sessions stay closed. Check the bridge secret there, or look for a plugin writing "
                    + "cultac:bridge frames.");
        }
    }

    void onClosed(ProxyBridgeSession session, boolean wasReady) {
        sessions.remove(session.playerId(), session);
        if (!wasReady) return;
        for (var listener : listeners) {
            try {
                listener.onClosed(session);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Bridge listener failed on close", failure);
            }
        }
    }

    void warn(String message) {
        logger.warning(message);
    }
}
