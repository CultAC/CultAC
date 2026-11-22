package ac.cult.cultac.checks.impl.scaffolding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DuplicateRotPlaceTest {
    private static float turn(float fromYaw, float toYaw) {
        return Math.abs(toYaw - fromYaw);
    }

    @Test
    void equalMouseCountTurnsOnTwoPlacesDoNotFlag() {
        DuplicateRotPlace.RepeatedTurns turns = new DuplicateRotPlace.RepeatedTurns();

        // 26 mouse counts at the default sensitivity before each of the first two places.
        assertFalse(turns.place(turn(-94.2001724243164F, -98.10017395019531F), 100));
        assertFalse(turns.place(turn(-130.05018615722656F, -133.9501953125F), 104));
        assertFalse(turns.place(turn(-137.25018310546875F, -153.30018615722656F), 106));
        assertFalse(turns.place(turn(-172.80018615722656F, -191.25018310546875F), 108));
    }

    @Test
    void fixedTurnOnEveryPlaceFlagsFromTheFourthPlace() {
        DuplicateRotPlace.RepeatedTurns turns = new DuplicateRotPlace.RepeatedTurns();
        float yaw = 37.5F;
        float target = yaw + 135.0F;

        assertFalse(turns.place(turn(yaw, target), 10));
        assertFalse(turns.place(turn(yaw, target), 13));
        assertFalse(turns.place(turn(yaw, target), 16));
        assertTrue(turns.place(turn(yaw, target), 19));
        assertTrue(turns.place(turn(yaw, target), 22));
    }

    @Test
    void aDifferentTurnASmallTurnOrALongPauseStartsTheRunAgain() {
        DuplicateRotPlace.RepeatedTurns turns = new DuplicateRotPlace.RepeatedTurns();
        float fixed = 45.0F;
        int gap = DuplicateRotPlace.RepeatedTurns.MAX_PLACE_GAP_TICKS;

        assertNoFlag(turns, fixed, 0, 2, 4);
        assertFalse(turns.place(fixed + 0.15F, 6));
        assertNoFlag(turns, fixed, 8, 10, 12);

        assertFalse(turns.place(1.5F, 14));
        assertNoFlag(turns, fixed, 16, 18, 20);

        assertNoFlag(turns, fixed, 21 + gap, 23 + gap, 25 + gap);
    }

    private static void assertNoFlag(DuplicateRotPlace.RepeatedTurns turns, float turn, int... ticks) {
        for (int tick : ticks) {
            assertFalse(turns.place(turn, tick), "tick " + tick);
        }
    }
}
