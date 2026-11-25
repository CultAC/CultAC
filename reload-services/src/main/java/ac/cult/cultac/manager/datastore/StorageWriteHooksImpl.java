package ac.cult.cultac.manager.datastore;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.VerboseFormat;
import ac.grim.grimac.internal.storage.checks.CheckRegistry;
import ac.grim.grimac.internal.storage.checks.StableKeyMapping;
import ac.grim.grimac.internal.storage.identity.PlayerIdentityService;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Concrete {@link StorageWriteHooks}. Caches display-name → checkId locally so
 * the hot path skips the {@code synchronized intern(...)} call on
 * {@link CheckRegistry}.
 */
public final class StorageWriteHooksImpl implements StorageWriteHooks {

    private final DataStore store;
    private final PlayerIdentityService identityService;
    private final CheckRegistry checkRegistry;
    private final SessionTracker sessionTracker;
    /** display name (lowercased) → checkId. Populated lazily via intern. */
    private final Map<CheckKey, Integer> checkIdCache = new ConcurrentHashMap<>();
    /** display-name-lowercase of checks we've already warned about. Prevents log spam. */
    private final Set<String> missingStableKeyLogged = ConcurrentHashMap.newKeySet();

    public StorageWriteHooksImpl(
            @NotNull DataStore store,
            @NotNull PlayerIdentityService identityService,
            @NotNull CheckRegistry checkRegistry,
            @NotNull SessionTracker sessionTracker) {
        this.store = store;
        this.identityService = identityService;
        this.checkRegistry = checkRegistry;
        this.sessionTracker = sessionTracker;
    }

    @Override
    public void onJoin(@NotNull UUID uuid, @Nullable String name, long now, @NotNull SessionTracker.ClientMeta meta) {
        identityService.observe(uuid, name, now);
        sessionTracker.observeActivity(uuid, now, meta);
    }

    @Override
    public void onQuit(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta) {
        sessionTracker.close(uuid, now, meta);
    }

    @Override
    public void observeBrand(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta) {
        sessionTracker.observeActivity(uuid, now, meta);
    }

    @Override
    public void recordFlag(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        UUID sessionId = sessionTracker.currentSessionId(playerUuid, meta);
        if (sessionId == null) {
            // Two real cases: a check fired on a packet between LOGIN_SUCCESS
            // and PlayerJoinEvent (no session yet), or a test harness called
            // recordFlag directly. Synthesise a session so the violation has
            // somewhere to land; PlayerJoinEvent's onJoin will extend it.
            sessionId = sessionTracker.observeActivity(playerUuid, now, meta);
        }
        final int checkId = resolveCheckId(check, meta.grimVersion());
        final UUID sid = sessionId;
        store.submit(
                Categories.VIOLATION,
                e -> e.sessionId(sid)
                        .playerUuid(playerUuid)
                        .checkId(checkId)
                        .vl(vl)
                        .occurredEpochMs(now)
                        .verbose(verbose)
                        .verboseFormat(VerboseFormat.TEXT));
    }

    @Override
    public void recordFlagData(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            byte @Nullable [] verboseData,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        UUID sessionId = sessionTracker.currentSessionId(playerUuid, meta);
        if (sessionId == null) {
            // See recordFlag(): binary verbose uses the same synthesized-session fallback.
            sessionId = sessionTracker.observeActivity(playerUuid, now, meta);
        }
        final int checkId = resolveCheckId(check, meta.grimVersion());
        final UUID sid = sessionId;
        final byte[] payload = verboseData == null ? null : verboseData.clone();
        store.submit(
                Categories.VIOLATION,
                e -> e.sessionId(sid)
                        .playerUuid(playerUuid)
                        .checkId(checkId)
                        .vl(vl)
                        .occurredEpochMs(now)
                        .verboseData(payload)
                        .verboseFormat(VerboseFormat.STRUCTURED_V1));
    }

    private int resolveCheckId(@NotNull AbstractCheck check, String introducedVersion) {
        String display = check.getCheckName();
        String key = display.toLowerCase(Locale.ROOT);

        String declaredStable = check.getStableKey();
        String stable;
        if (declaredStable != null && !declaredStable.isEmpty()) {
            stable = declaredStable;
        } else {
            // Check hasn't adopted the stable-key contract yet. Fall back to
            // the legacy map, and warn exactly once per display so the
            // missing declaration surfaces without spamming the log.
            if (missingStableKeyLogged.add(key)) {
                Logger.getLogger("cult-datastore")
                        .warning("[cult-history] check " + display
                                + " has no stableKey declared; falling back to StableKeyMapping. "
                                + "Populate the @CheckData.stableKey / CheckInfo.stableKey field.");
            }
            stable = StableKeyMapping.stableKeyFor(display).orElse(StableKeyMapping.legacyFallback(display));
        }
        String description = check.getDescription();
        CheckKey cacheKey = new CheckKey(stable, display, description, introducedVersion);
        Integer cached = checkIdCache.get(cacheKey);
        if (cached != null) return cached;
        int id = checkRegistry.intern(stable, display, description, introducedVersion);
        checkIdCache.put(cacheKey, id);
        return id;
    }

    private record CheckKey(String stable, String display, String description, String version) {}
}
