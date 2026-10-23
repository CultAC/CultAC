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
import java.util.UUID;
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

    // TODO(cultLoaderVersion>5): every host then has connection holds. Drop this check
    // and the fallbacks below, and call the two-argument methods directly.
    /**
     * The installed host may predate connection holds: {@link PlayerToggleStore} is
     * loaded by the host, so this runtime sees the host's version of it.
     */
    private static final boolean HOST_HOLDS_PER_CONNECTION = holdsPerConnection(PlayerToggleStore.class);

    static boolean holdsPerConnection(Class<?> store) {
        try {
            store.getMethod("prefetch", UUID.class, UUID.class);
            store.getMethod("evict", UUID.class, UUID.class);
            return true;
        } catch (NoSuchMethodException older) {
            return false;
        }
    }

    /** Holds the player's toggle slot for this connection. */
    public void holdToggles(@NotNull UUID uuid, @NotNull UUID connectionId) {
        if (HOST_HOLDS_PER_CONNECTION) playerToggleStore().prefetch(uuid, connectionId);
        else playerToggleStore().prefetch(uuid);
    }

    /** Releases this connection's hold on the player's toggle slot. */
    public void releaseToggles(@NotNull UUID uuid, @NotNull UUID connectionId) {
        if (HOST_HOLDS_PER_CONNECTION) playerToggleStore().evict(uuid, connectionId);
        else playerToggleStore().evict(uuid);
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
