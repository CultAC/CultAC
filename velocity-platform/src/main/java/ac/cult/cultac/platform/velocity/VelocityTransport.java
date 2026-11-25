package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.network.ConnectionContinuation;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.DormancyController;
import ac.cult.cultac.network.DormantWatch;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.RuntimeInstance;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/** Intercepts client-facing frames, after Velocity's entity-ID rewriting and before compression. */
final class VelocityTransport {
    static final Component REFUSED = Component.text(RuntimeInstance.CONNECTION_REFUSED);

    @FunctionalInterface
    interface AdapterFactory {
        PlatformConnection create(Player player, Channel channel, PacketOwner bedrock);
    }

    private final ProxyServer proxy;
    private final Object plugin;
    private final CultNetworkManager manager;
    private final AdapterFactory platform;
    private final Function<Channel, PacketOwner> bedrockOwners;
    private final Method connectionMethod;
    private final Method channelMethod;
    private final VelocityClientSupport clients;
    private final @Nullable VelocityCompressedForward compressedForward;
    private volatile @Nullable VelocityDormancy dormancy;
    private volatile boolean running;

    VelocityTransport(
            ProxyServer proxy,
            Object plugin,
            CultNetworkManager manager,
            AdapterFactory platform,
            Function<Channel, PacketOwner> bedrockOwners,
            @Nullable VelocityCompressedForward compressedForward) {
        this.proxy = proxy;
        this.compressedForward = compressedForward;
        this.plugin = plugin;
        this.manager = manager;
        this.platform = platform;
        this.bedrockOwners = bedrockOwners;
        this.clients = new VelocityClientSupport(proxy);
        try {
            // Velocity has no public packet API. Resolve this narrow, checked boundary once.
            ClassLoader loader = proxy.getClass().getClassLoader();
            connectionMethod = Class.forName(
                            "com.velocitypowered.proxy.connection.client.ConnectedPlayer", false, loader)
                    .getMethod("getConnection");
            channelMethod = connectionMethod.getReturnType().getMethod("getChannel");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unsupported Velocity transport; expected ConnectedPlayer.getConnection().getChannel()", failure);
        }
    }

    synchronized void install() {
        if (ProtocolVersion.MAXIMUM_VERSION.getProtocol() > VelocityProtocols.NEWEST.protocol()) {
            LogUtil.warn("This Velocity build accepts Minecraft "
                    + ProtocolVersion.MAXIMUM_VERSION.getMostRecentSupportedVersion()
                    + "; CultAC supports up to " + VelocityProtocols.NEWEST.minecraftVersion()
                    + " and disconnects newer clients");
        }
        if (RuntimeEnvironment.host() != null) {
            running = true;
            return;
        }
        if (!proxy.getAllPlayers().isEmpty()) {
            throw new IllegalStateException("CultAC must start before players connect");
        }
        running = true;
        proxy.getEventManager().register(plugin, this);
    }

    @Subscribe
    public EventTask login(PostLoginEvent event) {
        // Velocity awaits this event in CONFIG before connecting the first backend.
        Player player = event.getPlayer();
        if (!running) {
            return null;
        }
        final Channel channel;
        final PacketOwner bedrock;
        try {
            channel = channel(player);
            // Geyser sends login acknowledgement before its translator registers the Java UUID.
            // The downstream channel already exists, so its bridge tap is the connection's identity.
            bedrock = bedrockOwners.apply(channel);
            boolean translated = bedrock != null || clients.isBedrock(player.getUniqueId());
            // A Bedrock session needs the Geyser bridge's tap; never inspect it as a Java client.
            if (translated && bedrock == null) {
                refuseUnbridged(player);
                return null;
            }
        } catch (Throwable failure) {
            refuse(player, REFUSED, "Velocity login channel lookup failed", failure);
            return null;
        }
        CompletableFuture<Void> attached;
        try {
            attached = CompletableFuture.runAsync(
                    () -> {
                        if (running && channel.isActive()) {
                            join(player, channel, bedrock);
                        }
                    },
                    channel.eventLoop());
        } catch (Throwable failure) {
            attached = CompletableFuture.failedFuture(failure);
        }
        // Velocity logs an exceptional PostLogin task and admits the player, so a failed attach disconnects them.
        return EventTask.resumeWhenComplete(attached.handle((ignored, failure) -> {
            if (failure != null && channel.isActive()) {
                refuse(player, REFUSED, "Velocity connection attachment failed", failure);
            }
            return null;
        }));
    }

    /** A Bedrock session without a Geyser bridge joins uninspected where Bedrock players are exempt, else not at all. */
    static void refuseUnbridged(Player player) {
        if (CultAPI.INSTANCE.getConfigManager().isExemptBedrockPlayers()) return;
        refuse(player, REFUSED, "Bedrock session without a Geyser bridge", null);
    }

    /** Disconnects a client CultAC cannot inspect unless failing open lets it join uninspected. */
    static void refuse(Player player, Component message, String why, Throwable failure) {
        if (CultAPI.INSTANCE.getConfigManager().failsOpen()) {
            String line = "Not inspecting " + player.getUsername() + "; " + why;
            if (failure == null) LogUtil.warn(line);
            else LogUtil.warn(line, failure);
            return;
        }
        player.disconnect(message);
        // A client shown its own reason, such as an outdated version, is routine and not logged.
        if (message != REFUSED) return;
        String line = "Disconnected " + player.getUsername() + "; " + why;
        if (failure == null) LogUtil.warn(line);
        else LogUtil.error(line, failure);
    }

    Channel channel(Player player) {
        try {
            return (Channel) channelMethod.invoke(connectionMethod.invoke(player));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access Velocity client channel", failure);
        }
    }

    void dormancy(VelocityDormancy dormancy) {
        this.dormancy = dormancy;
    }

    boolean running() {
        return running;
    }

    /**
     * A connection at its PostLogin: a dormant Java player gets only a watch, a dormant Bedrock player CultAC's handlers
     * and no CultAC player, everyone else is attached.
     */
    void join(Player player, Channel channel, PacketOwner bedrock) {
        VelocityDormancy dormant = dormancy;
        if (running && dormant != null && dormant.joined(player, channel, bedrock)) return;
        attach(player, channel, bedrock, null);
    }

    synchronized void attach(Player player, Channel channel, PacketOwner bedrock, ConnectionContinuation continued) {
        attach(player, channel, bedrock, continued, false);
    }

    /** Attaches a Bedrock connection whose player is dormant: CultAC's handlers and no CultAC player. */
    synchronized void attachDormant(Player player, Channel channel, PacketOwner bedrock) {
        attach(player, channel, bedrock, null, true);
    }

    private void attach(
            Player player, Channel channel, PacketOwner bedrock, ConnectionContinuation continued, boolean dormant) {
        if (!running) {
            return;
        }
        if (VelocityProtocols.disconnectUnsupportedClient(player, channel)) return;
        var pipeline = channel.pipeline();
        if (pipeline.get("minecraft-decoder") == null || pipeline.get("minecraft-encoder") == null) {
            throw new IllegalStateException("Unsupported Velocity client pipeline: " + pipeline.names());
        }
        if (manager.connection(channel) != null) {
            throw new IllegalStateException("Velocity connection already attached");
        }
        var protocols = VelocityProtocols.read(player, channel);
        var adapter = (VelocityConnectionAdapter) platform.create(player, channel, bedrock);
        adapter.bindProtocols(protocols);
        var connection = manager.createConnection(adapter, channel);
        adapter.attach(connection);
        connection.continuation(continued);
        if (dormant) DormancyController.joinedDormant(connection);
        connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
        connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
        connection.resolveOwner();
        // Authentication enables compression (including Via's relocation) before
        // PostLogin CONFIG. Keep these handlers stable for the whole session.
        VelocityPacketPipeline.install(connection);
        if (compressedForward != null) compressedForward.installClient(connection, adapter, player);
        connection.execute(connection::prepare);
    }

    synchronized CompletionStage<Void> remove() {
        running = false;
        if (RuntimeEnvironment.host() == null) proxy.getEventManager().unregisterListener(plugin, this);
        VelocityDormancy dormant = dormancy;
        CompletableFuture<Void> detached = CompletableFuture.allOf(
                manager.connections().stream().map(this::detach).toArray(CompletableFuture[]::new));
        return dormant == null ? detached : detached.thenCompose(ignored -> dormant.remove());
    }

    /**
     * Releases a connection on its packet owner, before the acknowledgement reaches Velocity. A dormant connection's
     * state carries {@link DormantWatch#DORMANT} and its retired pings; a dormant Bedrock connection, with its Geyser
     * session, stays with this engine until it closes.
     *
     * @return the client state to continue, or null when this engine keeps the connection
     */
    Map<String, Object> release(Channel channel) {
        var connection = manager.connection(channel);
        if (connection == null
                || !connection.owner().inEventLoop()
                || connection.phase(PacketDirection.SERVERBOUND) != ConnectionPhase.PLAY
                || connection.phase(PacketDirection.CLIENTBOUND) != ConnectionPhase.CONFIGURATION) return null;
        var dormant = connection.dormant();
        if (dormant != null && connection.bedrockBridge() != null) return null;
        Map<String, Object> state =
                dormant == null ? ConnectionContinuation.capture(connection) : new HashMap<>(dormant);
        if (dormant != null) state.put(DormantWatch.DORMANT, true);
        else if (connection.bedrockBridge() != null) {
            var bedrock = GeyserBedrockBridgeRuntime.releaseSession(connection.bedrockBridge());
            if (bedrock == null) return null;
            state.put("bedrock-session", connection.bedrockBridge());
            state.put("bedrock", bedrock);
        }
        CloudModule.released(connection.player());
        connection
                .removeHandlers(() -> removeHandlers(connection))
                .toCompletableFuture()
                .join();
        manager.release(channel);
        if (dormant != null) state.put(DormantWatch.RETIRED, connection.drainRetiredPings());
        return state;
    }

    CompletableFuture<Void> detach(CultConnection connection) {
        try {
            // Enter I/O before requesting removal; removal waits for accepted packet work.
            return CompletableFuture.supplyAsync(
                            () -> connection.removeHandlers(() -> removeHandlers(connection)),
                            connection.channel().eventLoop())
                    .thenCompose(CompletionStage::toCompletableFuture);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Removes Cult's handlers and backend tick observer from {@code connection}; inside its removal. */
    void removeHandlers(CultConnection connection) {
        Channel channel = connection.channel();
        ((VelocityConnectionAdapter) connection.platform()).closeBackendTicks();
        if (compressedForward != null) VelocityCompressedForward.uninstall(channel);
        CultDecoder.uninstall(channel);
        CultEncoder.uninstall(channel);
    }
}
