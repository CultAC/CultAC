package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff.Carry;
import org.junit.jupiter.api.Test;

/**
 * When a sneaking edge's carry stands for the client's deltaMovement. A carry is built from the tick's start, and the
 * edge hides that start on a fully backed-off axis, so it is exact only from an exact start and only when every
 * reality the next tick may start from has one.
 */
class SneakEdgeCarryTest {
    @Test
    void everyRealityCarriedFromAnExactStartIsExact() {
        assertTrue(Carry.EVERY.carriesExactly(true));
        assertTrue(Carry.EVERY.leavesExact(true, true));
    }

    @Test
    void aCarryFromAStartTheEngineCouldNotCarryIsNotExact() {
        assertFalse(Carry.EVERY.carriesExactly(false));
        assertFalse(Carry.EVERY.leavesExact(false, true));
    }

    @Test
    void aCarryMissingForOneRealityIsNotExact() {
        assertFalse(Carry.PARTIAL.carriesExactly(true));
        assertFalse(Carry.PARTIAL.leavesExact(true, true));
        assertFalse(Carry.PARTIAL.leavesExact(true, false));
    }

    @Test
    void aTickOffTheEdgeHidesNothing() {
        assertTrue(Carry.UNKNOWN.leavesExact(false, false));
        assertTrue(Carry.NOTHING_HIDDEN.leavesExact(false, false));
        assertFalse(Carry.UNKNOWN.carriesExactly(true));
        assertFalse(Carry.NOTHING_HIDDEN.carriesExactly(true));
    }

    @Test
    void anEdgeTickHidesNothingOnlyWhenNoDeltaWasShortenedFromAnExactStart() {
        assertTrue(Carry.NOTHING_HIDDEN.leavesExact(true, true));
        assertFalse(Carry.NOTHING_HIDDEN.leavesExact(false, true));
        assertFalse(Carry.UNKNOWN.leavesExact(true, true));
    }
}
