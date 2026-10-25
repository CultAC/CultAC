package ac.cult.cultac.manager.datastore;

import ac.grim.grimac.api.storage.model.SettingScope;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistence facade for the per-player toggle state behind {@code /cult alerts},
 * {@code /cult verbose}, {@code /cult brands}. Per-online-player cache; the DB
 * is the source of truth across restarts.
 *
 * <p>Datastore-disabled or init-failed paths get {@link #NOOP}, which
 * implements every method as a no-op so callers don't null-check.
 */
public interface PlayerToggleStore {

    /**
     * Keys persisted under {@link SettingScope#PLAYER}, keyed by
     * {@link UUID#toString()} as the scope key. Stable strings — don't rename
     * without a migration.
     */
    String KEY_ALERTS = "alerts";

    String KEY_VERBOSE = "verbose";
    String KEY_BRANDS = "brands";

    /**
     * Kick off async reads for {@code alerts}, {@code verbose}, {@code brands}
     * keyed by this player's UUID. Returns immediately; results land in the
     * slot via the CompletionStage callback. Safe to call from any thread.
     * The connection holds the slot until it {@linkplain #evict evicts} it.
     */
    void prefetch(@NotNull UUID uuid, @NotNull UUID connectionId);

    /**
     * Read the slot's current best-known value. Returns {@code null} if no
     * source has won the slot yet (prefetch in flight + no permission-default
     * applied).
     */
    @Nullable
    Boolean current(@NotNull UUID uuid, @NotNull String key);

    /**
     * Apply a permission-default value to the slot. No-op if anything else has
     * already settled the slot. Persists the chosen value when applied (one-shot
     * at login, no risk of spam) so subsequent logins see this as the persisted
     * choice — turning the permission into a one-time onboarding policy rather
     * than a recurring override.
     */
    void applyPermissionDefault(@NotNull UUID uuid, @NotNull String key, boolean value);

    /**
     * Apply a user-driven toggle. Stomps any prior source unconditionally; the
     * write goes through the coalesced flush. Multiple toggles within the
     * debounce window collapse into one DB write of the final value.
     */
    void applyUserToggle(@NotNull UUID uuid, @NotNull String key, boolean value);

    /**
     * Release this connection's hold on the slot. Two connections may present
     * the same UUID at once, so the slot is dropped only when no connection
     * holds it; then the pending flush is cancelled and any deferred persists
     * run synchronously so a staff member who quits immediately after toggling
     * still has their last choice written.
     */
    void evict(@NotNull UUID uuid, @NotNull UUID connectionId);

    /** Called from {@code DataStoreLifecycle.stop()}. Idempotent. */
    void shutdown();

    /**
     * No-op store returned by {@code DataStoreLifecycle.playerToggleStore()}
     * when the v1 datastore is disabled or its init failed.
     */
    PlayerToggleStore NOOP = new PlayerToggleStore() {
        @Override
        public void prefetch(@NotNull UUID uuid, @NotNull UUID connectionId) {}

        @Override
        public @Nullable Boolean current(@NotNull UUID uuid, @NotNull String key) {
            return null;
        }

        @Override
        public void applyPermissionDefault(@NotNull UUID uuid, @NotNull String key, boolean value) {}

        @Override
        public void applyUserToggle(@NotNull UUID uuid, @NotNull String key, boolean value) {}

        @Override
        public void evict(@NotNull UUID uuid, @NotNull UUID connectionId) {}

        @Override
        public void shutdown() {}
    };
}
