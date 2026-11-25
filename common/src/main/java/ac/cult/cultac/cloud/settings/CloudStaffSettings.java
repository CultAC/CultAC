package ac.cult.cultac.cloud.settings;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.manager.alerts.AlertScopes;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Staff alert settings kept in the cloud while it is configured, so they follow staff from server to server: each
 * toggle and scope choice is sent there, and at join the cloud's values replace the ones this server applied. Without
 * the cloud only this server's datastore keeps them.
 */
public final class CloudStaffSettings {
    private static final CompletableFuture<Void> SENT = CompletableFuture.completedFuture(null);
    /** Each player's last write; a player's writes reach the cloud in the order made. */
    private static final Map<UUID, CompletableFuture<Void>> WRITES = new ConcurrentHashMap<>();

    private CloudStaffSettings() {}

    /** Sends {@code changes} to {@code player}'s settings in the cloud, after their earlier changes. */
    public static void send(UUID player, Map<String, String> changes) {
        StaffSettingsClient client = StaffSettingsClient.configured();
        if (client == null) return;
        CompletableFuture<Void> write = WRITES.compute(player, (id, last) -> (last == null ? SENT : last)
                .thenCompose(ignored -> client.write(player, changes))
                .thenAccept(sent -> {
                    if (!sent) LogUtil.warn("Could not save the staff settings of " + player + " to the cloud");
                }));
        write.whenComplete((ignored, error) -> WRITES.remove(player, write));
    }

    /** Reads {@code player}'s settings from the cloud and applies them while the player is here. */
    public static void join(PlatformPlayer player) {
        StaffSettingsClient client = StaffSettingsClient.configured();
        if (client == null) return;
        client.read(player.getUniqueId()).thenAccept(settings -> {
            if (settings == null || settings.isEmpty() || !online(player)) return;
            apply(player, settings);
            if (!online(player)) CultAPI.INSTANCE.getAlertManager().handlePlayerQuit(player);
        });
    }

    private static void apply(PlatformPlayer player, Map<String, String> settings) {
        AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
        Boolean on = toggle(player, settings, PlayerToggleStore.KEY_ALERTS, "cult.alerts");
        if (on != null) alerts.setAlertsEnabled(player, on, true);
        on = toggle(player, settings, PlayerToggleStore.KEY_VERBOSE, "cult.verbose");
        if (on != null) alerts.setVerboseEnabled(player, on, true);
        on = toggle(player, settings, PlayerToggleStore.KEY_BRANDS, "cult.brand");
        if (on != null) alerts.setBrandsEnabled(player, on, true);
        AlertScope scope = AlertScope.parse(settings.get(AlertScopes.SETTING_KEY));
        if (scope != null) alerts.adoptScope(player, scope);
    }

    /** The toggle {@code settings} holds under {@code key}; null without one or without {@code permission}. */
    static @Nullable Boolean toggle(PlatformPlayer player, Map<String, String> settings, String key, String permission) {
        String value = settings.get(key);
        if (!"true".equals(value) && !"false".equals(value)) return null;
        return player.hasPermission(permission) ? Boolean.valueOf(value) : null;
    }

    private static boolean online(PlatformPlayer player) {
        PlatformPlayer now = CultAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(player.getUniqueId());
        return now != null && now.getNative() == player.getNative();
    }
}
