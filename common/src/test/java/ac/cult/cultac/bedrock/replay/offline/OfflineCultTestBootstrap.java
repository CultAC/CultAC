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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.plugin.PluginManager;
import org.mockito.Mockito;

public final class OfflineCultTestBootstrap {
    private static boolean networkConfigured;

    private static boolean installed;
    private static UnsafeValues unsafeValues;
    private static PluginManager pluginManager;
    private static net.minecraft.core.RegistryAccess.Frozen worldRegistries;
    private static final Map<String, Double> DOUBLE_CONFIG_OVERRIDES = new ConcurrentHashMap<>();

    private OfflineCultTestBootstrap() {}

    public static void installConfig() {
        if (installed) {
            initializeProtocolRuntime();
            return;
        }
        SpongeSchematicCompensatedWorldLoader.bootstrapMinecraft();
        initializeProtocolRuntime();
        initializePaperGlobalConfiguration();
        installBukkitServer();
        installPlatformLoader();
        loadVanillaData();
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
                    ac.cult.cultac.network.TestProtocolRuntime.create(
                            ac.cult.cultac.protocol.data.ProtocolData.load(ac.cult.cultac.protocol.ProtocolVersion.of(
                                    net.minecraft.SharedConstants.getProtocolVersion()))),
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

    private static void loadVanillaData() {
        VanillaPackResources vanilla = ServerPacksSource.createVanillaPackSource();
        try (MultiPackResourceManager resources =
                new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla.fullResources()))) {
            List<Registry.PendingTags<?>> pendingTags =
                    TagLoader.loadTagsForExistingRegistries(resources, RegistryLayer.STATIC_ACCESS);
            pendingTags.forEach(Registry.PendingTags::apply);
            // 26.3 binds item components after their provider/transformer registries.
            var base = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var world = net.minecraft.resources.RegistryDataLoader.load(
                            resources,
                            base.listRegistries().toList(),
                            net.minecraft.resources.RegistryDataLoader.WORLD_REGISTRIES,
                            Runnable::run)
                    .join();
            worldRegistries = world;
            var context = net.minecraft.core.HolderLookup.Provider.create(
                    java.util.stream.Stream.concat(base.listRegistries(), world.listRegistries()));
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(context).forEach(pending -> pending.apply());
        }
    }

    public static void initializePaperItemEncoder() throws ReflectiveOperationException {
        installConfig();
        // Paper's sanitizer initializes its enchantment defaults from the running server.
        // Supply the loaded vanilla registries for that initialization; the item encoder
        // and sanitizer themselves still execute their real implementations.
        if (net.minecraft.server.MinecraftServer.getServer() == null) {
            var server = Mockito.mock(net.minecraft.server.MinecraftServer.class);
            Mockito.when(server.registryAccess()).thenReturn(worldRegistries);
            var current = net.minecraft.server.MinecraftServer.class.getDeclaredField("SERVER");
            current.setAccessible(true);
            current.set(null, server);
            try {
                Class.forName("io.papermc.paper.util.sanitizer.ItemComponentSanitizer");
            } finally {
                current.set(null, null);
            }
        } else {
            Class.forName("io.papermc.paper.util.sanitizer.ItemComponentSanitizer");
        }
    }

    /** Dynamic registry IDs in login/respawn use the server encoder's registry. */
    public static ac.cult.cultac.network.PlatformConnection platformConnection() {
        var platform = Mockito.mock(ac.cult.cultac.network.PlatformConnection.class);
        Mockito.doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(platform)
                .runInModel(Mockito.any(Runnable.class));
        Mockito.when(platform.registries())
                .thenReturn(new ac.cult.cultac.utils.minecraft.MinecraftRegistries(
                        () -> net.minecraft.server.MinecraftServer.getServer().registryAccess(),
                        () -> net.minecraft.server.MinecraftServer.getServer().getResourceManager()));
        return platform;
    }

    public static net.minecraft.core.RegistryAccess.Frozen vanillaRegistries() {
        installConfig();
        return worldRegistries;
    }

    public static AutoCloseable withServerRegistries(net.minecraft.core.RegistryAccess.Frozen registries)
            throws ReflectiveOperationException {
        var current = net.minecraft.server.MinecraftServer.class.getDeclaredField("SERVER");
        current.setAccessible(true);
        Object previous = current.get(null);
        var server = Mockito.mock(net.minecraft.server.MinecraftServer.class);
        Mockito.when(server.registryAccess()).thenReturn(registries);
        current.set(null, server);
        return () -> current.set(null, previous);
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

    private static void initializePaperGlobalConfiguration() {
        try {
            Class<?> globalConfigurationClass = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            java.lang.reflect.Method getMethod = globalConfigurationClass.getDeclaredMethod("get");
            Object current = getMethod.invoke(null);
            Object globalConfiguration =
                    current == null ? globalConfigurationClass.getConstructor().newInstance() : current;
            // Paper's Connection static initializer reads Misc.maxJoinsPerTick; its
            // constructor reads PacketLimiter. Supply the same defaults as Paper.
            for (String field : new String[] {"unsupportedSettings", "misc", "packetLimiter"}) {
                java.lang.reflect.Field configField = globalConfigurationClass.getField(field);
                if (configField.get(globalConfiguration) == null) {
                    Object defaults = configField
                            .getType()
                            .getConstructor(globalConfigurationClass)
                            .newInstance(globalConfiguration);
                    configField.set(globalConfiguration, defaults);
                }
            }

            java.lang.reflect.Method setMethod =
                    globalConfigurationClass.getDeclaredMethod("set", globalConfigurationClass);
            setMethod.setAccessible(true);
            setMethod.invoke(null, globalConfiguration);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install offline Paper global config", exception);
        }
    }

    private static void installBukkitServer() {
        if (Bukkit.getServer() != null) {
            return;
        }
        Bukkit.setServer((Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getViewDistance",
                            "getSimulationDistance",
                            "getSpawnRadius",
                            "getMaxWorldSize",
                            "getCurrentTick" -> 10;
                    case "getPort",
                            "getMaxPlayers",
                            "getIdleTimeout",
                            "getPauseWhenEmptyTime",
                            "getMaxChainedNeighborUpdates" -> 0;
                    case "getLogger" -> Logger.getLogger("OfflineBedrockReplay");
                    case "getUnsafe" -> unsafeValues();
                    case "getPluginManager" -> pluginManager();
                    case "createBlockData" -> createBlockData(args);
                    case "getName",
                            "getVersion",
                            "getBukkitVersion",
                            "getMinecraftVersion",
                            "getIp",
                            "getWorldType",
                            "getUpdateFolder",
                            "getResourcePack",
                            "getResourcePackHash",
                            "getResourcePackPrompt",
                            "getShutdownMessage",
                            "getMotd",
                            "getPermissionMessage" -> "";
                    case "getWorlds",
                            "getOnlinePlayers",
                            "matchPlayer",
                            "getInitialEnabledPacks",
                            "getInitialDisabledPacks" -> List.of();
                    case "getWhitelistedPlayers", "getBannedPlayers", "getOperators", "getIPBans" -> Set.of();
                    case "isPrimaryThread" -> true;
                    default -> defaultValue(method.getReturnType());
                }));
    }

    private static UnsafeValues unsafeValues() {
        if (unsafeValues != null) {
            return unsafeValues;
        }
        UnsafeValues unsafeValues = Mockito.mock(UnsafeValues.class);
        OfflineCultTestBootstrap.unsafeValues = unsafeValues;
        return OfflineCultTestBootstrap.unsafeValues;
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

    private static PluginManager pluginManager() {
        if (pluginManager != null) {
            return pluginManager;
        }
        OfflineCultTestBootstrap.pluginManager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class<?>[] {PluginManager.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        return OfflineCultTestBootstrap.pluginManager;
    }

    @SuppressWarnings("unchecked")
    private static BlockData createBlockData(Object[] args) {
        Material material = args != null && args.length > 0 && args[0] instanceof Material value ? value : Material.AIR;
        Identifier id = Identifier.fromNamespaceAndPath(
                material.getKey().getNamespace(), material.getKey().getKey());
        var block = BuiltInRegistries.BLOCK.getValue(id);
        var state = block == null ? Blocks.AIR.defaultBlockState() : block.defaultBlockState();
        BlockData data = CraftBlockData.createData(state);
        if (args != null && args.length > 1 && args[1] instanceof Consumer<?> consumer) {
            ((Consumer<BlockData>) consumer).accept(data);
        }
        return data;
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
