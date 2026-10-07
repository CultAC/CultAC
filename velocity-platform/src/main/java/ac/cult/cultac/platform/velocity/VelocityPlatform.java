package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
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
import io.netty.channel.Channel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import org.slf4j.Logger;

/** Proxy platform backed by bundled model tables and each connection's received packets. */
public final class VelocityPlatform implements PlatformLoader, AutoCloseable {
    private final ProxyServer proxy;
    private final Object nativePlugin;
    private final WireValueDecoder codecs;
    private final VelocityPlugin plugin;
    private final VelocityPermissions permissions = new VelocityPermissions();
    private final VelocitySenders senders;
    private final VelocityPlayers players;
    private final VelocityScheduler scheduler;
    private final VelocityPlugins plugins;
    private final CommandService commands;
    private final VelocityTransport transport;
    private final VelocityServer server;
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
            this.plugin = new VelocityPlugin(directory, logger);
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
            this.server = new VelocityServer(proxy, senders);
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
            CultAPI.INSTANCE.getExtensionManager().registerResolver(context -> context == nativePlugin ? plugin : null);
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

    public void start() {
        CultAPI.INSTANCE.load(this, (StartableInitable) this::startBedrockBridge);
        loaded = true;
        CultAPI.INSTANCE.start();
        plugin.getLogger().info("CultAC is inspecting Velocity client connections using the bundled 26.3 model");
        plugin.getLogger()
                .info(
                        "Proxy-only mode: server item-use verification, authoritative resends, pose resets, "
                                + "and administrative teleports/spectating are unavailable; packet prediction and setbacks remain active.");
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
        GrimAPIProvider.init(CultAPI.INSTANCE.getExternalAPI());
    }

    @Override
    public ItemResetHandler getItemResetHandler() {
        return new ItemResetHandler() {
            @Override
            public void resetItemUsage(PlatformPlayer player) {
                // A proxy cannot stop item use on the server.
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

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
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
            proxy.getEventManager().unregisterListeners(nativePlugin);
            scheduler.close();
            try {
                ProtocolCodecs.close();
            } catch (java.io.UncheckedIOException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Unable to close packet codecs", failure);
            }
        }
    }
}
