package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class InsideBlocksBefore1Dot21Dot9Test {
    private static final Vec3 WEB = new Vec3(0.25, 0.05F, 0.25);
    private static final Vec3 BERRY_BUSH = new Vec3(0.8F, 0.75, 0.8F);

    /** 1.21.2-1.21.4 record one movement per tick; a move shorter than 0.99999 visits only its end box. */
    @Test
    void aSingleShortMovementVisitsOnlyTheEndBox() {
        Collisions.StuckSpeedLookup web = (x, y, z) -> x == 0 && y == 0 && z == -1 ? WEB : null;
        assertNull(Collisions.resolveStuckSpeedBefore1Dot21Dot9(
                box(0.5, 0.5, 0.29), box(0.5, 0.45, 0.32), false, false, web));
    }

    /**
     * 1.21.5-1.21.8 step the movement one axis at a time in Entity#axisStepOrder; with |x| < |z| the Y step comes
     * first and its end box still overlaps a web the final box has left.
     */
    @Test
    void axisStepsVisitTheEndBoxOfEveryStep() {
        Collisions.StuckSpeedLookup web = (x, y, z) -> x == 0 && y == 0 && z == -1 ? WEB : null;
        assertEquals(
                WEB,
                Collisions.resolveStuckSpeedBefore1Dot21Dot9(
                        box(0.5, 0.5, 0.29), box(0.5, 0.45, 0.32), true, false, web));
    }

    /** BlockGetter#addCollisionsAlongTravel visits the blocks a movement of at least 0.99999 passes through. */
    @Test
    void aLongMovementVisitsTheBlocksAlongItsTravel() {
        Collisions.StuckSpeedLookup web = (x, y, z) -> x == 0 && y == 0 && z == 1 ? WEB : null;
        for (boolean nudged : new boolean[] {false, true}) {
            assertEquals(
                    WEB,
                    Collisions.resolveStuckSpeedBefore1Dot21Dot9(
                            box(0.5, 0.5, 0.5), box(0.5, 0.5, 2.5), false, nudged, web));
        }
    }

    /** BlockPos#betweenClosed iterates X fastest and Z slowest; Entity#makeStuckInBlock keeps the last block. */
    @Test
    void theLastBlockInBetweenClosedOrderSetsTheMultiplier() {
        Collisions.StuckSpeedLookup blocks = (x, y, z) -> {
            if (y != 0) return null;
            if (x == 1 && z == 0) return WEB;
            if (x == 0 && z == 1) return BERRY_BUSH;
            return null;
        };
        assertEquals(
                BERRY_BUSH,
                Collisions.resolveStuckSpeedBefore1Dot21Dot9(
                        box(1.0, 0.5, 1.0), box(1.0, 0.5, 1.0), false, false, blocks));
    }

    private static SimpleCollisionBox box(double x, double y, double z) {
        return new SimpleCollisionBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }
}
