package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.manager.PermissionRegistrationManager;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.mockito.Mockito;

public final class OfflineCultTestBootstrap {
    private static boolean networkConfigured;

    private static boolean installed;
    private static final Map<String, Double> DOUBLE_CONFIG_OVERRIDES = new ConcurrentHashMap<>();

    private OfflineCultTestBootstrap() {}

    public static void installConfig() {
        if (installed) {
            initializeProtocolRuntime();
            return;
        }
        initializeProtocolRuntime();
        installPlatformLoader();
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getIntElse(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Mockito.when(config.getBooleanElse(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        Mockito.when(config.getBooleanElse("experimental-checks", false)).thenReturn(true);
        Mockito.when(config.getDoubleElse(Mockito.anyString(), Mockito.anyDouble()))
                .thenAnswer(invocation ->
                        DOUBLE_CONFIG_OVERRIDES.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        BaseConfigManager configManager = new ReplayConfigManager(config);

        try {
            Field field = CultAPI.class.getDeclaredField("configManager");
            field.setAccessible(true);
            field.set(CultAPI.INSTANCE, configManager);
            installed = true;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install offline Cult config", exception);
        }
    }

    private static void initializeProtocolRuntime() {
        var manager = CultAPI.INSTANCE.getNetworkManager();
        if (!networkConfigured) {
            networkConfigured = true;
            manager.configureTransport(
                    ac.cult.cultac.network.TestProtocolRuntime.create(ac.cult.cultac.protocol.data.ProtocolData.load(
                            ac.cult.cultac.protocol.ProtocolVersion.V26_3)),
                    () -> {},
                    () -> java.util.concurrent.CompletableFuture.completedFuture(null));
        }
    }

    /** Offline state tests still encode authored records through the real transport. */
    static ac.cult.cultac.network.protocol.player.User wireUser(
            ac.cult.cultac.network.protocol.player.User.Profile profile) {
        return wireUser(profile, null);
    }

    /** A non-null bridge attaches the connection to a Geyser session through owner resolution. */
    static ac.cult.cultac.network.protocol.player.User wireUser(
            ac.cult.cultac.network.protocol.player.User.Profile profile, Object bedrockBridge) {
        var runtime =
                CultAPI.INSTANCE.getNetworkManager().dispatcher().scanner().runtime();
        var channel = new io.netty.channel.embedded.EmbeddedChannel();
        channel.pipeline().addLast("splitter", new io.netty.channel.ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("decoder", new io.netty.channel.ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new io.netty.channel.ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new io.netty.channel.ChannelOutboundHandlerAdapter());
        // Offline tests invoke consumers directly. Routes are empty so authored
        // packets are encoded without running those consumers a second time.
        var routes = new ac.cult.cultac.network.PacketDispatcher(runtime);
        var transport = new ac.cult.cultac.network.CultConnection(
                channel,
                routes,
                ignored -> bedrockBridge == null
                        ? null
                        : new ac.cult.cultac.network.PacketOwner(channel.eventLoop(), bedrockBridge));
        ac.cult.cultac.protocol.netty.CultDecoder.install(transport);
        ac.cult.cultac.protocol.netty.CultEncoder.install(transport);
        for (var direction : ac.cult.cultac.protocol.PacketDirection.values())
            transport.phase(direction, ac.cult.cultac.protocol.ConnectionPhase.PLAY);
        transport.resolveOwner();
        var user = new ac.cult.cultac.network.protocol.player.User(profile, transport);
        return user;
    }

    static void setDoubleConfigOverride(String key, double value) {
        DOUBLE_CONFIG_OVERRIDES.put(key, value);
    }

    static void clearDoubleConfigOverride(String key) {
        DOUBLE_CONFIG_OVERRIDES.remove(key);
    }

    /** Default fixtures use the bundled names and client-visible world facts. */
    public static ac.cult.cultac.network.PlatformConnection platformConnection() {
        var platform = Mockito.mock(ac.cult.cultac.network.PlatformConnection.class);
        Mockito.doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(platform)
                .runInModel(Mockito.any(Runnable.class));
        Mockito.when(platform.registryNames())
                .thenReturn(ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults());
        Mockito.when(platform.initialWorldData())
                .thenReturn(ac.cult.cultac.utils.latency.ClientWorldRegistries.modelDefaults());
        return platform;
    }

    public static java.util.List<ac.cult.cultac.protocol.PacketType<?>> catalog() {
        var world = new ac.cult.cultac.utils.latency.ClientWorldRegistries(
                ac.cult.cultac.utils.latency.ClientWorldRegistries.modelDefaults());
        return ac.cult.cultac.network.codec.ClientPacketCodecs.catalog(
                context -> ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults(), context -> world);
    }

    public static ac.cult.blocksim.engine.SimItemStack item(String key) {
        return item(key, 1);
    }

    public static ac.cult.blocksim.engine.SimItemStack item(String key, int count) {
        return ac.cult.cultac.utils.inventory.ItemUtil.modelItems().stack(key, count);
    }

    private static void installPlatformLoader() {
        PermissionRegistrationManager noOpPermissions = (name, defaultValue) -> {};
        GrimPlugin grimPlugin = (GrimPlugin) Proxy.newProxyInstance(
                GrimPlugin.class.getClassLoader(),
                new Class<?>[] {GrimPlugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getLogger("OfflineBedrockReplay");
                    case "getDataFolder" -> new File("build/offline-bedrock-replay-plugin");
                    default -> defaultValue(method.getReturnType());
                });
        PlatformLoader loader = (PlatformLoader) Proxy.newProxyInstance(
                PlatformLoader.class.getClassLoader(),
                new Class<?>[] {PlatformLoader.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPermissionManager" -> noOpPermissions;
                    case "getPluginManager" ->
                        Mockito.mock(ac.cult.cultac.platform.api.manager.PlatformPluginManager.class);
                    case "getPlatformServer" -> Mockito.mock(ac.cult.cultac.platform.api.PlatformServer.class);
                    case "getPlugin" -> grimPlugin;
                    default -> defaultValue(method.getReturnType());
                });
        try {
            Field loaderField = CultAPI.class.getDeclaredField("loader");
            loaderField.setAccessible(true);
            loaderField.set(CultAPI.INSTANCE, loader);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install offline Cult platform loader", exception);
        }
    }

    private static final class ReplayConfigManager extends BaseConfigManager {
        private final ConfigManager config;

        private ReplayConfigManager(ConfigManager config) {
            this.config = config;
        }

        @Override
        public ConfigManager getConfig() {
            return config;
        }

        @Override
        public boolean isBedrockMovementSetbacksEnabled() {
            return true;
        }

        @Override
        public double getBedrockMovementPositionFlagThreshold() {
            return 0.001D;
        }

        @Override
        public double getBedrockMovementVelocityFlagThreshold() {
            return 0.001D;
        }

        @Override
        public boolean isVerboseBedrockMovement() {
            return false;
        }

        @Override
        public boolean isVerboseBedrockMovementLogCleanOffsets() {
            return false;
        }

        @Override
        public double getVerboseBedrockMovementMinOffset() {
            return 0.0D;
        }

        @Override
        public double getVerboseBedrockMovementCooldownSeconds() {
            return 0.0D;
        }

        // isExperimentalChecks()/getMaxPingTransaction() overrides dropped: neither exists on the
        // merged BaseConfigManager (experimental checks are stubbed on the mock above; the engine
        // reads the clamped max-transaction-time key, which the mock default-answers).
        @Override
        public int getMaxPingKnockback() {
            return 1000;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0F;
        }
        if (returnType == double.class) {
            return 0.0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
