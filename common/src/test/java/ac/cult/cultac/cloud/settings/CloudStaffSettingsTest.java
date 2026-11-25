package ac.cult.cultac.cloud.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CloudStaffSettingsTest {

    /** Toggles as the alert manager holds them, recording what the join read applies. */
    private static final class Staff implements CloudStaffSettings.Staff {
        final Map<String, Boolean> toggles = new HashMap<>();
        final Map<String, String> applied = new LinkedHashMap<>();

        Staff(boolean alerts, boolean verbose, boolean brands) {
            toggles.put("alerts", alerts);
            toggles.put("verbose", verbose);
            toggles.put("brands", brands);
        }

        @Override
        public boolean online(PlatformPlayer player) {
            return true;
        }

        @Override
        public Map<String, Boolean> toggles(PlatformPlayer player) {
            return Map.copyOf(toggles);
        }

        @Override
        public void toggle(PlatformPlayer player, String key, boolean on) {
            toggles.put(key, on);
            applied.put(key, String.valueOf(on));
        }

        @Override
        public void adoptScope(PlatformPlayer player, AlertScope scope) {
            applied.put("alert-scope", scope.id());
        }

        @Override
        public void quit(PlatformPlayer player) {}
    }

    @Test
    void aSettingChangedWhileTheJoinReadIsUnderWayStands() {
        UUID id = UUID.randomUUID();
        PlatformPlayer player = mock(PlatformPlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(anyString())).thenReturn(true);
        Map<String, String> cloud = Map.of("alerts", "true", "verbose", "true", "brands", "true", "alert-scope", "global");

        Staff staff = new Staff(true, false, false);
        CompletableFuture<@Nullable Map<String, String>> read = new CompletableFuture<>();
        CloudStaffSettings.join(player, read, staff);
        staff.toggles.put("alerts", false);
        CloudStaffSettings.send(id, Map.of("alerts", "false"));
        CloudStaffSettings.send(id, Map.of("alert-scope", "server"));
        read.complete(cloud);
        assertEquals(Map.of("brands", "true"), staff.applied);
        assertEquals(false, staff.toggles.get("alerts"));

        Staff untouched = new Staff(true, false, false);
        CompletableFuture<@Nullable Map<String, String>> next = new CompletableFuture<>();
        CloudStaffSettings.join(player, next, untouched);
        next.complete(cloud);
        assertEquals(cloud, untouched.applied);
    }

    @Test
    void aStoredToggleAppliesOnlyWithItsPermission() {
        PlatformPlayer player = mock(PlatformPlayer.class);
        when(player.hasPermission("cult.alerts")).thenReturn(true);
        Map<String, String> settings = Map.of("alerts", "false", "brands", "true", "verbose", "yes");

        assertEquals(false, CloudStaffSettings.toggle(player, settings, "alerts", "cult.alerts"));
        assertNull(CloudStaffSettings.toggle(player, settings, "brands", "cult.brand"));
        when(player.hasPermission("cult.verbose")).thenReturn(true);
        assertNull(CloudStaffSettings.toggle(player, settings, "verbose", "cult.verbose"));
    }
}
