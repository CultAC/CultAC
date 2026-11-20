package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A player sneaking diagonally past a block corner. Entity#collideWithShapes moves along the axes in
 * Direction#axisStepOrder of the move before collision, so the longer axis carries the box beside the block and the
 * shorter one stops at its face, while the opposite order would pass the corner untouched.
 */
class JavaSneakCornerTest {
    private static final float SPEED = 0.1F;
    private static final float SNEAKING_SPEED = 0.3F;

    // Bundle 01a16c03 seq 765: Z moves first, then X stops at the west face of a block whose north face is at z = 1.
    private static final float EAST_YAW = -444.06342F;
    private static final ServerboundPlayerInput SNEAK_FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, false, true, true);
    private static final Vec3 EAST_START = new Vec3(12070.03790361417 - 12069.375, FLOOR_Y, 12000.955773644957 - 12000.3);
    private static final Vec3 EAST_VELOCITY =
            new Vec3(0.020878050448551348, -0.0784000015258789, 0.04038846689956531);
    private static final Vec3 EAST_MOVE = new Vec3(0.03709637390966236, 0.0, 0.0726722682302352);
    private static final double EAST_FACE_X = 1.0 - 0.3;
    private static final double EAST_CORNER_Z = 1.0;

    // Bundle 01a16d93 seq 19229: X moves first, then Z stops at the south face of a block whose east face is at x = 1.
    private static final float NORTH_YAW = 342.4256F;
    private static final ServerboundPlayerInput SNEAK_BACK_RIGHT =
            new ServerboundPlayerInput(false, true, false, true, false, true, false);
    private static final Vec3 NORTH_START = new Vec3(131.31212040053745 - 130, FLOOR_Y, -12.66370863945958 + 13);
    private static final Vec3 NORTH_VELOCITY =
            new Vec3(-0.043289794420177996, -0.0784000015258789, -0.024964228169235506);
    private static final Vec3 NORTH_MOVE = new Vec3(-0.0801959704800197, 0.0, -0.036291348619490904);
    private static final double NORTH_FACE_Z = 0.0 + 0.3;
    private static final double NORTH_CORNER_X = 1.0;

    @Test
    void sneakingIntoACornerAfterTheLongerAxisIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult result = east(f, null);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void walkingIntoACornerAfterTheLongerAxisWhileSneakingIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertRejected(east(f, 1.0F), "NoSneakSlow");
        }
    }

    @Test
    void sneakingIntoACornerAfterTheLongerXAxisIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult result = north(f, null);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void walkingIntoACornerAfterTheLongerXAxisWhileSneakingIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertRejected(north(f, 1.0F), "NoSneakSlow");
        }
    }

    /** The recorded move, or with {@code movingSlowlyFactor} the move the keys make at that factor. */
    private static PredictionResult east(JavaTickFixture f, Float movingSlowlyFactor) throws Exception {
        for (int z = 1; z <= 8; z++) stone(f, 1, z);
        settle(f);
        Vec3 move = EAST_MOVE;
        if (movingSlowlyFactor != null) {
            Vec3 delta = EAST_VELOCITY.add(
                    ClientMoveInput.of(f, SNEAK_FORWARD_RIGHT, movingSlowlyFactor, SPEED, EAST_YAW));
            assertTrue(Math.abs(delta.x) < Math.abs(delta.z) && EAST_START.z + 0.3 + delta.z > EAST_CORNER_Z);
            move = new Vec3(Math.min(delta.x, EAST_FACE_X - EAST_START.x), 0.0, delta.z);
        }
        return tick(f, EAST_START, EAST_VELOCITY, EAST_YAW, SNEAK_FORWARD_RIGHT, move);
    }

    private static PredictionResult north(JavaTickFixture f, Float movingSlowlyFactor) throws Exception {
        for (int[] block : new int[][] {{0, -1}, {-1, -1}, {-1, 0}, {-1, -2}, {0, -2}, {1, -2}}) {
            stone(f, block[0], block[1]);
        }
        settle(f);
        Vec3 move = NORTH_MOVE;
        if (movingSlowlyFactor != null) {
            Vec3 delta = NORTH_VELOCITY.add(
                    ClientMoveInput.of(f, SNEAK_BACK_RIGHT, movingSlowlyFactor, SPEED, NORTH_YAW));
            assertTrue(Math.abs(delta.x) >= Math.abs(delta.z) && NORTH_START.x - 0.3 + delta.x < NORTH_CORNER_X);
            move = new Vec3(delta.x, 0.0, Math.max(delta.z, NORTH_FACE_Z - NORTH_START.z));
        }
        return tick(f, NORTH_START, NORTH_VELOCITY, NORTH_YAW, SNEAK_BACK_RIGHT, move);
    }

    private static PredictionResult tick(
            JavaTickFixture f, Vec3 start, Vec3 velocity, float yaw, ServerboundPlayerInput keys, Vec3 move)
            throws Exception {
        f.place(start, velocity);
        f.player.xRot = f.player.lastTickXRot = yaw;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        f.input(keys);
        return f.tick(start.add(move.x, 0.0, move.z), true);
    }

    private static void stone(JavaTickFixture f, int x, int z) {
        for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.STONE.defaultState());
        }
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
