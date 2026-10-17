package ac.cult.cultac.utils.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogThrottleTest {
    @Test
    void clipReplacesControlFormatAndSeparatorCharacters() {
        assertEquals("a?[31mb?c?d", LogThrottle.clip("a\u001b[31mb\nc\u202ed"));
        assertEquals("a?b?c", LogThrottle.clip("a\u2028b\u2029c"));
    }

    @Test
    void clipKeepsShortTextAndCutsLongTextTo200Characters() {
        assertEquals("short", LogThrottle.clip("short"));
        assertEquals("null", LogThrottle.clip(null));
        String clipped = LogThrottle.clip("z".repeat(10_000));
        assertEquals(LogThrottle.MAX_CLIPPED, clipped.length());
        assertTrue(clipped.endsWith("\u2026"));
    }

    @Test
    void aWarningWithoutAFailureIsAdmittedOncePerInterval() {
        long[] now = {0};
        LogThrottle throttle = new LogThrottle(10, () -> now[0]);
        throttle.warn("refused", "refused: reason", null);
        assertEquals(-1, throttle.admit("refused"));
        now[0] = 20;
        assertEquals(1, throttle.admit("refused"));
    }

    @Test
    void clipNeverSplitsASurrogatePair() {
        String text = "a".repeat(LogThrottle.MAX_CLIPPED - 2) + "\uD83D\uDE00" + "tail";
        String clipped = LogThrottle.clip(text);
        assertTrue(clipped.length() <= LogThrottle.MAX_CLIPPED);
        assertTrue(!Character.isHighSurrogate(clipped.charAt(clipped.length() - 2)));
    }
}
