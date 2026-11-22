package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.manager.PermissionRegistrationManager;
import ac.cult.cultac.platform.api.manager.PlatformPluginManager;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.plugin.GrimPlugin;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
            loadBedrockBlockShapes();
            return;
        }
        initializeProtocolRuntime();
        installPlatformLoader();
        // The config outlives every test in the fork and is read on every player's creation: a plain proxy keeps
        // those reads cheap. It answers each default, with the experimental checks on and any double override.
        ConfigManager config = (ConfigManager) Proxy.newProxyInstance(
                ConfigManager.class.getClassLoader(), new Class<?>[] {ConfigManager.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getBooleanElse") && "experimental-checks".equals(args[0])) return true;
                    if (name.equals("getDoubleElse")) return DOUBLE_CONFIG_OVERRIDES.getOrDefault(args[0], (Double) args[1]);
                    if (name.endsWith("Else") && args != null && args.length == 2) return args[1];
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return proxy == args[0];
                    if (name.equals("toString")) return "OfflineCultTestConfig";
                    return unconfigured(method.getReturnType());
                });

        BaseConfigManager configManager = new ReplayConfigManager(config);

        try {
            Field field = CultAPI.class.getDeclaredField("configManager");
            field.setAccessible(true);
            field.set(CultAPI.INSTANCE, configManager);
            installed = true;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install offline Cult config", exception);
        }
        loadBedrockBlockShapes();
    }

    /** Bedrock players collide with the client's block shapes, loaded from the catalog the Geyser bridge loads. */
    private static void loadBedrockBlockShapes() {
        if (!BedrockClientBlockShapeMappings.isInitialized()) {
            BedrockClientBlockShapeMappings.initialize();
        }
    }

    /** What an unconfigured config answers: nothing, zero, false or an empty collection. */
    private static Object unconfigured(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0D;
        if (type == float.class) return 0.0F;
        if (type == List.class) return List.of();
        if (type == Set.class) return Set.of();
        if (type == Map.class) return Map.of();
        if (type == Optional.class) return Optional.empty();
        return null;
    }

    private static void initializeProtocolRuntime() {
        var manager = CultAPI.INSTANCE.getNetworkManager();
        if (!networkConfigured) {
            networkConfigured = true;
            manager.configureTransport(
                    TestProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3)),
                    () -> {},
                    () -> CompletableFuture.completedFuture(null));
        }
    }

    /** Offline state tests still encode authored records through the real transport. */
    static User wireUser(User.Profile profile) {
        return wireUser(profile, null);
    }

    /** A non-null bridge attaches the connection to a Geyser session through owner resolution. */
    static User wireUser(User.Profile profile, Object bedrockBridge) {
        var runtime =
                CultAPI.INSTANCE.getNetworkManager().dispatcher().scanner().runtime();
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        // Offline tests invoke consumers directly. Routes are empty so authored
        // packets are encoded without running those consumers a second time.
        var routes = new PacketDispatcher(runtime);
        var transport = new CultConnection(
                channel,
                routes,
                ignored -> bedrockBridge == null ? null : new PacketOwner(channel.eventLoop(), bedrockBridge));
        CultDecoder.install(transport);
        CultEncoder.install(transport);
        for (var direction : PacketDirection.values()) transport.phase(direction, ConnectionPhase.PLAY);
        transport.resolveOwner();
        var user = new User(profile, transport);
        return user;
    }

    static void setDoubleConfigOverride(String key, double value) {
        DOUBLE_CONFIG_OVERRIDES.put(key, value);
    }

    static void clearDoubleConfigOverride(String key) {
        DOUBLE_CONFIG_OVERRIDES.remove(key);
    }

    /** Default fixtures use the bundled names and client-visible world facts. */
    public static PlatformConnection platformConnection() {
        var platform = Mockito.mock(PlatformConnection.class);
        Mockito.doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(platform)
                .runInModel(Mockito.any(Runnable.class));
        Mockito.when(platform.registryNames()).thenReturn(ModelRegistryNamesState.defaults());
        Mockito.when(platform.initialWorldData()).thenReturn(ClientWorldRegistries.modelDefaults());
        return platform;
    }

    public static List<PacketType<?>> catalog() {
        var world = new ClientWorldRegistries(ClientWorldRegistries.modelDefaults());
        return ClientPacketCodecs.catalog(context -> ModelRegistryNamesState.defaults(), context -> world);
    }

    public static SimItemStack item(String key) {
        return item(key, 1);
    }

    public static SimItemStack item(String key, int count) {
        return ItemUtil.modelItems().stack(key, count);
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
                    case "getPluginManager" -> Mockito.mock(PlatformPluginManager.class);
                    case "getPlatformServer" -> Mockito.mock(PlatformServer.class);
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
        // merged BaseConfigManager (experimental checks are on in the config above; the engine
        // reads the clamped max-transaction-time key, which the config default-answers).
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
