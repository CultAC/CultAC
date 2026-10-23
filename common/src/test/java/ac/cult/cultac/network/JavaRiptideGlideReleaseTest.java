package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A riptide released mid-glide. Minecraft#handleKeybinds runs TridentItem#releaseUsing before the tick's
 * LivingEntity#travelFallFlying, so the glide starts from the pushed velocity.
 */
class JavaRiptideGlideReleaseTest {
    private static final ServerboundPlayerInput NO_KEYS =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double GRAVITY = 0.08;
    private static final float YAW = 0.0F;
    private static final int RIPTIDE = 3;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 20, -3.5);
    private static final Vec3 RISING = new Vec3(0, 0.2, 0);

    @ParameterizedTest
    @CsvSource({"20, 0.2, 0", "-56, 0.2, 0", "-58, 0, 0.6"})
    void theGlideStartsFromThePushedVelocity(float pitch, double y, double z) throws Exception {
        Vec3 carried = new Vec3(0, y, z);
        for (boolean certain : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                Vec3 velocity = release(f, pitch, carried, certain);
                PredictionResult result = f.tick(START.add(velocity), false);
                assertTrue(result.getSimulationContext().usesFallFlyingMovement());
                assertTrue(exact(result), () -> "pitch " + pitch + " certain " + certain + ": " + describe(result));
            }
        }
    }

    @Test
    void theGlideMovesNoFurtherThanThePushedVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 velocity = release(f, 20.0F, RISING, true);
            assertRejected(f.tick(START.add(velocity).add(new Vec3(0.5, 0, 0)), false), "Elytra");
        }
    }

    /** Sets up a release mid-glide; the velocity TridentItem#releaseUsing and the glide leave. */
    private static Vec3 release(JavaTickFixture f, float pitch, Vec3 carried, boolean certain) {
        f.player.isGliding = true;
        f.player.refreshPlayerPose();
        f.player.xRot = YAW;
        f.player.yRot = pitch;
        f.input(NO_KEYS);
        f.place(START, carried);
        f.player.packetStateData.riptideLevel = RIPTIDE;
        f.player.packetStateData.riptideReleaseCertain = certain;
        f.player.riptideSpinAttackTicks = 20;
        return ElytraTransform.getElytraMovement(
                f.player, carried.add(push(pitch)), ReachUtils.getLook(f.player, YAW, pitch), pitch, GRAVITY);
    }

    /** TridentItem#releaseUsing: the look scaled to the spin attack strength. */
    private static Vec3 push(float pitch) {
        float strength = 3.0F * ((1.0F + RIPTIDE) / 4.0F);
        float yRot = YAW * (float) (Math.PI / 180.0);
        float xRot = pitch * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(yRot) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(yRot) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new Vec3(xd * (strength / dist), yd * (strength / dist), zd * (strength / dist));
    }
}
