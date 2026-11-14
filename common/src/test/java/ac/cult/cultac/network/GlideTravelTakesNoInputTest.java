package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** LivingEntity#travelFallFlying moves by the glide velocity alone; no moveRelative input reaches it. */
class GlideTravelTakesNoInputTest {
    private static final double GRAVITY = 0.08;
    private static final float YAW = 30.0F, PITCH = -5.0F;
    private static final Vec3 FROM = new Vec3(8.5, FLOOR_Y + 200, 8.5);
    private static final Vec3 CARRIED = new Vec3(0.5, -0.1, 0.8);

    private record Outcome(int ticks, double minOffset, double maxOffset, boolean setback) {}

    @Test
    void horizontalAccelerationInsideTheAirInputRadiusIsRejected() throws Exception {
        Outcome outcome = glide(ClientVersion.V_26_3, 0.025, 200);
        assertTrue(outcome.minOffset() > 0.02, outcome.toString());
        // The rejected acceleration is not carried into the next tick's start.
        assertTrue(outcome.maxOffset() > 2 * outcome.minOffset(), outcome.toString());
        assertTrue(outcome.setback(), outcome.toString());
        assertTrue(outcome.ticks() < 60, outcome.toString());
    }

    @Test
    void largeHorizontalAccelerationIsSetBackAtOnce() throws Exception {
        Outcome outcome = glide(ClientVersion.V_26_3, 0.2, 200);
        assertTrue(outcome.setback(), outcome.toString());
        assertTrue(outcome.ticks() <= 2, outcome.toString());
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aLongVanillaGlideStaysExact(ClientVersion version) throws Exception {
        Outcome outcome = glide(version, 0.0, 200);
        assertEquals(200, outcome.ticks(), outcome.toString());
        assertTrue(outcome.maxOffset() < 1.0E-5, outcome.toString());
    }

    /** LivingEntity#travel dispatches to travelInFluid before travelFallFlying, and travelInFluid takes input. */
    @Test
    void aGlideIntoWaterKeepsTheFluidInput() throws Exception {
        try (var f = start(ClientVersion.V_26_3)) {
            f.water(new BlockPos(-8, FLOOR_Y, -8), new BlockPos(8, FLOOR_Y + 3, 8));
            f.place(new Vec3(0.5, FLOOR_Y + 2.5, 0.5), Vec3.ZERO);
            PredictionResult inWater = f.tick(new Vec3(0.5, FLOOR_Y + 2.5, 0.5), false);
            assertTrue(inWater.getHorizontalInputRadius() > 0, describe(inWater));
        }
    }

    private static JavaTickFixture start(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.setExperimentalChecks(true);
        Check offset = f.player.checkManager.getCheck(OffsetHandler.class);
        offset.setEnabled(true);
        offset.reload();
        offset.violations = 0;
        f.player.isGliding = true;
        f.player.refreshPlayerPose();
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        return f;
    }

    private static Outcome glide(ClientVersion version, double thrust, int ticks) throws Exception {
        try (var f = start(version)) {
            f.place(FROM, CARRIED);
            var look = ReachUtils.getLook(f.player, YAW, PITCH);
            double norm = Math.hypot(look.getX(), look.getZ());
            Vec3 push = new Vec3(look.getX() / norm * thrust, 0, look.getZ() / norm * thrust);
            Vec3 velocity = CARRIED;
            Vec3 position = FROM;
            double minOffset = Double.MAX_VALUE, maxOffset = 0;
            int tick = 0;
            while (tick < ticks) {
                velocity = ElytraTransform.getElytraMovement(
                        f.player, cut(version, velocity).add(push), look, PITCH, GRAVITY);
                position = position.add(velocity);
                PredictionResult result = f.tick(position, false);
                tick++;
                minOffset = Math.min(minOffset, result.getOffset());
                maxOffset = Math.max(maxOffset, result.getOffset());
                if (f.player.getSetbackTeleportUtil().isPendingSetback()) {
                    return new Outcome(tick, minOffset, maxOffset, true);
                }
            }
            return new Outcome(tick, minOffset, maxOffset, false);
        }
    }

    /** LivingEntity#aiStep: 1.21.5+ players zero a horizontal speed under 0.003, older clients each axis. */
    private static Vec3 cut(ClientVersion version, Vec3 v) {
        double x = v.x, z = v.z;
        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
            if (x * x + z * z < 9.0E-6) x = z = 0;
        } else {
            if (Math.abs(x) < 0.003) x = 0;
            if (Math.abs(z) < 0.003) z = 0;
        }
        return new Vec3(x, Math.abs(v.y) < 0.003 ? 0 : v.y, z);
    }
}
