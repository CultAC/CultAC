package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class AlertExclusionTest {
    @Test
    void verboseRecipientIsExcludedFromAlertWhateverItsWrapper() {
        AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
        UUID uuid = UUID.randomUUID();
        Object staffer = new Object();
        PlatformPlayer verboseWrapper = player(uuid, staffer);
        PlatformPlayer alertWrapper = player(uuid, staffer);
        boolean consoleAlerts = alerts.hasConsoleAlertsEnabled();
        boolean consoleVerbose = alerts.hasConsoleVerboseEnabled();
        try {
            alerts.setConsoleAlertsEnabled(false, true);
            alerts.setConsoleVerboseEnabled(false, true);
            alerts.setVerboseEnabled(verboseWrapper, true, true);
            alerts.setAlertsEnabled(alertWrapper, true, true);

            Set<PlatformPlayer> verboseRecipients = alerts.sendVerbose(Component.text("verbose"), null);
            alerts.sendAlert(Component.text("alert"), verboseRecipients);

            assertEquals(1, messages(verboseWrapper) + messages(alertWrapper));
        } finally {
            alerts.handlePlayerQuit(verboseWrapper);
            alerts.handlePlayerQuit(alertWrapper);
            alerts.setConsoleAlertsEnabled(consoleAlerts, true);
            alerts.setConsoleVerboseEnabled(consoleVerbose, true);
        }
    }

    private static long messages(PlatformPlayer player) {
        return mockingDetails(player).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .count();
    }

    private static PlatformPlayer player(UUID uuid, Object nativePlayer) {
        PlatformPlayer player = mock(PlatformPlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getNative()).thenReturn(nativePlayer);
        return player;
    }
}
