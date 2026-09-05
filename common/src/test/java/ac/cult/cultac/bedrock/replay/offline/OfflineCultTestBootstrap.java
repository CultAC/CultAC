package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.CultAPI;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.manager.PermissionRegistrationManager;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.grim.grimac.api.config.ConfigManager;
import ac.cult.cultac.manager.config.BaseConfigManager;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.mockito.Mockito;

public final class OfflineCultTestBootstrap {
    private static boolean installed;
    private static UnsafeValues unsafeValues;
    private static PluginManager pluginManager;
    private static final Map<String, Double> DOUBLE_CONFIG_OVERRIDES = new ConcurrentHashMap<>();

    private OfflineCultTestBootstrap() {
    }

    public static void installConfig() {
        if (installed) {
            return;
        }
        SpongeSchematicCompensatedWorldLoader.bootstrapMinecraft();
        loadVanillaTags();
        initializePaperGlobalConfiguration();
        installBukkitServer();
        installCultPlugin();
        installPlatformLoader();
        BedrockClientBlockShapeMappings.initialize();
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getIntElse(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Mockito.when(config.getBooleanElse(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        Mockito.when(config.getBooleanElse("experimental-checks", false)).thenReturn(true);
        Mockito.when(config.getDoubleElse(Mockito.anyString(), Mockito.anyDouble()))
                .thenAnswer(invocation -> DOUBLE_CONFIG_OVERRIDES.getOrDefault(
                        invocation.getArgument(0), invocation.getArgument(1)));
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

    static void setDoubleConfigOverride(String key, double value) {
        DOUBLE_CONFIG_OVERRIDES.put(key, value);
    }

    static void clearDoubleConfigOverride(String key) {
        DOUBLE_CONFIG_OVERRIDES.remove(key);
    }

    private static void loadVanillaTags() {
        VanillaPackResources vanilla = ServerPacksSource.createVanillaPackSource();
        try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla))) {
            List<Registry.PendingTags<?>> pendingTags = TagLoader.loadTagsForExistingRegistries(
                    resources,
                    RegistryLayer.STATIC_ACCESS);
            pendingTags.forEach(Registry.PendingTags::apply);
        }
    }

    private static void installCultPlugin() {
        try {
            Field pluginField = CultAPI.class.getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(CultAPI.INSTANCE, plugin());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install offline Cult plugin", exception);
        }
    }


    private static void installPlatformLoader() {
        PermissionRegistrationManager noOpPermissions = (name, defaultValue) -> {
        };
        GrimPlugin grimPlugin = (GrimPlugin) Proxy.newProxyInstance(
                GrimPlugin.class.getClassLoader(),
                new Class<?>[]{GrimPlugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getLogger("OfflineBedrockReplay");
                    case "getDataFolder" -> new File("build/offline-bedrock-replay-plugin");
                    default -> defaultValue(method.getReturnType());
                });
        PlatformLoader loader = (PlatformLoader) Proxy.newProxyInstance(
                PlatformLoader.class.getClassLoader(),
                new Class<?>[]{PlatformLoader.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPermissionManager" -> noOpPermissions;
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
            if (current != null) {
                return;
            }

            Object globalConfiguration = globalConfigurationClass.getConstructor().newInstance();
            Class<?> unsupportedSettingsClass = Class.forName("io.papermc.paper.configuration.GlobalConfiguration$UnsupportedSettings");
            Object unsupportedSettings = unsupportedSettingsClass.getConstructor(globalConfigurationClass).newInstance(globalConfiguration);
            globalConfigurationClass.getField("unsupportedSettings").set(globalConfiguration, unsupportedSettings);

            java.lang.reflect.Method setMethod = globalConfigurationClass.getDeclaredMethod("set", globalConfigurationClass);
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
                new Class[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getViewDistance", "getSimulationDistance", "getSpawnRadius", "getMaxWorldSize", "getCurrentTick" -> 10;
                    case "getPort", "getMaxPlayers", "getIdleTimeout", "getPauseWhenEmptyTime", "getMaxChainedNeighborUpdates" -> 0;
                    case "getLogger" -> Logger.getLogger("OfflineBedrockReplay");
                    case "getUnsafe" -> unsafeValues();
                    case "getPluginManager" -> pluginManager();
                    case "createBlockData" -> createBlockData(args);
                    case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion", "getIp",
                         "getWorldType", "getUpdateFolder", "getResourcePack", "getResourcePackHash",
                         "getResourcePackPrompt", "getShutdownMessage", "getMotd", "getPermissionMessage" -> "";
                    case "getWorlds", "getOnlinePlayers", "matchPlayer", "getInitialEnabledPacks", "getInitialDisabledPacks" -> List.of();
                    case "getWhitelistedPlayers", "getBannedPlayers", "getOperators", "getIPBans" -> Set.of();
                    case "isPrimaryThread" -> true;
                    default -> defaultValue(method.getReturnType());
                }
        ));
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

    private static final class ReplayEmptyItemStack extends ItemStack {
        @Override
        public Material getType() {
            return Material.AIR;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    }

    private static PluginManager pluginManager() {
        if (pluginManager != null) {
            return pluginManager;
        }
        OfflineCultTestBootstrap.pluginManager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        return OfflineCultTestBootstrap.pluginManager;
    }

    @SuppressWarnings("removal")
    private static JavaPlugin plugin() {
        PluginDescriptionFile description = new PluginDescriptionFile(
                "CultACOfflineReplay",
                "offline-bedrock-replay",
                "ac.cult.cultac.CultAC");
        File dataFolder = new File("build/offline-bedrock-replay-plugin");
        dataFolder.mkdirs();
        return new OfflineJavaPlugin(
                new JavaPluginLoader(Bukkit.getServer()),
                description,
                dataFolder,
                new File(dataFolder, "CultACOfflineReplay.jar"));
    }

    @SuppressWarnings("removal")
    public static final class OfflineJavaPlugin extends JavaPlugin {
        private OfflineJavaPlugin(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
            super(loader, description, dataFolder, file);
        }
    }

    @SuppressWarnings("unchecked")
    private static BlockData createBlockData(Object[] args) {
        Material material = args != null && args.length > 0 && args[0] instanceof Material value
                ? value
                : Material.AIR;
        Identifier id = Identifier.fromNamespaceAndPath(
                material.getKey().getNamespace(),
                material.getKey().getKey());
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
