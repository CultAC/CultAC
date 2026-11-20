package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A sneaking player whose carried velocity alone would reach a wall, while the input turns the move away before it:
 * Entity#collide clips nothing, so the input is the move minus the carried velocity.
 */
class JavaSneakShortOfWallTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    private static final float YAW = 192.02684F;
    private static final double WALL_X = 2.0;
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final double START_Z = 0.5;
    private static final Vec3 VELOCITY = new Vec3(0.025560924719679943, -0.0784000015258789, -0.030324812861470075);
    private static final double GAP = 0.011544874634495272;
    // The client's move: the carried velocity plus sneaking forward-left input at this yaw.
    private static final double MOVE_X = 0.002930921828493638;
    private static final double MOVE_Z = -0.06520465390394747;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    // Entity#makeStuckInBlock: a cobweb scales the next move by 0.25 horizontally.
    private static final double WEB_STUCK = 0.25;
    private static final float WEB_YAW = -158.0F;
    private static final Vec3 WEB_ENTRY_VELOCITY = new Vec3(0.042, -0.0784000015258789, 0.077);
    private static final double WEB_GAP = 0.003;
    private static final double WEB_Z = 0.75;

    @Test
    void sneakingAwayFromAWallTheCarriedVelocityWouldReachIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult result = move(f, MOVE_X, MOVE_Z);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void walkingSpeedAwayFromTheSameWallWhileSneakingIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            double x = VELOCITY.x + (MOVE_X - VELOCITY.x) / 0.3;
            double z = VELOCITY.z + (MOVE_Z - VELOCITY.z) / 0.3;
            assertRejected(move(f, x, z), "NoSneakSlow");
        }
    }

    @Test
    void movingFurtherAlongTheWallThanTheSneakingInputReachesIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertRejected(move(f, MOVE_X, MOVE_Z - 0.005), "NoSneakSlow");
        }
    }

    /**
     * A cobweb entered on the previous tick scales this move by a quarter: the carried velocity still reaches the
     * wall, while the sneaking input turns the move away before it.
     */
    @Test
    void sneakingAwayFromTheWallOnTheTickACobwebSlowsIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            wall(f);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 1), BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 entered = new Vec3(WALL_X - HALF_WIDTH - WEB_GAP, FLOOR_Y, WEB_Z);
            f.place(entered.subtract(WEB_ENTRY_VELOCITY.x, 0, WEB_ENTRY_VELOCITY.z), WEB_ENTRY_VELOCITY);
            f.player.xRot = f.player.lastTickXRot = WEB_YAW;
            f.input(SNEAK);
            PredictionResult entering = f.tick(entered, true);
            assertTrue(exact(entering), describe(entering));

            f.input(SNEAK_FORWARD_LEFT);
            Vec3 input = sneakInput(WEB_YAW, 1, 1);
            double moveX = WEB_STUCK * (WEB_ENTRY_VELOCITY.x * GROUND_FRICTION + input.x);
            double moveZ = WEB_STUCK * (WEB_ENTRY_VELOCITY.z * GROUND_FRICTION + input.z);
            PredictionResult result = f.tick(entered.add(moveX, 0, moveZ), true);
            assertEquals(WEB_STUCK, result.getSimulationContext().getLastStuckSpeed().x);
            assertTrue(exact(result), describe(result));
        }
    }

    /** Sneaking forward-left from {@link #GAP} short of the wall; the client moves by {@code (x, z)}. */
    private static PredictionResult move(JavaTickFixture f, double x, double z) throws Exception {
        wall(f);
        double startX = WALL_X - HALF_WIDTH - GAP;
        f.place(new Vec3(startX, FLOOR_Y, START_Z), VELOCITY);
        f.player.xRot = f.player.lastTickXRot = YAW;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        f.input(SNEAK_FORWARD_LEFT);
        return f.tick(new Vec3(startX + x, FLOOR_Y, START_Z + z), true);
    }

    private static void wall(JavaTickFixture f) {
        for (int bz = -1; bz <= 1; bz++) {
            for (int by = FLOOR_Y; by <= FLOOR_Y + 1; by++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(2, by, bz), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** LivingEntity#moveRelative of a sneaking diagonal: 0.98F * 0.3F on each input axis at the 0.1F speed. */
    private static Vec3 sneakInput(float yaw, int strafe, int forward) {
        double axis = (double) (0.98F * 0.3F) * (double) 0.1F;
        float radians = yaw * (float) (Math.PI / 180.0);
        double sin = mth((long) (radians * 10430.378350470453D));
        double cos = mth((long) (radians * 10430.378350470453D + 16384.0D));
        return new Vec3(strafe * axis * cos - forward * axis * sin, 0, forward * axis * cos + strafe * axis * sin);
    }

    /** Mth#sin's table entry from 1.21.11 on. */
    private static double mth(long index) {
        return (float) StrictMath.sin((int) (index & 65535L) / 10430.378350470453D);
    }
}
