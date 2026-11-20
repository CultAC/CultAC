package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Forward input into a shape at the player's side: Entity#collide gathers block shapes through
 * {@code getBoundingBox().expandTowards(movement)}, and the movement carries moveRelative's input.
 */
class JavaFlushShapeCollisionTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final float YAW = -64.0F;
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    // Entity#getInputVector on stone: (0, 0, 0.98F) scaled by 0.1F and turned by the yaw.
    private static final double INPUT = (double) 0.98F * (double) 0.1F;
    private static final double RADIANS = YAW * (float) (Math.PI / 180.0);
    private static final double COS = mth((long) (RADIANS * 10430.378350470453D + 16384.0D));
    private static final int ANDESITE_WALL =
            DataTables.defaults().registry().block("minecraft:andesite_wall").defaultState();
    private static final int OAK_FENCE =
            DataTables.defaults().registry().block("minecraft:oak_fence").defaultState();
    // Facing north, hinged left and open, the door turns east: its plate spans x 0..3/16 of the cell.
    private static final int OPEN_DOOR = DataTables.defaults()
            .registry()
            .with(DataTables.defaults().registry().block("minecraft:oak_door").defaultState(), "open", "true");
    private static final int OPEN_DOOR_UPPER = DataTables.defaults().registry().with(OPEN_DOOR, "half", "upper");
    // Hinged right, the open door turns west: its plate spans x 13/16..1 of the cell.
    private static final int OPEN_RIGHT_DOOR = DataTables.defaults().registry().with(OPEN_DOOR, "hinge", "right");
    private static final int OPEN_RIGHT_DOOR_UPPER =
            DataTables.defaults().registry().with(OPEN_RIGHT_DOOR, "half", "upper");
    // Facing south and hinged right, the open door turns east like the north-facing left-hinged one.
    private static final int OPEN_SOUTH_RIGHT_DOOR =
            DataTables.defaults().registry().with(OPEN_RIGHT_DOOR, "facing", "south");
    private static final int OPEN_SOUTH_RIGHT_DOOR_UPPER =
            DataTables.defaults().registry().with(OPEN_SOUTH_RIGHT_DOOR, "half", "upper");
    private static final int STONE_SLAB =
            DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();

    @Test
    void forwardIntoAFlushWallPostSlidesAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, ANDESITE_WALL);
            assertExact(slide(f, 1.25, 0.0));
        }
    }

    @Test
    void forwardIntoAWallPostOneEpsilonAwaySlidesAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, ANDESITE_WALL);
            assertExact(slide(f, 1.25, 1.0E-7));
        }
    }

    @Test
    void forwardIntoAFlushFencePostSlidesAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, OAK_FENCE);
            assertExact(slide(f, 1.375, 0.0));
        }
    }

    @Test
    void forwardIntoAFlushOpenDoorSlidesAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, OPEN_DOOR);
            set(f, 1, FLOOR_Y + 1, 0, OPEN_DOOR_UPPER);
            assertExact(slide(f, 1.0, 0.0));
        }
    }

    @Test
    void forwardIntoAFlushRightHingedOpenDoorSlidesAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, OPEN_RIGHT_DOOR);
            set(f, 1, FLOOR_Y + 1, 0, OPEN_RIGHT_DOOR_UPPER);
            assertExact(slide(f, 1.8125, 0.0));
        }
    }

    @Test
    void forwardIntoAFlushSouthFacingOpenDoorSlidesAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, OPEN_SOUTH_RIGHT_DOOR);
            set(f, 1, FLOOR_Y + 1, 0, OPEN_SOUTH_RIGHT_DOOR_UPPER);
            assertExact(slide(f, 1.0, 0.0));
        }
    }

    /**
     * The slide along the post reaches a slab only through the input: Entity#collide steps the box up onto it,
     * still flush against the post, which is taller than the step.
     */
    @Test
    void slidingAlongAFlushWallPostStepsUpOntoASlabBesideIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, ANDESITE_WALL);
            set(f, 0, FLOOR_Y, 1, STONE_SLAB);
            Vec3 start = start(f, 1.25, 0.0, 1.0 - HALF_WIDTH - 0.01);
            assertExact(f.tick(new Vec3(start.x, FLOOR_Y + 0.5, start.z + INPUT * COS), true));
        }
    }

    @Test
    void forwardIntoAFlushFullBlockSlidesAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, BlockIds.STONE.defaultState());
            assertExact(slide(f, 1.0, 0.0));
        }
    }

    @Test
    void forwardTowardsAWallPostAThousandthAwayClosesTheGapAndSlides() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, ANDESITE_WALL);
            assertExact(slide(f, 1.25, 1.0E-3));
        }
    }

    @Test
    void slidingWithoutClosingAThousandthGapToAWallPostIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            set(f, 1, FLOOR_Y, 0, ANDESITE_WALL);
            Vec3 start = start(f, 1.25, 1.0E-3);
            assertRejected(f.tick(new Vec3(start.x, FLOOR_Y, start.z + INPUT * COS), true), "Angle");
        }
    }

    /** Forward input from {@code gap} short of the face at {@code faceX}; the client ends flush and slides. */
    private static PredictionResult slide(JavaTickFixture f, double faceX, double gap) {
        Vec3 start = start(f, faceX, gap);
        return f.tick(new Vec3(faceX - HALF_WIDTH, FLOOR_Y, start.z + INPUT * COS), true);
    }

    private static Vec3 start(JavaTickFixture f, double faceX, double gap) {
        return start(f, faceX, gap, 0.5);
    }

    private static Vec3 start(JavaTickFixture f, double faceX, double gap, double z) {
        Vec3 start = new Vec3(faceX - HALF_WIDTH - gap, FLOOR_Y, z);
        f.place(start, new Vec3(0, -0.0784000015258789, 0));
        f.player.xRot = f.player.lastTickXRot = YAW;
        f.input(FORWARD);
        return start;
    }

    private static void set(JavaTickFixture f, int x, int y, int z, int state) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** Mth#sin's table entry from 1.21.11 on. */
    private static double mth(long index) {
        return (float) StrictMath.sin((int) (index & 65535L) / 10430.378350470453D);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
