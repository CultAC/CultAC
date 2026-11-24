package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A jump from a sneaking edge with shift still held. Player#maybeBackOffFromEdge leaves a rising delta whole, so the jump
 * moves by the deltaMovement the held ticks carried plus this tick's crouching input, whatever way the input turns.
 */
class JavaSneakEdgeJumpTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_JUMP_FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, true, true, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    // JavaTickFixture's floor ends at x = 9; the player's box overhangs it by 0.299.
    private static final double EDGE_X = 9.299;
    // The player's box ends 0.01 short of the slab's block at z = 1.
    private static final double STEP_Z = 0.69;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double JUMP_Y = 0.42F;
    private static final double SNEAK_INPUT = (double) (0.98F * 0.3F) * (double) 0.1F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final float JUMP_YAW = -33.4F;
    private static final int EDGE_TICKS = 6;

    @Test
    void jumpTurnedFromTheEdgeIsExact() throws Exception {
        PredictionResult jump = jump(0.0);
        assertTrue(exact(jump), describe(jump));
    }

    @Test
    void jumpPastTheCarryIsFlagged() throws Exception {
        PredictionResult jump = jump(0.01);
        assertFalse(exact(jump), describe(jump));
    }

    /**
     * Player#maybeBackOffFromEdge reads the delta before Entity#collide steps: a step up onto a slab after the held
     * ticks zeroes the carried x, and the step lifts the z move to the slab's top.
     */
    @Test
    void stepUpFromTheEdgeIsExact() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(8, FLOOR_Y, 1),
                    DataTables.defaults().registry().block("minecraft:stone_slab").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            holdLeftAtTheEdge(f, STEP_Z);
            f.input(SNEAK_FORWARD_LEFT);
            Vec3 input = crouchingInput(f, 1.0F, 1.0F, 0.0F);
            PredictionResult step = f.tick(new Vec3(EDGE_X, FLOOR_Y + 0.5, STEP_Z + input.z), true);
            assertTrue(exact(step), describe(step));
        }
    }

    /** Holds the player at the floor's edge sneaking left, then jumps forward and right {@code past} beyond vanilla. */
    private static PredictionResult jump(double past) throws Exception {
        try (var f = new JavaTickFixture()) {
            double velocity = holdLeftAtTheEdge(f, 0.5);
            f.input(SNEAK_JUMP_FORWARD_RIGHT);
            f.player.xRot = JUMP_YAW;
            Vec3 input = crouchingInput(f, -1.0F, 1.0F, JUMP_YAW);
            return f.tick(new Vec3(EDGE_X + velocity + input.x, FLOOR_Y + JUMP_Y, 0.5 + input.z + past), false);
        }
    }

    /** Sneaks left at the floor's edge for the held ticks; the x of the deltaMovement they carry. */
    private static double holdLeftAtTheEdge(JavaTickFixture f, double z) {
        f.place(new Vec3(EDGE_X, FLOOR_Y, z), new Vec3(0, GROUNDED_Y, 0));
        f.input(SNEAK);
        assertExact(-1, f.tick(new Vec3(EDGE_X, FLOOR_Y, z), true));
        f.input(SNEAK_LEFT);
        double velocity = 0.0;
        for (int tick = 0; tick < EDGE_TICKS; tick++) {
            // LocalPlayer#sendPosition sends no position for a held tick the back-off keeps at the edge.
            f.tickEnd();
            assertExact(tick, f.player.checkManager.getSimulationProcessor().getLastPrediction());
            velocity = (velocity + SNEAK_INPUT) * GROUND_FRICTION;
        }
        return velocity;
    }

    /** KeyboardInput#tick, LocalPlayer#modifyInput while crouching and Entity#getInputVector at the walking speed. */
    private static Vec3 crouchingInput(JavaTickFixture f, float strafe, float forward, float yaw) {
        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(strafe, 0.0D, forward), 1.0F, 0.3F);
        Vec3 movement = (modified.lengthSqr() > 1.0D ? modified.normalize() : modified).scale(0.1F);
        float yawRadians = yaw * ((float) Math.PI / 180F);
        float sin = f.player.trigHandler.sin(yawRadians);
        float cos = f.player.trigHandler.cos(yawRadians);
        return new Vec3(movement.x * cos - movement.z * sin, 0.0D, movement.z * cos + movement.x * sin);
    }

    private static void assertExact(int tick, PredictionResult result) {
        assertTrue(exact(result), "tick " + tick + " " + describe(result));
    }
}
