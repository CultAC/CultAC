package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientRavagerRoars;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Ravager#applyRoarKnockbackClient reaches every living entity Level#getEntitiesOfClass returns for the ravager's box
 * inflated by 4.0. AABB#intersects is strict with no epsilon: a positive overlap below CultAC's collision epsilon is
 * still reached, and boxes that only touch are not.
 */
class RavagerRoarOverlapTest {
    // Ravager EntityDimensions 1.95F x 2.2F at (0.5, 64, 0.5): half width (float) 0.975F.
    private static final double RAVAGER_HALF = 1.95F / 2.0F;
    private static final double PLAYER_HALF = 0.6F / 2.0F;
    private static final SimpleCollisionBox RAVAGER =
            new SimpleCollisionBox(0.5 - RAVAGER_HALF, 64, 0.5 - RAVAGER_HALF, 0.5 + RAVAGER_HALF, 66.2, 0.5 + RAVAGER_HALF);
    private static final double REACH = 0.5 + RAVAGER_HALF + 4.0;

    @Test
    void aPositiveOverlapBelowTheCollisionEpsilonIsRoared() {
        double x = REACH - 5.0E-8 + PLAYER_HALF;
        Vec3 impulse = ClientRavagerRoars.impulse(RAVAGER, player(x), new Vec3(x, 64, 0.5));
        // Ravager#strongKnockback: the inverse squared horizontal distance, scaled by 4, and 0.2 up.
        double dx = x - 0.5;
        assertEquals(new Vec3(dx / (dx * dx) * 4.0, 0.2, 0), impulse);
    }

    @Test
    void touchingBoxesAreNotRoared() {
        double x = REACH + PLAYER_HALF;
        assertEquals(Vec3.ZERO, ClientRavagerRoars.impulse(RAVAGER, player(x), new Vec3(x, 64, 0.5)));
    }

    @Test
    void aDeepOverlapIsRoared() {
        double x = REACH - 0.1 + PLAYER_HALF;
        assertNotEquals(Vec3.ZERO, ClientRavagerRoars.impulse(RAVAGER, player(x), new Vec3(x, 64, 0.5)));
    }

    private static SimpleCollisionBox player(double x) {
        return new SimpleCollisionBox(x - PLAYER_HALF, 64, 0.5 - PLAYER_HALF, x + PLAYER_HALF, 65.8, 0.5 + PLAYER_HALF);
    }
}
