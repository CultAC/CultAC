package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.api.Test;

/** A slow-falling glide pulled up past level (SuperDebug paste of issue #90). */
class JavaSlowFallingGlideTest {
    private static final double GRAVITY = 0.08;
    private static final double SLOW_FALLING_GRAVITY = 0.01;
    private static final float YAW = -94.5F, PITCH = -35.1F;
    // The recorded coordinates, moved by whole blocks onto the fixture's loaded chunks.
    private static final Vec3 SHIFT = new Vec3(-263, 0, -174);
    private static final Vec3 PREVIOUS_FROM = new Vec3(261.9978875431547, 80.96755394739817, 174.36349333999576);
    private static final Vec3 FROM = new Vec3(263.4212360285169, 80.96998012095612, 174.11953767750742);

    /**
     * LivingEntity#aiStep zeroes a deltaMovement.y below 0.003 before travel, and LivingEntity#getEffectiveGravity
     * reads that stored y: at or below zero, slow falling caps the gravity, whatever the glide makes of it.
     */
    @Test
    void theGravityFollowsTheStoredVelocityBeforeTheGlide() throws Exception {
        Vec3 previousMove = FROM.subtract(PREVIOUS_FROM);
        assertTrue(previousMove.y > 0 && previousMove.y < 0.003);
        Vec3 stored = new Vec3(previousMove.x, 0, previousMove.z);
        assertExact(glide(previousMove, stored, SLOW_FALLING_GRAVITY));

        Vec3 rising = new Vec3(previousMove.x, 0.004, previousMove.z);
        assertExact(glide(rising, rising, GRAVITY));
    }

    private static PredictionResult glide(Vec3 carried, Vec3 stored, double gravity) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.SLOW_FALLING, 0);
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            f.player.xRot = YAW;
            f.player.yRot = PITCH;
            Vec3 from = FROM.add(SHIFT);
            f.place(from, carried);
            Vec3 move = ElytraTransform.getElytraMovement(
                    f.player, stored, ReachUtils.getLook(f.player, YAW, PITCH), PITCH, gravity);
            return f.tick(from.add(move), false);
        }
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(result.getSimulationContext().usesFallFlyingMovement());
        assertTrue(exact(result), describe(result));
    }
}
