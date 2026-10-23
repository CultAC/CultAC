package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.api.Test;

/** A riptide released while a glide touches the ground (SuperDebug paste of issue #85). */
class JavaRiptideGlideLandingTest {
    private static final ServerboundPlayerInput NO_KEYS =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double GRAVITY = 0.08;
    private static final float YAW = 0.0F, PITCH = 6.0F;
    private static final int RIPTIDE = 3;

    /**
     * TridentItem#releaseUsing pushes a grounded glider, then moves it up by Entity#move(SELF, (0, 1.1999999F, 0))
     * before the tick's travel. That move is position only: the glide that follows stores the pushed velocity.
     */
    @Test
    void theGroundLiftIsNotCarriedIntoTheNextGlide() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            f.player.xRot = YAW;
            f.player.yRot = PITCH;
            f.input(NO_KEYS);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 0.096375, 0.5);
            Vec3 falling = new Vec3(0.004081, -0.133844, 0.340665);
            f.place(position, falling);

            Vec3 velocity = glide(f, falling);
            assertTrue(position.y + velocity.y < FLOOR_Y);
            position = new Vec3(position.x + velocity.x, FLOOR_Y, position.z + velocity.z);
            assertExact(f.tick(position, true));
            // Entity#move clears the y of the landing glide's velocity.
            Vec3 landed = new Vec3(velocity.x, 0, velocity.z);

            f.player.packetStateData.riptideLevel = RIPTIDE;
            f.player.riptideSpinAttackTicks = 20;
            velocity = glide(f, landed.add(push()));
            position = position.add(new Vec3(0, 1.1999999F, 0)).add(velocity);
            PredictionResult released = f.tick(position, false);
            assertExact(released);
            assertEquals(1.1999999F, released.getPositionOnlyDelta().y);

            velocity = glide(f, velocity);
            position = position.add(velocity);
            assertExact(f.tick(position, false));
        }
    }

    /** TridentItem#releaseUsing: the look scaled to the spin attack strength. */
    private static Vec3 push() {
        float strength = 3.0F * ((1.0F + RIPTIDE) / 4.0F);
        float yRot = YAW * (float) (Math.PI / 180.0);
        float xRot = PITCH * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(yRot) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(yRot) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new Vec3(xd * (strength / dist), yd * (strength / dist), zd * (strength / dist));
    }

    private static Vec3 glide(JavaTickFixture f, Vec3 velocity) {
        return ElytraTransform.getElytraMovement(
                f.player, velocity, ReachUtils.getLook(f.player, YAW, PITCH), PITCH, GRAVITY);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(result.getSimulationContext().usesFallFlyingMovement());
        assertTrue(exact(result), describe(result));
    }
}
