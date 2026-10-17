package ac.cult.cultac.companion.api;

import ac.cult.cultac.companion.bridge.CompanionSession;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.alerts.AlertManager;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.api.storage.backend.BackendRegistry;
import ac.grim.grimac.internal.plugin.resolver.GrimExtensionManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * The Grim API on a backend whose checks run on the CultAC proxy. Users exist from {@code PlayerJoinEvent} until
 * {@code PlayerQuitEvent}; a READY bridge session attaches to the user and feeds its mirrored state.
 */
public final class CompanionGrimAPI implements GrimAbstractAPI {
    private final EventBus eventBus;
    private final GrimExtensionManager extensions;
    private final String version;
    private final Executor mainThread;
    private final IntSupplier currentTick;
    private final WarnOnce warnings;
    private final CompanionAlertManager alertManager;
    private final CompanionBackendRegistry backendRegistry;
    private final Map<UUID, CompanionUser> users = new ConcurrentHashMap<>();
    private final Map<String, Function<Object, Object>> functions = new ConcurrentHashMap<>();
    private volatile boolean started;
    private volatile @Nullable Supplier<CompletableFuture<Boolean>> reloader;
    private final CompanionFeatureManager featureManager;
    private volatile CheckPermissions checkPermissions = CheckPermissions.NONE;

    CompanionGrimAPI(
            EventBus eventBus,
            GrimExtensionManager extensions,
            String version,
            Logger logger,
            Executor mainThread,
            IntSupplier currentTick) {
        this.eventBus = eventBus;
        this.extensions = extensions;
        this.version = version;
        this.mainThread = mainThread;
        this.currentTick = currentTick;
        this.warnings = new WarnOnce(logger);
        this.alertManager = new CompanionAlertManager(this::user);
        this.backendRegistry = new CompanionBackendRegistry(warnings);
        this.featureManager = new CompanionFeatureManager(warnings);
    }

    void reloadVia(@Nullable Supplier<CompletableFuture<Boolean>> reloader) {
        this.reloader = reloader;
    }

    CheckPermissions checkPermissions() {
        return checkPermissions;
    }

    void checkPermissions(CheckPermissions permissions) {
        checkPermissions = permissions;
    }

    void markStarted() {
        started = true;
    }

    WarnOnce warnings() {
        return warnings;
    }

    CompanionUser user(UUID player) {
        return users.get(player);
    }

    /** Main thread; returns the player's user, creating it unless a READY session already did. */
    CompanionUser join(Player player) {
        return users.compute(
                player.getUniqueId(),
                (id, existing) -> existing != null && existing.player() == player
                        ? existing
                        : new CompanionUser(player, mainThread, featureManager));
    }

    /** Main thread; returns the removed user, or null when this player had none. */
    @Nullable
    CompanionUser quit(Player player) {
        CompanionUser user = users.get(player.getUniqueId());
        return user != null && user.player() == player && users.remove(player.getUniqueId(), user) ? user : null;
    }

    /** Main thread; attaches the session to the player's user, creating it when the session is READY before join. */
    CompanionUser open(CompanionSession session) {
        CompanionUser user = join(session.player());
        user.attach(session);
        return user;
    }

    CompanionUser userOf(CompanionSession session) {
        CompanionUser user = users.get(session.playerId());
        return user != null && user.session() == session ? user : null;
    }

    void clear() {
        users.clear();
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public GrimUser getGrimUser(Player player) {
        return player == null ? null : user(player.getUniqueId());
    }

    @Override
    public GrimUser getGrimUser(UUID uuid) {
        return uuid == null ? null : user(uuid);
    }

    /** Accepted without effect: alert messages are rendered on the proxy. */
    @Override
    public void registerVariable(String variable, Function<GrimUser, String> replacement) {
        warnVariable(variable);
    }

    /** Accepted without effect: alert messages are rendered on the proxy. */
    @Override
    public void registerVariable(String variable, String replacement) {
        warnVariable(variable);
    }

    private void warnVariable(String variable) {
        warnings.warn(
                "variable",
                "Grim API placeholder " + variable + " was registered on the CultAC proxy companion and has no effect;"
                        + " alert messages are rendered on the proxy");
    }

    @Override
    public String getGrimVersion() {
        return version;
    }

    /** Stored for {@link #getFunction} but never called: the functions' consumers run on the proxy. Null removes. */
    @Override
    public void registerFunction(String key, Function<Object, Object> function) {
        if (function == null) {
            functions.remove(key);
            return;
        }
        functions.put(key, function);
        warnings.warn(
                "function",
                "Grim API function " + key + " was registered on the CultAC proxy companion and has no effect;"
                        + " use a proxy plugin");
    }

    @Override
    public Function<Object, Object> getFunction(String key) {
        return functions.get(key);
    }

    @Override
    public AlertManager getAlertManager() {
        return alertManager;
    }

    @Override
    public ConfigManager getConfigManager() {
        return CompanionConfigManager.INSTANCE;
    }

    @Override
    public boolean hasStarted() {
        return started;
    }

    @Override
    public int getCurrentTick() {
        return currentTick.getAsInt();
    }

    @Override
    public GrimPlugin getGrimPlugin(Object context) {
        return extensions.getPlugin(context);
    }

    @Override
    public BackendRegistry getBackendRegistry() {
        return backendRegistry;
    }

    /** Runs /cult reload on the proxy as this server's console. */
    @Override
    public void reload() {
        reloadAsync();
    }

    /** Runs /cult reload on the proxy as this server's console; completes when the proxy finishes the command. */
    @Override
    public CompletableFuture<Boolean> reloadAsync() {
        Supplier<CompletableFuture<Boolean>> forward = reloader;
        if (forward == null) {
            warnReload();
            return CompletableFuture.completedFuture(false);
        }
        return forward.get();
    }

    /** The proxy reloads its own configuration; {@code config} is ignored. */
    @Override
    public void reload(ConfigManager config) {
        reloadAsync();
    }

    /** The proxy reloads its own configuration; {@code config} is ignored. */
    @Override
    public CompletableFuture<Boolean> reloadAsync(ConfigManager config) {
        return reloadAsync();
    }

    private void warnReload() {
        warnings.warn("reload", "Grim API reload needs the companion's command relay; run /cult reload on the proxy");
    }
}
