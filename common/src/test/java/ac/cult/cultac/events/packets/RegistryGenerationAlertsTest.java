package ac.cult.cultac.events.packets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegistryGenerationAlertsTest {
    @Test
    void failureAlertsOncePerWindow() {
        String failure = "test.Failure: " + System.nanoTime();
        long start = 1_000_000L;
        assertTrue(RegistryGenerationAlerts.admit(failure, start));
        assertFalse(RegistryGenerationAlerts.admit(failure, start + 1));
        assertFalse(RegistryGenerationAlerts.admit(failure, start + RegistryGenerationAlerts.WINDOW_MILLIS - 1));
        assertTrue(RegistryGenerationAlerts.admit(failure + " other", start + 1));
        assertTrue(RegistryGenerationAlerts.admit(failure, start + RegistryGenerationAlerts.WINDOW_MILLIS));
    }

    @Test
    void reachingTheCapKeepsLiveSuppression() {
        // Starts past the window of every other test's entries.
        long start = Long.MAX_VALUE / 4;
        String prefix = "test.Capacity " + System.nanoTime() + " ";
        for (int i = 0; i < RegistryGenerationAlerts.MAX_OCCURRENCES; i++)
            assertTrue(RegistryGenerationAlerts.admit(prefix + i, start));
        assertFalse(RegistryGenerationAlerts.admit(prefix + 0, start + 1), "A live occurrence stays suppressed");
        assertFalse(RegistryGenerationAlerts.admit(prefix + "new", start + 1), "A full table admits nothing new");
        long later = start + RegistryGenerationAlerts.WINDOW_MILLIS;
        assertTrue(RegistryGenerationAlerts.admit(prefix + "new", later), "Expired occurrences make room");
        assertTrue(RegistryGenerationAlerts.admit(prefix + 0, later));
    }
}
