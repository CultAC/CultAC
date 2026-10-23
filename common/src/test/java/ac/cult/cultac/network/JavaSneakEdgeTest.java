package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** Player#maybeBackOffFromEdge at the +X edge of the fixture floor, which ends at x = 9. */
class JavaSneakEdgeTest {
    private static final ServerboundPlayerInput FORWARD_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final double EDGE = 9.0;
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    // LivingEntity#travelInAir on stone: blockFriction * 0.91F.
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    // Facing +X: Mth.sin(-90 degrees) is -1 and Mth.cos is 0, so the forward input is all X.
    private static final float YAW_EAST = -90.0F;
    private static final double START_X = 9.1;

    @Test
    void theEdgeWithholdsTheMoveButNotTheVelocityThatCarriesPastIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.xRot = YAW_EAST;
            f.place(new Vec3(START_X, FLOOR_Y, 0.5), new Vec3(0, -0.0784, 0));
            f.input(FORWARD_SHIFT);
            double x = START_X;
            double velocity = 0;
            int withheldTicks = 0;
            for (int tick = 0; tick < 8; tick++) {
                // LocalPlayer#aiStep reads crouching from the previous tick's shift key.
                double delta = velocity + input(tick > 0);
                double moved = backOff(x, delta);
                if (moved != delta) withheldTicks++;
                x += moved;
                PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
                assertTrue(exact(result), "tick " + tick + " withheld " + (moved != delta) + ": " + describe(result));
                velocity = delta * GROUND_FRICTION;
            }
            assertTrue(withheldTicks >= 3, "the scenario never reached the edge");

            // Releasing shift moves by the withheld velocity; the crouch still slows this tick's input.
            f.input(FORWARD);
            assertExact(f.tick(new Vec3(x + velocity + input(true), FLOOR_Y, 0.5), true));
            assertEquals(0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
        }
    }

    @Test
    void aShiftingPlayerCannotWalkOffTheEdge() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.xRot = YAW_EAST;
            f.place(new Vec3(START_X, FLOOR_Y, 0.5), new Vec3(0, -0.0784, 0));
            f.input(FORWARD_SHIFT);
            double x = START_X;
            double velocity = 0;
            for (int tick = 0; tick < 20; tick++) {
                double delta = velocity + input(tick > 0);
                double moved = backOff(x, delta);
                if (moved != delta) {
                    PredictionResult offTheEdge = f.tick(new Vec3(x + delta, FLOOR_Y, 0.5), true);
                    assertFalse(exact(offTheEdge), describe(offTheEdge));
                    return;
                }
                x += moved;
                assertExact(f.tick(new Vec3(x, FLOOR_Y, 0.5), true));
                velocity = delta * GROUND_FRICTION;
            }
            throw new AssertionError("the scenario never reached the edge");
        }
    }

    /** Player#maybeBackOffFromEdge's X loop: canFallAtLeast once the 1.0E-7 inset box clears the stone. */
    private static double backOff(double x, double delta) {
        double minX = x - HALF_WIDTH;
        double step = Math.signum(delta) * 0.05;
        while (delta != 0.0 && minX + 1.0E-7 + delta >= EDGE) {
            if (Math.abs(delta) <= 0.05) return 0.0;
            delta -= step;
        }
        return delta;
    }

    /** LocalPlayer#modifyInput for forward alone, through Entity#getInputVector at 0.1 speed. */
    private static double input(boolean crouching) {
        float zza = 0.98F;
        if (crouching) zza *= 0.3F;
        // modifyInputSpeedForSquareMovement on a single axis.
        float length = (float) Math.sqrt(zza * zza);
        float direction = zza * (1.0F / length);
        zza = direction * Math.min(length * (float) Math.sqrt(1.0F), 1.0F);
        return (double) zza * 0.1F;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
