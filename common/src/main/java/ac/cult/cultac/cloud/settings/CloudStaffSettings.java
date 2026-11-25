package ac.cult.cultac.cloud.settings;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.manager.alerts.AlertScopes;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Staff alert settings kept in the cloud while it is configured, so they follow staff from server to server: each
 * toggle and scope choice is sent there, and at join the cloud's values replace the ones this server applied, except
 * those changed while the read was under way. Without the cloud only this server's datastore keeps them.
 */
public final class CloudStaffSettings {
    private static final CompletableFuture<Void> SENT = CompletableFuture.completedFuture(null);
    private static final String[] TOGGLES = {
        PlayerToggleStore.KEY_ALERTS, PlayerToggleStore.KEY_VERBOSE, PlayerToggleStore.KEY_BRANDS
    };
    /** Each player's last write; a player's writes reach the cloud in the order made. */
    private static final Map<UUID, CompletableFuture<Void>> WRITES = new ConcurrentHashMap<>();
    /** Each player's join read under way. */
    private static final Map<UUID, Reading> READING = new ConcurrentHashMap<>();

    private CloudStaffSettings() {}

    /** A join read: the toggles as the join left them, and the keys sent since it started. */
    private record Reading(Map<String, Boolean> toggles, Set<String> changed) {}

    /** The alert manager as the join read sees and changes it. */
    interface Staff {
        boolean online(PlatformPlayer player);

        /** The player's toggles by {@link PlayerToggleStore} key. */
        Map<String, Boolean> toggles(PlatformPlayer player);

        void toggle(PlatformPlayer player, String key, boolean on);

        void adoptScope(PlatformPlayer player, AlertScope scope);

        void quit(PlatformPlayer player);
    }

    /** Sends {@code changes} to {@code player}'s settings in the cloud, after their earlier changes. */
    public static void send(UUID player, Map<String, String> changes) {
        Reading reading = READING.get(player);
        if (reading != null) reading.changed().addAll(changes.keySet());
        StaffSettingsClient client = StaffSettingsClient.configured();
        if (client == null) return;
        CompletableFuture<Void> write = WRITES.compute(player, (id, last) -> (last == null ? SENT : last)
                .thenCompose(ignored -> write(client, player, changes)));
        write.whenComplete((ignored, error) -> WRITES.remove(player, write));
    }

    /** One write, completing normally whatever happens to it, so the player's later writes still run. */
    private static CompletableFuture<Void> write(StaffSettingsClient client, UUID player, Map<String, String> changes) {
        CompletableFuture<Boolean> sent;
        try {
            sent = client.write(player, changes);
        } catch (RuntimeException failure) {
            sent = CompletableFuture.failedFuture(failure);
        }
        return sent.handle((ok, failure) -> {
            if (failure != null)
                LogUtil.warn("Could not save the staff settings of " + player + " to the cloud", failure);
            else if (!Boolean.TRUE.equals(ok))
                LogUtil.warn("Could not save the staff settings of " + player + " to the cloud");
            return null;
        });
    }

    /** Reads {@code player}'s settings from the cloud and applies them while the player is here. */
    public static void join(PlatformPlayer player) {
        StaffSettingsClient client = StaffSettingsClient.configured();
        if (client == null) return;
        join(player, client.read(player.getUniqueId()), LIVE);
    }

    static void join(
            PlatformPlayer player, CompletableFuture<@Nullable Map<String, String>> read, Staff staff) {
        UUID id = player.getUniqueId();
        Reading reading = new Reading(Map.copyOf(staff.toggles(player)), ConcurrentHashMap.newKeySet());
        READING.put(id, reading);
        read.whenComplete((settings, failure) -> {
            READING.remove(id, reading);
            if (failure != null) {
                LogUtil.warn("Could not read the staff settings of " + id + " from the cloud", failure);
                return;
            }
            if (settings == null || settings.isEmpty() || !staff.online(player)) return;
            apply(player, unchanged(settings, reading, staff.toggles(player)), staff);
            if (!staff.online(player)) staff.quit(player);
        });
    }

    /**
     * {@code settings} without the keys changed since {@code reading} started: sent, or a toggle now differing. Alerts
     * and verbose turn each other on and off, so a change to either keeps both.
     */
    private static Map<String, String> unchanged(Map<String, String> settings, Reading reading, Map<String, Boolean> now) {
        Set<String> changed = new HashSet<>(reading.changed());
        for (String key : TOGGLES) {
            if (!Objects.equals(reading.toggles().get(key), now.get(key))) changed.add(key);
        }
        if (changed.contains(PlayerToggleStore.KEY_ALERTS) || changed.contains(PlayerToggleStore.KEY_VERBOSE)) {
            changed.add(PlayerToggleStore.KEY_ALERTS);
            changed.add(PlayerToggleStore.KEY_VERBOSE);
        }
        Map<String, String> kept = new HashMap<>(settings);
        kept.keySet().removeAll(changed);
        return kept;
    }

    private static void apply(PlatformPlayer player, Map<String, String> settings, Staff staff) {
        Boolean on = toggle(player, settings, PlayerToggleStore.KEY_ALERTS, "cult.alerts");
        if (on != null) staff.toggle(player, PlayerToggleStore.KEY_ALERTS, on);
        on = toggle(player, settings, PlayerToggleStore.KEY_VERBOSE, "cult.verbose");
        if (on != null) staff.toggle(player, PlayerToggleStore.KEY_VERBOSE, on);
        on = toggle(player, settings, PlayerToggleStore.KEY_BRANDS, "cult.brand");
        if (on != null) staff.toggle(player, PlayerToggleStore.KEY_BRANDS, on);
        AlertScope scope = AlertScope.parse(settings.get(AlertScopes.SETTING_KEY));
        if (scope != null) staff.adoptScope(player, scope);
    }

    /** The toggle {@code settings} holds under {@code key}; null without one or without {@code permission}. */
    static @Nullable Boolean toggle(PlatformPlayer player, Map<String, String> settings, String key, String permission) {
        String value = settings.get(key);
        if (!"true".equals(value) && !"false".equals(value)) return null;
        return player.hasPermission(permission) ? Boolean.valueOf(value) : null;
    }

    private static final Staff LIVE = new Staff() {
        @Override
        public boolean online(PlatformPlayer player) {
            PlatformPlayer now = CultAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(player.getUniqueId());
            return now != null && now.getNative() == player.getNative();
        }

        @Override
        public Map<String, Boolean> toggles(PlatformPlayer player) {
            AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
            return Map.of(
                    PlayerToggleStore.KEY_ALERTS, alerts.hasAlertsEnabled(player),
                    PlayerToggleStore.KEY_VERBOSE, alerts.hasVerboseEnabled(player),
                    PlayerToggleStore.KEY_BRANDS, alerts.hasBrandsEnabled(player));
        }

        @Override
        public void toggle(PlatformPlayer player, String key, boolean on) {
            AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
            switch (key) {
                case PlayerToggleStore.KEY_ALERTS -> alerts.setAlertsEnabled(player, on, true);
                case PlayerToggleStore.KEY_VERBOSE -> alerts.setVerboseEnabled(player, on, true);
                case PlayerToggleStore.KEY_BRANDS -> alerts.setBrandsEnabled(player, on, true);
                default -> {}
            }
        }

        @Override
        public void adoptScope(PlatformPlayer player, AlertScope scope) {
            CultAPI.INSTANCE.getAlertManager().adoptScope(player, scope);
        }

        @Override
        public void quit(PlatformPlayer player) {
            CultAPI.INSTANCE.getAlertManager().handlePlayerQuit(player);
        }
    };
}
