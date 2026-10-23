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
 * A sneaking player whose carried velocity already reaches into a wall: Entity#collide clips the velocity plus the
 * input, so the input on the clipped axis may point away from the wall by as much as the wall absorbs.
 */
class JavaSneakFlushWallTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, false, true, false);
    private static final float YAW = -1217.3937F;
    private static final double WALL_Z = 2.0;
    private static final double HALF_WIDTH = 0.6F / 2.0F;
    private static final double START_X = 0.5;
    private static final Vec3 VELOCITY = new Vec3(0.023410487732574408, -0.0784000015258789, 0.02552740487386616);
    private static final double GAP = 0.006373553713771685;
    // The client's x movement: the carried velocity plus sneaking forward-right input at this yaw.
    private static final double MOVE_X = 0.06495221902275716;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    // Entity#makeStuckInBlock: a cobweb scales the next move by 0.25 horizontally.
    private static final double WEB_STUCK = 0.25;
    private static final float WEB_YAW = -157.5F;
    private static final Vec3 WEB_ENTRY_VELOCITY = new Vec3(0.022, -0.0784000015258789, 0.078);
    private static final double WEB_GAP = 0.005;
    private static final double WEB_X = 0.71;

    @Test
    void sneakingAlongAWallTheCarriedVelocityReachesIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            var result = slide(f, MOVE_X);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void walkingSpeedAlongTheSameWallWhileSneakingIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertRejected(slide(f, VELOCITY.x + (MOVE_X - VELOCITY.x) / 0.3), "NoSneakSlow");
        }
    }

    @Test
    void movingFurtherAlongTheWallThanTheSneakingInputReachesIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertRejected(slide(f, VELOCITY.x + 0.045), "NoSneakSlow");
        }
    }

    /**
     * A cobweb entered on the previous tick scales this move by a quarter: the carried velocity still reaches the
     * wall, while the sneaking input points away from it.
     */
    @Test
    void sneakingAlongTheWallOnTheTickACobwebSlowsIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            wall(f);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 1), BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 entered = new Vec3(WEB_X, FLOOR_Y, WALL_Z - HALF_WIDTH - WEB_GAP);
            f.place(entered.subtract(WEB_ENTRY_VELOCITY.x, 0, WEB_ENTRY_VELOCITY.z), WEB_ENTRY_VELOCITY);
            f.player.xRot = f.player.lastTickXRot = WEB_YAW;
            f.input(SNEAK);
            PredictionResult entering = f.tick(entered, true);
            assertTrue(exact(entering), describe(entering));

            f.input(SNEAK_FORWARD_RIGHT);
            double carriedX = WEB_ENTRY_VELOCITY.x * GROUND_FRICTION;
            double moveX = WEB_STUCK * (carriedX + sneakInput(WEB_YAW, -1, 1).x);
            PredictionResult result = f.tick(new Vec3(WEB_X + moveX, FLOOR_Y, WALL_Z - HALF_WIDTH), true);
            assertEquals(WEB_STUCK, result.getSimulationContext().getLastStuckSpeed().x);
            assertTrue(exact(result), describe(result));
        }
    }

    /** Sneaking forward-right from {@link #GAP} short of the wall; the client ends flush having moved {@code x}. */
    private static PredictionResult slide(JavaTickFixture f, double x) throws Exception {
        wall(f);
        double startZ = WALL_Z - HALF_WIDTH - GAP;
        f.place(new Vec3(START_X, FLOOR_Y, startZ), VELOCITY);
        f.player.xRot = f.player.lastTickXRot = YAW;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        f.input(SNEAK_FORWARD_RIGHT);
        return f.tick(new Vec3(START_X + x, FLOOR_Y, WALL_Z - HALF_WIDTH), true);
    }

    private static void wall(JavaTickFixture f) {
        for (int bx = -1; bx <= 1; bx++) {
            for (int by = FLOOR_Y; by <= FLOOR_Y + 1; by++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(bx, by, 2), BlockIds.STONE.defaultState());
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
