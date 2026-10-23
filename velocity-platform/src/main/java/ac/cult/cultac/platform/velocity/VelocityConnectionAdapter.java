package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.manager.tick.BackendTickEstimator;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.codec.ConnectionModelValues;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.util.concurrent.EventExecutor;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;

final class VelocityConnectionAdapter implements PlatformConnection {
    private final ProxyServer proxy;
    private final Player nativePlayer;
    private final VelocityPlayer player;
    private final EventExecutor owner;
    private final Object bedrockBridge;
    private VelocityProtocols.Versions protocols;
    private final ConnectionModelValues model;
    private CultConnection connection;
    private BackendTickEstimator backendTicks;
    private Object observedServer;
    private Channel observedBackend;
    private boolean backendTicksClosed;

    VelocityConnectionAdapter(ProxyServer proxy, Player nativePlayer, VelocityPlayer player, EventExecutor eventLoop) {
        this(proxy, nativePlayer, player, eventLoop, null, null);
    }

    VelocityConnectionAdapter(
            ProxyServer proxy,
            Player nativePlayer,
            VelocityPlayer player,
            EventExecutor eventLoop,
            WireValueDecoder codecs,
            PacketOwner bedrock) {
        this.proxy = proxy;
        this.nativePlayer = nativePlayer;
        this.player = player;
        // A Bedrock session stays on Geyser's tick loop, which orders its Bedrock and Java packets.
        this.owner = bedrock == null ? eventLoop : bedrock.executor();
        this.bedrockBridge = bedrock == null ? null : bedrock.bedrockBridge();
        this.model = new ConnectionModelValues(codecs);
    }

    EventExecutor owner() {
        return owner;
    }

    Object bedrockBridge() {
        return bedrockBridge;
    }

    void attach(CultConnection connection) {
        this.connection = connection;
        player.attach(connection);
        backendTicks = new BackendTickEstimator(
                this::serverTickEnd, this::schedule, this::tickPeriodNanos, this::refreshBackendObserver);
    }

    @Override
    public BackendTickEstimator backendTicks() {
        return backendTicks;
    }

    private void serverTickEnd() {
        CultPlayer target = connection.player();
        if (target != null && !target.isDisabled()) target.onServerTickEnd();
    }

    private AutoCloseable schedule(Runnable task, long delayNanos) {
        var future = owner.schedule(
                () -> {
                    if (!connection.disconnected()) connection.execute(task);
                },
                delayNanos,
                TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    private long tickPeriodNanos() {
        CultPlayer target = connection.player();
        if (target == null) return 50_000_000L;
        var state = target.packetStateData;
        if (state.serverTicksFrozen && state.serverFrozenTickStepsRemaining == 0) return 0;
        return (long) (1_000_000_000L / Math.max(1F, state.serverTickRate));
    }

    /**
     * Observes the bursts of the backend currently forwarding to this client. Runs at each time
     * synchronization (every 20 server ticks) on the connection owner, so a switch is followed within a second.
     */
    private void refreshBackendObserver() {
        if (backendTicksClosed || connection == null) return;
        Object current = nativePlayer.getCurrentServer().orElse(null);
        if (current == observedServer) return;
        removeBackendObserver();
        Channel backend = current == null ? null : VelocityBackendChannels.channel(current);
        if (backend == null) return;
        var observer = new VelocityBackendTickObserver(partial -> {
            if (nativePlayer.getCurrentServer().orElse(null) != current) return;
            long now = System.nanoTime();
            connection.execute(() -> backendTicks.onBurstEnd(partial, now));
        });
        backend.eventLoop().execute(() -> {
            if (backend.isActive() && backend.pipeline().get(VelocityBackendTickObserver.NAME) == null)
                backend.pipeline().addFirst(VelocityBackendTickObserver.NAME, observer);
        });
        observedServer = current;
        observedBackend = backend;
    }

    private void removeBackendObserver() {
        Channel backend = observedBackend;
        observedServer = null;
        observedBackend = null;
        if (backend == null) return;
        backend.eventLoop().execute(() -> {
            if (backend.pipeline().get(VelocityBackendTickObserver.NAME) != null)
                backend.pipeline().remove(VelocityBackendTickObserver.NAME);
        });
    }

    /** Detaches the backend observer and stops the timer; called when Cult detaches this connection. */
    void closeBackendTicks() {
        backendTicksClosed = true;
        removeBackendObserver();
        if (backendTicks != null) backendTicks.close();
    }

    void bindProtocols(VelocityProtocols.Versions protocols) {
        if (this.protocols != null) throw new IllegalStateException("Velocity client protocols already bound");
        this.protocols = Objects.requireNonNull(protocols);
    }

    @Override
    public ProtocolVersion getObservedProtocol() {
        return protocols == null ? null : protocols.observed();
    }

    @Override
    public ProtocolVersion getClientProtocol() {
        return protocols == null ? null : protocols.client();
    }

    /**
     * A 1.20.2+ client sends its brand as soon as it enters configuration, which Velocity reads before the PostLogin
     * attach installs Cult's decoder. Velocity keeps it and only replays it to backends.
     */
    @Override
    public String clientBrand() {
        return nativePlayer.getClientBrand();
    }

    /** Velocity sets the current server before it forwards that server's login. */
    @Override
    public String backendName() {
        return nativePlayer
                .getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse(null);
    }

    @Override
    public ConnectionModelValues modelValues() {
        return model;
    }

    @Override
    public User.Profile authenticatedProfile() {
        return new User.Profile(nativePlayer.getUniqueId(), nativePlayer.getUsername());
    }

    @Override
    public void disconnect(Component reason) {
        nativePlayer.disconnect(reason);
    }

    @Override
    public void sendMessage(Component message) {
        nativePlayer.sendMessage(message);
    }

    @Override
    public PlayerBinding playerBinding() {
        return new PlayerBinding() {
            @Override
            public VelocityPlayer player() {
                return player;
            }

            @Override
            public boolean isCurrent() {
                return nativePlayer.isActive()
                        && proxy.getPlayer(nativePlayer.getUniqueId()).orElse(null) == nativePlayer;
            }

            @Override
            public boolean matches(Object candidate) {
                return candidate == nativePlayer;
            }

            @Override
            public void initialize(CultPlayer target) {
                // Join/respawn packets supply client-visible entity, dimension and game mode.
                // Velocity has no authoritative world state to copy during configuration.
            }
        };
    }
}
