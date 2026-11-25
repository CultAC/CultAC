package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.wire.IntakeWire;
import java.util.List;
import org.junit.jupiter.api.Test;

class TickFlagsTest {
    @Test
    void aTickKeepsAtMostTheCap() {
        TickFlags flags = new TickFlags();
        assertTrue(flags.drain().isEmpty());
        for (int i = 0; i < IntakeWire.MAX_FLAGS + 5; i++) flags.add("Reach", i);
        List<IntakeWire.Flag> drained = flags.drain();
        assertEquals(IntakeWire.MAX_FLAGS, drained.size());
        assertEquals(IntakeWire.Flag.of("Reach", 0), drained.get(0));
        assertTrue(flags.drain().isEmpty());
    }

    @Test
    void atMostFourFlagsOfATickCarryAVerbose() {
        TickFlags flags = new TickFlags();
        for (int i = 0; i < IntakeWire.MAX_VERBOSE_FLAGS; i++) {
            assertTrue(flags.verboseRoom());
            flags.add("Timer", i, "v" + i);
        }
        assertFalse(flags.verboseRoom());
        flags.add("Timer", 9, "late");
        List<IntakeWire.Flag> drained = flags.drain();
        assertEquals("v0", drained.get(0).verbose());
        assertNull(drained.get(IntakeWire.MAX_VERBOSE_FLAGS).verbose());
        assertTrue(flags.verboseRoom());
    }

    @Test
    void aTickKeepsTheSetbacksItsCallersNamedUpToTheCap() {
        TickFlags flags = new TickFlags();
        flags.add("BadPacketsH", 1);
        flags.setback(IntakeWire.SETBACK_VIOLATION, null, null);
        flags.setback(IntakeWire.SETBACK_VIOLATION, null, "Simulation");
        flags.setback(IntakeWire.SETBACK_RESYNC, "places", null);
        for (int i = 0; i < IntakeWire.MAX_SETBACKS; i++) flags.setback(IntakeWire.SETBACK_RESEND, "r", null);
        List<IntakeWire.Setback> setbacks = flags.drainSetbacks();
        assertEquals(IntakeWire.MAX_SETBACKS, setbacks.size());
        assertNull(setbacks.get(0).check());
        assertEquals("Simulation", setbacks.get(1).check());
        assertNull(setbacks.get(2).check());
        assertEquals("places", setbacks.get(2).cause());
        assertTrue(flags.drainSetbacks().isEmpty());
    }
}
