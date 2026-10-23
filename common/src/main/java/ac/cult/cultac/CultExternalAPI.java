package ac.cult.cultac;

import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.manager.config.ConfigManagerFileImpl;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.AlertDetails;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.common.ConfigReloadObserver;
import ac.cult.cultac.utils.common.PropertiesUtil;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.alerts.AlertManager;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.GrimReloadEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.api.storage.backend.BackendRegistry;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CultExternalAPI implements GrimAbstractAPI, ConfigReloadObserver, StartableInitable {

    // Holder class — CultExternalAPI is constructed inside CultAPI's ctor,
    // so a plain static-final would see a null CultAPI.INSTANCE. Holder
    // init runs on first fire, after CultAPI is fully built.
    private static final class Channels {
        static final GrimReloadEvent.Channel RELOAD =
                CultAPI.INSTANCE.getEventBus().get(GrimReloadEvent.class);
    }

    private final CultAPI api;

    @Getter
    private final Map<String, Function<GrimUser, String>> variableReplacements = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, String> staticReplacements = new ConcurrentHashMap<>();

    private final Map<String, Function<Object, Object>> functions = new ConcurrentHashMap<>();
    private final ConfigManagerFileImpl configManagerFile = new ConfigManagerFileImpl();
    private final String grimVersion;
    private ConfigManager configManager = null;
    private boolean started = false;

    public CultExternalAPI(CultAPI api) {
        this.api = api;
        this.grimVersion = resolveCultVersion(api);
    }

    @Override
    public @NotNull EventBus getEventBus() {
        return api.getEventBus();
    }

    @Deprecated
    @Override
    public @Nullable GrimUser getGrimUser(Player player) {
        return getGrimUser(player.getUniqueId());
    }

    @Override
    public @Nullable GrimUser getGrimUser(UUID uuid) {
        return api.getPlayerDataManager().getPlayer(uuid);
    }

    @Override
    public void registerVariable(String string, Function<GrimUser, String> replacement) {
        if (replacement == null) {
            variableReplacements.remove(string);
        } else {
            variableReplacements.put(string, replacement);
        }
    }

    @Override
    public void registerVariable(String variable, String replacement) {
        if (replacement == null) {
            staticReplacements.remove(variable);
        } else {
            staticReplacements.put(variable, replacement);
        }
    }

    @Override
    public String getGrimVersion() {
        return grimVersion;
    }

    private static String resolveCultVersion(CultAPI api) {
        if (RuntimeEnvironment.host() != null) return RuntimeEnvironment.host().build();
        try {
            Properties properties = PropertiesUtil.readProperties(CultExternalAPI.class, "cultac.properties");
            String buildVersion = properties.getProperty("build.version");
            if (buildVersion != null && !buildVersion.isBlank() && !buildVersion.startsWith("${")) {
                return buildVersion;
            }
        } catch (RuntimeException ignored) {
        }

        try {
            return api.getGrimPlugin().getDescription().getVersion();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public void registerFunction(String key, Function<Object, Object> function) {
        if (function == null) {
            functions.remove(key);
        } else {
            functions.put(key, function);
        }
    }

    @Override
    public Function<Object, Object> getFunction(String key) {
        return functions.get(key);
    }

    @Override
    public AlertManager getAlertManager() {
        return CultAPI.INSTANCE.getAlertManager();
    }

    @Override
    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public boolean hasStarted() {
        return started;
    }

    @Override
    public int getCurrentTick() {
        return CultAPI.INSTANCE.getTickManager().currentTick;
    }

    @Override
    public @NotNull GrimPlugin getGrimPlugin(@NotNull Object o) {
        return this.api.getExtensionManager().getPlugin(o);
    }

    @Override
    public @NotNull BackendRegistry getBackendRegistry() {
        return api.getBackendRegistry();
    }

    // on load, load the config & register the service
    public void load() {
        if (!successfulReload(configManagerFile) && RuntimeEnvironment.host() != null)
            throw new IllegalStateException("Runtime configuration rejected");
        api.getLoader().registerAPIService();
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void reload(ConfigManager config) {
        if (config.isLoadedAsync() && started) {
            CultAPI.INSTANCE
                    .getScheduler()
                    .getAsyncScheduler()
                    .runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> successfulReload(config));
        } else {
            successfulReload(config);
        }
    }

    @Override
    public CompletableFuture<Boolean> reloadAsync(ConfigManager config) {
        if (config.isLoadedAsync() && started) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            CultAPI.INSTANCE
                    .getScheduler()
                    .getAsyncScheduler()
                    .runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> future.complete(successfulReload(config)));
            return future;
        }
        return CompletableFuture.completedFuture(successfulReload(config));
    }

    private boolean successfulReload(ConfigManager config) {
        return successfulReload(config, true);
    }

    public boolean applyPrepared(ConfigManager config) {
        return successfulReload(config, false);
    }

    private boolean successfulReload(ConfigManager config, boolean read) {
        try {
            if (read) config.reload();
            CultAPI.INSTANCE.getConfigManager().load(config);
            onReload(config);
            if (started) CloudModule.configReloaded();
            if (started && RuntimeEnvironment.host() == null)
                CultAPI.INSTANCE
                        .getScheduler()
                        .getAsyncScheduler()
                        .runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> Channels.RELOAD.fire(true));
            return true;
        } catch (Exception e) {
            LogUtil.error("Failed to reload config", e);
        }
        if (started && RuntimeEnvironment.host() == null)
            CultAPI.INSTANCE
                    .getScheduler()
                    .getAsyncScheduler()
                    .runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> Channels.RELOAD.fire(false));
        return false;
    }

    @Override
    public void onReload(ConfigManager newConfig) {
        if (newConfig == null) {
            LogUtil.warn("ConfigManager not set. Using default config file manager.");
            configManager = configManagerFile;
        } else {
            configManager = newConfig;
        }
        updateVariables();
        CultAPI.INSTANCE.getAlertManager().reload(configManager);
        CultAPI.INSTANCE.getDiscordManager().reload();
        CultAPI.INSTANCE.getSpectateManager().reload();
        // First-load guard: load() calls reload() before start() runs, so this fires once with started=false before the
        // datastore exists. Subsequent /cult reload calls see started=true and proceed (including disabled→enabled
        // flips — DataStoreLifecycle.reload() re-evaluates builder.enabled() each time).
        if (!started) return;
        // Hot-reload picks up backend swaps + routing + connection-pool edits without a server restart. Drains
        // in-flight writes for shutdown-drain-timeout-ms then drops; brief mid-reload unavailability is the tradeoff.
        if (CultAPI.INSTANCE.getDataStoreLifecycle() != null) {
            CultAPI.INSTANCE.getDataStoreLifecycle().reload();
        }
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            player.runSafely(() -> player.reload(configManager));
        }
    }

    private void updateVariables() {
        variableReplacements.putIfAbsent("%player%", GrimUser::getName);
        variableReplacements.putIfAbsent("%uuid%", user -> user.getUniqueId().toString());
        variableReplacements.putIfAbsent("%ping%", user -> user.getTransactionPing() + "");
        variableReplacements.putIfAbsent("%brand%", GrimUser::getBrand);
        variableReplacements.putIfAbsent(
                "%h_sensitivity%", user -> ((int) Math.round(user.getHorizontalSensitivity() * 200)) + "");
        variableReplacements.putIfAbsent(
                "%v_sensitivity%", user -> ((int) Math.round(user.getVerticalSensitivity() * 200)) + "");
        variableReplacements.putIfAbsent("%fast_math%", user -> !user.isVanillaMath() + "");
        variableReplacements.putIfAbsent(
                "%tps%",
                user -> String.format(
                        "%.2f", CultAPI.INSTANCE.getPlatformServer().getTPS(user.getUniqueId())));
        variableReplacements.putIfAbsent("%version%", GrimUser::getVersionName);
        variableReplacements.putIfAbsent(
                "%server%", user -> user instanceof CultPlayer player ? AlertDetails.server(player) : "unknown");
        variableReplacements.putIfAbsent(
                "%world%", user -> user instanceof CultPlayer player ? AlertDetails.world(player) : "unknown");
        variableReplacements.putIfAbsent(
                "%world_type%", user -> user instanceof CultPlayer player ? AlertDetails.worldType(player) : "unknown");
        variableReplacements.putIfAbsent(
                "%gamemode%", user -> user instanceof CultPlayer player ? AlertDetails.gamemode(player) : "unknown");
        staticReplacements.put(
                "%prefix%",
                MessageUtil.translateAlternateColorCodes(
                        '&', CultAPI.INSTANCE.getConfigManager().getPrefix()));
        staticReplacements.putIfAbsent("%cult_version%", getGrimVersion());
    }
}
