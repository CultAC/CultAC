package ac.cult.cultac.platform.velocity.cloud;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CloudFacts;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Marker;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerFacts;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.cloud.CloudCapture;
import ac.cult.cultac.cloud.SegmentBinder;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.player.CultPlayer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.channel.Channel;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * The proxy's side of the cloud upload: binds each capture segment to the backend it belongs to, keeps the facts each
 * backend's companion reports, and carries a capture's markers to the companion and its answers back.
 *
 * <p>A segment starts unbound at the play {@code login} the proxy forwards, when Velocity still names the previous
 * backend as the player's current server. {@link ServerPostConnectEvent}, handled on the player's event loop, binds it
 * to the backend the player then is on. A backend that reconfigures its own connection fires no such event, so a
 * segment that ended on a connection polls until the same connection is back in play and binds to it. A segment that
 * starts mid-play binds to the backend the player is in play on. The capture discards a segment left unbound too long.
 *
 * <p>A bind waits, up to {@link #FACTS_WAIT_MILLIS}, while the player's bridge session to that backend has not yet
 * brought the backend's facts, so the segment's JOIN carries them. It does not wait without a session, to a backend
 * whose sessions have closed without reaching READY, or whose companion is READY without {@code CLOUD_FACTS}; on a
 * proxy without the bridge it never waits.
 */
public final class CloudBackends implements SegmentBinder, BridgeMessageListener, AutoCloseable {
    static final long POLL_MILLIS = 50;
    /** The longest a bind waits for the backend's facts over a bridge session that is not yet READY. */
    static final long FACTS_WAIT_MILLIS = 1000;

    private static volatile @Nullable CloudBackends installed;

    private record PlayerSettings(String server, Map<String, String> settings) {}

    private final ProxyServer proxy;
    private final Object plugin;
    private final Function<Player, Channel> channels;
    private final Predicate<ServerConnection> backendInPlay;
    private final long pollMillis;
    private final long factsWaitMillis;
    private final LongSupplier clock;
    private volatile BridgeSessions bridge = BridgeSessions.NONE;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();
    /** The backend connection each player's last segment ended on at a {@code start_configuration}. */
    private final Map<UUID, ServerConnection> reconfiguring = new ConcurrentHashMap<>();

    private final Map<String, ServerFacts> backends = new ConcurrentHashMap<>();
    /** Backends whose bridge sessions have reached READY: they run a companion. */
    private final Set<String> companions = ConcurrentHashMap.newKeySet();
    /** Backends whose bridge session closed without one ever reaching READY: a bind there does not wait. */
    private final Set<String> withoutCompanion = ConcurrentHashMap.newKeySet();

    private final Map<UUID, PlayerSettings> players = new ConcurrentHashMap<>();
    private volatile Consumer<UUID> factsChanged = CloudBackends::toCapture;
    private volatile boolean closed;

    public CloudBackends(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            Predicate<ServerConnection> backendInPlay) {
        this(proxy, plugin, channels, backendInPlay, POLL_MILLIS, FACTS_WAIT_MILLIS, System::nanoTime);
    }

    /** As above, polling every {@code pollMillis} and waiting for facts at most {@code factsWaitMillis} on {@code clock}. */
    CloudBackends(
            ProxyServer proxy,
            Object plugin,
            Function<Player, Channel> channels,
            Predicate<ServerConnection> backendInPlay,
            long pollMillis,
            long factsWaitMillis,
            LongSupplier clock) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channels = channels;
        this.backendInPlay = backendInPlay;
        this.pollMillis = pollMillis;
        this.factsWaitMillis = factsWaitMillis;
        this.clock = clock;
    }

    /**
     * Binds every capture segment through this instance and starts collecting the companions' facts;
     * {@link BridgeSessions#NONE} on a proxy without the bridge.
     */
    public void start(BridgeSessions bridge) {
        this.bridge = bridge;
        bridge.addListener(this);
        proxy.getEventManager().register(plugin, this);
        installed = this;
        CloudCapture.segmentBinder(this);
    }

    /**
     * The facts of {@code player} on backend {@code server}: the backend's, with the player's laid on top while they
     * came from that backend. {@link ServerFacts#NONE} without a companion there. Any thread.
     */
    public static ServerFacts facts(UUID player, String server) {
        CloudBackends current = installed;
        return current == null ? ServerFacts.NONE : current.factsOf(player, server);
    }

    ServerFacts factsOf(UUID player, String server) {
        ServerFacts backend = backends.getOrDefault(server, ServerFacts.NONE);
        PlayerSettings settings = players.get(player);
        return settings == null || !settings.server().equals(server)
                ? backend
                : backend.merge(ServerFacts.settings(settings.settings()));
    }

    /** Called with a player's id, on any thread, whenever its facts change after its backend's READY. */
    void onFactsChanged(Consumer<UUID> listener) {
        factsChanged = listener;
    }

    private static void toCapture(UUID id) {
        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(id);
        if (player != null) CloudCapture.factsChanged(player);
    }

    /** On the player's event loop; mid-play, binds to the backend the player is in play on, else as a login does. */
    @Override
    public void started(CultPlayer player, Consumer<String> bind, boolean playing) {
        if (closed) return;
        UUID id = player.getUniqueId();
        Player velocity = proxy.getPlayer(id).orElse(null);
        Channel channel = velocity == null ? null : channel(velocity);
        if (channel == null) return;
        pending.put(id, bind);
        ServerConnection server = playing ? velocity.getCurrentServer().orElse(null) : null;
        if (server != null && backendInPlay.test(server)) {
            reconfiguring.remove(id);
            bindWhenReady(id, bind, server, channel, clock.getAsLong(), false);
            return;
        }
        ServerConnection ended = reconfiguring.get(id);
        if (ended != null) schedulePoll(id, bind, velocity, ended, channel);
    }

    @Override
    public void abandoned(CultPlayer player) {
        pending.remove(player.getUniqueId());
    }

    @Override
    public void configuring(CultPlayer player) {
        UUID id = player.getUniqueId();
        pending.remove(id);
        ServerConnection server =
                proxy.getPlayer(id).flatMap(Player::getCurrentServer).orElse(null);
        if (server == null) reconfiguring.remove(id);
        else reconfiguring.put(id, server);
    }

    /**
     * A connection adopted from another runtime at a configuration acknowledgement: its next segment binds as after a
     * {@code start_configuration} this runtime saw. On the player's event loop.
     */
    public void adopted(Player player) {
        if (closed) return;
        player.getCurrentServer().ifPresent(server -> reconfiguring.put(player.getUniqueId(), server));
    }

    @Override
    public void disconnected(CultPlayer player) {
        forget(player.getUniqueId());
    }

    @Override
    public boolean marks(CultPlayer player) {
        return markerSession(player) != null;
    }

    @Override
    public void mark(CultPlayer player, int marker) {
        BridgeSession session = markerSession(player);
        if (session != null) session.send(new Marker(marker));
    }

    private @Nullable BridgeSession markerSession(CultPlayer player) {
        BridgeSession session = bridge.current(player.getUniqueId());
        return session != null && session.has(ServerCapability.MARKER) ? session : null;
    }

    private void schedulePoll(UUID id, Consumer<String> entry, Player player, ServerConnection ended, Channel channel) {
        channel.eventLoop().schedule(() -> poll(id, entry, player, ended, channel), pollMillis, TimeUnit.MILLISECONDS);
    }

    /** Binds to {@code ended} once the player is back in play on it; PostConnect binds a switch. */
    private void poll(UUID id, Consumer<String> entry, Player player, ServerConnection ended, Channel channel) {
        if (closed || pending.get(id) != entry || reconfiguring.get(id) != ended) return;
        if (!channel.isActive()) {
            pending.remove(id, entry);
            return;
        }
        if (player.getCurrentServer().orElse(null) != ended) return;
        if (!backendInPlay.test(ended)) {
            schedulePoll(id, entry, player, ended, channel);
            return;
        }
        reconfiguring.remove(id, ended);
        bindWhenReady(id, entry, ended, channel, clock.getAsLong(), false);
    }

    /**
     * Binds once the backend's facts are in, so the JOIN carries them. It waits only while the player has a bridge
     * session to {@code server} that has not brought them, up to {@link #factsWaitMillis} from {@code since}; one
     * poll gives a session opening on the same connect event time to appear.
     */
    private void bindWhenReady(
            UUID id, Consumer<String> entry, ServerConnection server, Channel channel, long since, boolean looked) {
        if (closed || pending.get(id) != entry) return;
        if (!channel.isActive()) {
            pending.remove(id, entry);
            return;
        }
        String name = server.getServerInfo().getName();
        BridgeSessions sessions = bridge;
        BridgeSession session = sessions.current(id);
        boolean waiting = sessions != BridgeSessions.NONE
                && !withoutCompanion.contains(name)
                && (session == null ? !looked : name.equals(session.serverName()) && bringsFacts(session, name));
        if (!waiting || clock.getAsLong() - since >= TimeUnit.MILLISECONDS.toNanos(factsWaitMillis)) {
            bind(id, entry, name);
            return;
        }
        channel.eventLoop()
                .schedule(
                        () -> bindWhenReady(id, entry, server, channel, since, true),
                        pollMillis,
                        TimeUnit.MILLISECONDS);
    }

    /** Whether {@code session} may still bring backend {@code server}'s facts: before READY, or READY with them. */
    private boolean bringsFacts(BridgeSession session, String server) {
        return !factsReady(server) && (!session.isReady() || session.has(ServerCapability.CLOUD_FACTS));
    }

    /** Whether the backend's companion has reported its software and tick length. */
    private boolean factsReady(String server) {
        ServerFacts facts = backends.get(server);
        return facts != null && facts.server() != null && facts.tickMicros() > 0;
    }

    private void bind(UUID id, Consumer<String> entry, String server) {
        if (pending.remove(id, entry)) entry.accept(server);
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (closed) return;
        Player player = event.getPlayer();
        Channel channel = channel(player);
        if (channel == null) return;
        UUID id = player.getUniqueId();
        channel.eventLoop().execute(() -> {
            reconfiguring.remove(id);
            Consumer<String> entry = pending.get(id);
            if (entry == null || closed || !channel.isActive()) return;
            ServerConnection server = player.getCurrentServer().orElse(null);
            if (server != null) bindWhenReady(id, entry, server, channel, clock.getAsLong(), false);
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    private void forget(UUID id) {
        pending.remove(id);
        reconfiguring.remove(id);
        players.remove(id);
    }

    @Override
    public void onReady(BridgeSession session) {
        companions.add(session.serverName());
        withoutCompanion.remove(session.serverName());
        String version = session.serverVersion();
        if (version.isEmpty()) return;
        backends.compute(session.serverName(), (name, known) -> {
            if (known == null) return new ServerFacts(0, version, Map.of());
            return version.equals(known.server())
                    ? known
                    : new ServerFacts(known.tickMicros(), version, known.settings());
        });
    }

    @Override
    public void onMessage(BridgeSession session, BridgeMessage message) {
        switch (message) {
            case CloudFacts facts ->
                backends.compute(
                        session.serverName(),
                        (name, known) -> new ServerFacts(
                                facts.tickMicros(), known == null ? null : known.server(), facts.settings()));
            case PlayerFacts facts ->
                players.put(session.playerId(), new PlayerSettings(session.serverName(), facts.settings()));
            case Marker marker -> {
                CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(session.playerId());
                if (player != null) CloudCapture.markerAnswered(player, marker.marker());
                return;
            }
            default -> {
                return;
            }
        }
        factsChanged.accept(session.playerId());
    }

    @Override
    public void onClosed(BridgeSession session) {
        if (!companions.contains(session.serverName())) withoutCompanion.add(session.serverName());
        players.computeIfPresent(
                session.playerId(), (id, settings) -> settings.server().equals(session.serverName()) ? null : settings);
    }

    private @Nullable Channel channel(Player player) {
        try {
            return channels.apply(player);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (installed == this) {
            installed = null;
            CloudCapture.segmentBinder(SegmentBinder.NONE);
        }
        try {
            proxy.getEventManager().unregisterListener(plugin, this);
        } catch (RuntimeException | LinkageError ignored) {
            // Never registered when start was not reached.
        }
        pending.clear();
        reconfiguring.clear();
        players.clear();
        backends.clear();
        companions.clear();
        withoutCompanion.clear();
    }
}
