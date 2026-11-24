package ac.cult.cultac.cloud.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CloudStaffSettingsTest {

    @Test
    void eachUserToggleIsKeptHereAndSent() {
        PlayerToggleStore local = mock(PlayerToggleStore.class);
        List<Map<String, String>> sent = new CopyOnWriteArrayList<>();
        UUID uuid = UUID.randomUUID();
        PlayerToggleStore toggles = CloudStaffSettings.sending(local, (player, changes) -> {
            assertEquals(uuid, player);
            sent.add(changes);
        });

        toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_ALERTS, false);
        toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_VERBOSE, true);

        verify(local).applyUserToggle(uuid, PlayerToggleStore.KEY_ALERTS, false);
        verify(local).applyUserToggle(uuid, PlayerToggleStore.KEY_VERBOSE, true);
        assertEquals(List.of(Map.of("alerts", "false"), Map.of("verbose", "true")), sent);
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
