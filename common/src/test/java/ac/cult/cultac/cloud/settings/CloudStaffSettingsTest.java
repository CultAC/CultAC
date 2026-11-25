package ac.cult.cultac.cloud.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudStaffSettingsTest {

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
