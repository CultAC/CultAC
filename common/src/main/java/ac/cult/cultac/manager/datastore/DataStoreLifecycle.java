package ac.cult.cultac.manager.datastore;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.manager.init.stop.StoppableInitable;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.backend.Backend;
import ac.grim.grimac.api.storage.backend.BackendRegistry;
import ac.grim.grimac.api.storage.config.DataStoreConfig;
import ac.grim.grimac.api.storage.history.HistoryService;
import ac.grim.grimac.api.storage.identity.NameResolver;
import ac.grim.grimac.api.storage.submit.ViolationSink;
import ac.grim.grimac.internal.storage.backend.sqlite.SqliteBackend;
import ac.grim.grimac.internal.storage.checks.CheckRegistry;
import ac.grim.grimac.internal.storage.verbose.VerboseRegistry;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Generation-local adapter; database ownership and workers live in the bootstrap. */
public final class DataStoreLifecycle implements StartableInitable, StoppableInitable {
    private final SharedDataStoreLifecycle shared;

    public DataStoreLifecycle(GrimPlugin plugin, BackendRegistry registry) {
        var host = RuntimeEnvironment.host();
        shared = host == null
                ? new SharedDataStoreLifecycle(plugin.getDataFolder().toPath(), registry)
                : (SharedDataStoreLifecycle)
                        host.shared("datastore", () -> new SharedDataStoreLifecycle(host.directory(), registry));
    }

    public void start() {
        initialize(false);
    }

    public void stop() {
        if (RuntimeEnvironment.host() == null) shared.close();
    }

    public void reload() {
        if (RuntimeEnvironment.host() == null || RuntimeEnvironment.host().current()) initialize(true);
    }

    private void initialize(boolean reload) {
        VerboseCodecs.ensureRegistered();
        shared.initialize(
                CultAPI.INSTANCE.getConfigManager().getConfig(),
                CultAPI.INSTANCE.getExternalAPI().getGrimVersion(),
                CultAPI.INSTANCE.getPlatformServer().getPlatformImplementationString(),
                () -> {
                    if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
                        LogUtil.warn("Observe-only keeps the server running without storage");
                        return;
                    }
                    CultAPI.INSTANCE
                            .getScheduler()
                            .getGlobalRegionScheduler()
                            .run(
                                    CultAPI.INSTANCE.getGrimPlugin(),
                                    () -> CultAPI.INSTANCE
                                            .getPlatformServer()
                                            .dispatchCommand(
                                                    CultAPI.INSTANCE
                                                            .getPlatformServer()
                                                            .getConsoleSender(),
                                                    "stop"));
                },
                ClientVersionResolver::legacyStringToPvn,
                reload);
    }

    public boolean isEnabled() {
        return shared.isEnabled();
    }

    public boolean isLoaded() {
        return shared.isLoaded();
    }

    public @Nullable DataStore dataStore() {
        return shared.dataStore();
    }

    public @Nullable HistoryService historyService() {
        return shared.historyService();
    }

    public @Nullable NameResolver nameResolver() {
        return shared.nameResolver();
    }

    public @Nullable ViolationSink violationSink() {
        return shared.violationSink();
    }

    public @Nullable DataStoreConfig config() {
        return shared.config();
    }

    public @Nullable VerboseRegistry verboseRegistry() {
        return shared.verboseRegistry();
    }

    public @NotNull LiveWriteHooks liveWriteHooks() {
        return new LiveWriteHooksImpl(shared::liveWriteHooks);
    }

    public @NotNull SessionTracker sessionTracker() {
        return shared.sessionTracker();
    }

    public @NotNull PlayerToggleStore playerToggleStore() {
        return shared.playerToggleStore();
    }

    @ApiStatus.Internal
    public @Nullable SqliteBackend sqliteBackendForCommands() {
        return shared.sqliteBackendForCommands();
    }

    @ApiStatus.Internal
    public @Nullable CheckRegistry checkRegistryForCommands() {
        return shared.checkRegistryForCommands();
    }

    @ApiStatus.Internal
    public @NotNull Map<String, Backend> allBackendsForCommands() {
        return shared.allBackendsForCommands();
    }
}
