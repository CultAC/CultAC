package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.World;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.command.CommandService;
import ac.cult.cultac.platform.api.manager.ItemResetHandler;
import ac.cult.cultac.platform.api.manager.MessagePlaceHolderManager;
import ac.cult.cultac.platform.api.manager.PermissionRegistrationManager;
import ac.cult.cultac.platform.api.manager.PlatformPluginManager;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.player.PlatformPlayerFactory;
import ac.cult.cultac.platform.api.scheduler.PlatformScheduler;
import ac.cult.cultac.platform.api.sender.SenderFactory;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeService;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.platform.velocity.bridge.VelocityBackend;
import ac.cult.cultac.platform.velocity.bridge.VelocityBridge;
import ac.cult.cultac.platform.velocity.bridge.api.BridgeApiService;
import ac.cult.cultac.platform.velocity.bridge.command.BridgeCommandService;
import ac.cult.cultac.platform.velocity.cloud.CloudBackends;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.plugin.GrimPlugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.channel.Channel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** Proxy platform backed by bundled model tables and each connection's received packets. */
public final class VelocityPlatform implements PlatformLoader, AutoCloseable {
    private final ProxyServer proxy;
    private final Object nativePlugin;
    private final WireValueDecoder codecs;
    private final GrimPlugin plugin;
    private final VelocityPermissions permissions = new VelocityPermissions();
    private final VelocitySenders senders;
    private final VelocityPlayers players;
    private final VelocityScheduler scheduler;
    private final VelocityPlugins plugins;
    private final CommandService commands;
    private final VelocityTransport transport;
    private final VelocityServer server;
    private final BridgeService bridge;
    /** Set while the cloud upload is on and the bridge has started. */
    private volatile @Nullable CloudBackends cloudBackends;
    /** The bridge sessions the cloud's backends listen to; null until the bridge has started. */
    private @Nullable BridgeSessions cloudBridge;
    /** Whether the cloud upload is on, as its hooks last said. */
    private boolean cloudOn;

    private volatile boolean bedrockBridge;
    private boolean loaded;
    private boolean closed;

    public VelocityPlatform(ProxyServer proxy, Object nativePlugin, Path directory, Logger logger) throws Exception {
        this.proxy = proxy;
        this.nativePlugin = nativePlugin;
        var cleanup = new ArrayDeque<AutoCloseable>();
        try {
            this.codecs = ProtocolCodecs.decoder();
            cleanup.push(ProtocolCodecs::close);
            this.plugin = ac.cult.cultac.runtime.RuntimeEnvironment.host() == null
                    ? new VelocityPlugin(directory, logger)
                    : ac.cult.reload.host.SharedPlugin.create(
                            directory,
                            logger,
                            ac.cult.cultac.runtime.RuntimeEnvironment.host().build());
            this.senders = new VelocitySenders(proxy, permissions);
            this.players = new VelocityPlayers(proxy, senders);
            this.scheduler = new VelocityScheduler(world -> ((VelocityWorld) world).connection());
            cleanup.push(scheduler);
            this.plugins = new VelocityPlugins(proxy);
            this.commands = new VelocityCommands(proxy, senders).service(nativePlugin);
            var manager = CultAPI.INSTANCE.getNetworkManager();
            this.transport = new VelocityTransport(
                    proxy,
                    nativePlugin,
                    manager,
                    (player, channel, bedrock) -> new VelocityConnectionAdapter(
                            proxy,
                            player,
                            (VelocityPlayer) players.getFromNativePlayerType(player),
                            channel.eventLoop(),
                            codecs,
                            bedrock),
                    this::bedrockOwner);
            this.bridge = new BridgeService(proxy, nativePlugin, transport::channel, plugin.getLogger());
            this.server = new VelocityServer(proxy, senders, bridge::tps);
            manager.setPacketOwnerResolver(channel -> {
                var connection = manager.connection(channel);
                if (connection == null) return null;
                var adapter = (VelocityConnectionAdapter) connection.platform();
                return adapter.bedrockBridge() == null
                        ? null
                        : new PacketOwner(adapter.owner(), adapter.bedrockBridge());
            });
            // Vanilla's command packet uses FriendlyByteBuf.readUtf() (32767 characters).
            var runtime = ProtocolRuntime.create(
                    ProtocolData.load(ProtocolVersion.V26_3), ClientPacketCodecs.connectionCatalog(), 32767);
            manager.configureTransport(runtime, transport::install, () -> {}, transport::remove);
            if (ac.cult.cultac.runtime.RuntimeEnvironment.host() == null)
                CultAPI.INSTANCE
                        .getExtensionManager()
                        .registerResolver(context -> context == nativePlugin ? plugin : null);
        } catch (Exception | Error failure) {
            for (var resource : cleanup) {
                try {
                    resource.close();
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    public void load() {
        if (loaded) return;
        CultAPI.INSTANCE.load(this, (StartableInitable) this::startBedrockBridge);
        loaded = true;
        VelocityBridge.install(bridge);
    }

    public void start() {
        load();
        CloudModule.platformHooks(this::cloudTurnedOn, this::cloudTurnedOff);
        CultAPI.INSTANCE.start();
        boolean bridged = bridge.start(
                CultAPI.INSTANCE.getNetworkManager().dispatcher(),
                CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse("bridge.secret", ""));
        synchronized (this) {
            cloudBridge = bridged ? bridge : BridgeSessions.NONE;
            startCloudBackends();
        }
        plugin.getLogger().info("CultAC is inspecting Velocity client connections using the bundled 26.3 model");
        plugin.getLogger()
                .info("Proxy mode: resyncs use the server state each backend sent; server tick ends are estimated "
                        + "from the backend stream.");
        if (bridged) {
            plugin.getLogger()
                    .info("Backends running the CultAC companion get active-item clears, administrative teleports, "
                            + "spectating and real validation blocks; other backends run proxy-only.");
            bridge.addListener(new BridgeMessageListener() {
                @Override
                public void onReady(BridgeSession session) {
                    mirrorWorld(session.playerId(), session.worldUid());
                }

                @Override
                public void onMessage(BridgeSession session, BridgeMessage message) {
                    if (message instanceof World world) mirrorWorld(session.playerId(), world.uid());
                }
            });
            BridgeApiService.start();
            BridgeCommandService.start(proxy, nativePlugin, plugin.getLogger());
        } else {
            plugin.getLogger()
                    .info("Proxy-only mode: administrative teleports, spectating and real validation blocks require "
                            + "Paper or a backend running the CultAC companion.");
        }
    }

    private void mirrorWorld(UUID playerId, @Nullable UUID worldUid) {
        if (worldUid != null && players.getFromUUID(playerId) instanceof VelocityPlayer player) {
            player.getWorld().mirror(worldUid);
        }
    }

    private synchronized void cloudTurnedOn() {
        cloudOn = true;
        startCloudBackends();
    }

    private synchronized void cloudTurnedOff() {
        cloudOn = false;
        if (cloudBackends != null) cloudBackends.close();
        cloudBackends = null;
    }

    /** Binds capture segments to backends and collects the companions' facts while the upload is on. */
    private void startCloudBackends() {
        if (!cloudOn || cloudBackends != null || cloudBridge == null || closed) return;
        cloudBackends = new CloudBackends(proxy, nativePlugin, transport::channel, backendPlayProbe());
        cloudBackends.start(cloudBridge);
    }

    private Predicate<ServerConnection> backendPlayProbe() {
        try {
            return VelocityBackend.playStateProbe(proxy.getClass().getClassLoader());
        } catch (ReflectiveOperationException | LinkageError failure) {
            plugin.getLogger()
                    .warning("Unsupported Velocity backend connection internals: captures that span a backend's own "
                            + "reconfiguration are not uploaded");
            return server -> false;
        }
    }

    // Geyser loads first (a declared dependency); the bridge taps the Bedrock sessions it hosts here.
    private void startBedrockBridge() {
        if (proxy.getPluginManager().getPlugin("geyser").isEmpty()) return;
        try {
            GeyserBedrockBridgeRuntime.start();
            bedrockBridge = GeyserBedrockBridgeRuntime.isRunning();
        } catch (RuntimeException | LinkageError failure) {
            LogUtil.error("Unable to start the Geyser Bedrock bridge; Bedrock players cannot join", failure);
        }
    }

    private PacketOwner bedrockOwner(Channel channel) {
        return bedrockBridge ? GeyserBedrockBridgeRuntime.packetOwner(channel) : null;
    }

    @Override
    public PlatformScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public boolean failOnInitializationError() {
        return true;
    }

    @Override
    public PlatformPlayerFactory getPlatformPlayerFactory() {
        return players;
    }

    @Override
    public CommandService getCommandService() {
        return commands;
    }

    @Override
    public SenderFactory<?> getSenderFactory() {
        return senders;
    }

    @Override
    public GrimPlugin getPlugin() {
        return plugin;
    }

    @Override
    public PlatformPluginManager getPluginManager() {
        return plugins;
    }

    @Override
    public PlatformServer getPlatformServer() {
        return server;
    }

    @Override
    public PermissionRegistrationManager getPermissionManager() {
        return permissions;
    }

    @Override
    public MessagePlaceHolderManager getMessagePlaceHolderManager() {
        return (player, message) -> message;
    }

    @Override
    public void registerAPIService() {
        if (ac.cult.cultac.runtime.RuntimeEnvironment.host() == null)
            GrimAPIProvider.init(CultAPI.INSTANCE.getExternalAPI());
    }

    @Override
    public ItemResetHandler getItemResetHandler() {
        return new ItemResetHandler() {
            @Override
            public void resetItemUsage(PlatformPlayer player) {
                // A proxy clear must name the use it ends; this request names none.
            }

            @Override
            public boolean isUsingItem(PlatformPlayer player) {
                return false;
            }

            @Override
            public Hand getItemUsageHand(PlatformPlayer player) {
                return null;
            }
        };
    }

    VelocitySenders senders() {
        return senders;
    }

    void attach(com.velocitypowered.api.proxy.Player player, Channel channel) {
        var owner = bedrockOwner(channel);
        if (owner == null && new VelocityClientSupport(proxy).isBedrock(player.getUniqueId())) {
            VelocityTransport.refuse(
                    player, VelocityTransport.REFUSED, "Bedrock session without a Geyser bridge", null);
            return;
        }
        transport.attach(player, channel, owner, null);
    }

    java.util.Map<String, Object> release(Channel channel) {
        return transport.release(channel);
    }

    /** Adopts both halves of a translated session before forwarding its reconfiguration acknowledgement. */
    void adopt(com.velocitypowered.api.proxy.Player player, Channel channel, java.util.Map<String, Object> state) {
        if (state.get("bedrock") instanceof java.util.Map<?, ?> continued) {
            var session = state.get("bedrock-session");
            if (session == null || !ac.cult.cultac.runtime.RuntimeEnvironment.owns(session))
                throw new IllegalStateException("Missing transferred Bedrock session");
            GeyserBedrockBridgeRuntime.adoptSession(session, continued);
        }
        transport.attach(
                player, channel, bedrockOwner(channel), ac.cult.cultac.network.ConnectionContinuation.from(state));
        bridge.adopted(player);
        CloudBackends backends = cloudBackends;
        if (backends != null) backends.adopted(player);
    }

    java.util.concurrent.CompletionStage<Void> disconnect(Channel channel) {
        var connection = CultAPI.INSTANCE.getNetworkManager().connection(channel);
        if (connection == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return transport
                .detach(connection)
                .thenCompose(ignored -> CultAPI.INSTANCE.getNetworkManager().disconnect(channel));
    }

    @Override
    public synchronized void close() {
        BridgeCommandService.stop();
        BridgeApiService.stop();
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (cloudBackends != null) cloudBackends.close();
            bridge.close();
            VelocityBridge.install(BridgeSessions.NONE);
            scheduler.cancel(plugin);
            if (loaded) {
                CultAPI.INSTANCE.stop();
            } else {
                CultAPI.INSTANCE
                        .getNetworkManager()
                        .stop()
                        .toCompletableFuture()
                        .join();
            }
        } finally {
            if (ac.cult.cultac.runtime.RuntimeEnvironment.host() == null)
                proxy.getEventManager().unregisterListeners(nativePlugin);
            scheduler.close();
            ProtocolCodecs.close();
        }
    }
}
