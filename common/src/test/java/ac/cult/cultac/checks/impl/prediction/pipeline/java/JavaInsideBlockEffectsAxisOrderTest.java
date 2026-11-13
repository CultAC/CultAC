package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.11 player sprint-jumps into a wall that a cobblestone block above a cobweb forms, lands next to the cobweb
 * and slides past its corner along the wall. The client moves the next tick at full sprint speed: its
 * Entity#checkInsideBlocks stepped Z before X and never entered the cobweb.
 */
class JavaInsideBlockEffectsAxisOrderTest {
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final double HEIGHT = 1.8F;
    private static final int WEB_X = 4035, WEB_Y = 63, WEB_Z = 15988;

    // The client's positions after the wall tick and after the landing tick.
    private static final Vec3 WALL_END = new Vec3(4034.699999988079, 63.024424088213685, 15987.744559857281);
    private static final Vec3 LANDING_END = new Vec3(4034.7220850189083, 63.0, 15987.625320499114);
    private static final Vec3 MOVED = LANDING_END.subtract(WALL_END);

    // The landing tick's selected trace: last tick's uncollided x carry plus air input, clipped by the collision
    // envelope to the client's x.
    private static final Vec3 ATTEMPTED = new Vec3(0.20521741563681983, -0.2276884875449882, -0.11923935816776066);

    // The landing tick's next-tick velocity candidates.
    private static final Vec3 UNCOLLIDED_CARRY = new Vec3(0.020097378633777602, -0.0784000015258789, -0.10850781905983882);
    private static final Vec3 ENVELOPE_CARRY = new Vec3(0.18674785361154697, -0.0784000015258789, -0.10850781905983882);
    private static final Vec3 COLLIDED_CARRY = new Vec3(0.0, -0.0784000015258789, -0.10850781905983882);

    @Test
    void theAttemptedOrderStepsXFirstIntoTheCobweb() {
        assertTrue(entersWeb(ATTEMPTED));
    }

    @Test
    void anUncollidedCarryReplaysTheClientsMoveAndMissesTheCobweb() {
        Vec3 order = JavaInsideBlockEffects.axisOrderMovement(ClientVersion.V_1_21_11, ATTEMPTED, MOVED, UNCOLLIDED_CARRY);
        assertEquals(MOVED.x, order.x);
        assertEquals(MOVED.z, order.z);
        assertFalse(entersWeb(order));
    }

    @Test
    void aCarryOrderedUnlikeTheMoveKeepsTheAttemptedOrderAndTheCobweb() {
        Vec3 order = JavaInsideBlockEffects.axisOrderMovement(ClientVersion.V_1_21_11, ATTEMPTED, MOVED, ENVELOPE_CARRY);
        assertSame(ATTEMPTED, order);
        assertTrue(entersWeb(order));
    }

    @Test
    void aCollidedAxisKeepsTheAttemptedOrderAndTheCobweb() {
        Vec3 order = JavaInsideBlockEffects.axisOrderMovement(ClientVersion.V_1_21_11, ATTEMPTED, MOVED, COLLIDED_CARRY);
        assertSame(ATTEMPTED, order);
        assertTrue(entersWeb(order));
    }

    @Test
    void otherVersionsKeepTheAttemptedOrder() {
        assertSame(
                ATTEMPTED,
                JavaInsideBlockEffects.axisOrderMovement(ClientVersion.V_26_2, ATTEMPTED, MOVED, UNCOLLIDED_CARRY));
    }

    @Test
    void aMoveEndingInTheCobwebEntersItInEitherOrder() {
        Vec3 end = LANDING_END.add(0.0, 0.0, 0.2);
        Vec3 moved = end.subtract(WALL_END);
        Vec3 order = JavaInsideBlockEffects.axisOrderMovement(ClientVersion.V_1_21_11, ATTEMPTED, moved, UNCOLLIDED_CARRY);
        assertEquals(moved.x, order.x);
        assertTrue(entersWeb(end, order));
        assertTrue(entersWeb(end, ATTEMPTED));
    }

    private static boolean entersWeb(Vec3 originalMovement) {
        return entersWeb(LANDING_END, originalMovement);
    }

    private static boolean entersWeb(Vec3 end, Vec3 originalMovement) {
        boolean[] web = {false};
        Collisions.visitInsideBlocks26Dot2(box(WALL_END), box(end), originalMovement, (from, to, pos, precise) -> {
            boolean hit = pos.getX() == WEB_X && pos.getY() == WEB_Y && pos.getZ() == WEB_Z;
            web[0] |= hit;
            return hit;
        });
        return web[0];
    }

    private static SimpleCollisionBox box(Vec3 position) {
        return new SimpleCollisionBox(
                position.x - HALF_WIDTH,
                position.y,
                position.z - HALF_WIDTH,
                position.x + HALF_WIDTH,
                position.y + HEIGHT,
                position.z + HALF_WIDTH);
    }
}
