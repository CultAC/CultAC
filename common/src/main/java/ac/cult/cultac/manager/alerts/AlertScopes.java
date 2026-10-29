package ac.cult.cultac.manager.alerts;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.HostContext;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.SettingRecord;
import ac.grim.grimac.api.storage.model.SettingScope;
import ac.grim.grimac.api.storage.query.Page;
import ac.grim.grimac.api.storage.query.Queries;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Each staff member's alert scope: their own choice, persisted as the player setting {@value #SETTING_KEY}, else
 * {@link #DEFAULT}, which is never persisted. Choices are held per native player, from the join that loads them to the
 * quit, in an object every engine generation on the proxy shares.
 */
public final class AlertScopes {
    public static final String SETTING_KEY = "alert-scope";
    public static final AlertScope DEFAULT = AlertScope.SERVER;
    static final String SHARED_KEY = "cultac.alerts.scope";
    /** Held while the persisted choice is being read; counts as no choice. */
    private static final String LOADING = "";

    /** Native player to scope id; JDK types only, since every generation reads it. */
    private final Map<Object, String> chosen;

    private final Supplier<@Nullable DataStore> store;

    @SuppressWarnings("unchecked")
    public AlertScopes(@Nullable HostContext host, Supplier<@Nullable DataStore> store) {
        this.chosen = host == null ? newMap() : (Map<Object, String>) host.shared(SHARED_KEY, AlertScopes::newMap);
        this.store = store;
    }

    private static Map<Object, String> newMap() {
        return Collections.synchronizedMap(new IdentityHashMap<>());
    }

    /**
     * Starts reading {@code player}'s persisted choice without blocking; {@link #DEFAULT} applies until it lands. Runs
     * {@code loaded} once a choice is applied. A choice made meanwhile wins over the one read.
     */
    public void load(PlatformPlayer player, Runnable loaded) {
        DataStore store = this.store.get();
        if (store == null) return;
        Object handle = player.getNative();
        if (chosen.putIfAbsent(handle, LOADING) != null) return;
        UUID uuid = player.getUniqueId();
        store.query(Categories.SETTING, new Queries.GetSetting(SettingScope.PLAYER, uuid.toString(), SETTING_KEY))
                .whenComplete((page, failure) -> {
                    if (failure != null) LogUtil.warn("Could not read the alert scope of " + uuid, failure);
                    AlertScope persisted = failure == null ? decode(page) : null;
                    if (persisted != null && chosen.replace(handle, LOADING, persisted.id())) {
                        loaded.run();
                    } else {
                        chosen.remove(handle, LOADING);
                    }
                });
    }

    /** {@code player}'s own choice; null when they made none or it is still loading. */
    public @Nullable AlertScope chosen(PlatformPlayer player) {
        return AlertScope.parse(chosen.get(player.getNative()));
    }

    /** The choice, else {@link #DEFAULT}. */
    public AlertScope scope(PlatformPlayer player) {
        AlertScope choice = chosen(player);
        return choice != null ? choice : DEFAULT;
    }

    /** Sets {@code player}'s choice and persists it. */
    public void choose(PlatformPlayer player, AlertScope scope) {
        chosen.put(player.getNative(), scope.id());
        DataStore store = this.store.get();
        if (store == null) return;
        String uuid = player.getUniqueId().toString();
        byte[] value = scope.id().getBytes(StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();
        store.submit(
                Categories.SETTING,
                setting -> setting.scope(SettingScope.PLAYER)
                        .scopeKey(uuid)
                        .key(SETTING_KEY)
                        .value(value)
                        .updatedEpochMs(now));
    }

    /** Drops {@code player}'s choice from memory when their connection ends; the persisted one stays. */
    public void forget(PlatformPlayer player) {
        chosen.remove(player.getNative());
    }

    private static @Nullable AlertScope decode(@Nullable Page<SettingRecord> page) {
        if (page == null || page.items().isEmpty()) return null;
        byte[] value = page.items().get(0).value();
        return value == null ? null : AlertScope.parse(new String(value, StandardCharsets.UTF_8));
    }
}
