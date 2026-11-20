package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A player sneaking forward-left beside a boat it never touches, turning on one tick. LocalPlayer#modifyInput scales
 * the keys by the sneaking speed and LivingEntity#travel adds that input to the carried velocity before Entity#collide,
 * which leaves the move whole.
 */
class JavaSneakTurnBesideBoatTest {
    private static final int BOAT = 100;
    private static final ServerboundPlayerInput SNEAK_FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    private static final float SNEAKING_SPEED = 0.3F;
    private static final float SPEED = 0.1F;
    // LivingEntity#travelInAir keeps 0.6F * 0.91F of the velocity on stone.
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final float YAW = -51.74F;
    // The capture's 18.6 degree turn.
    private static final float TURNED_YAW = -33.14F;
    private static final Vec3 START = new Vec3(-3.5, FLOOR_Y, 0.5);
    // Out of the player's reach throughout, within range of its move.
    private static final Vec3 BOAT_AT = new Vec3(-3.0, FLOOR_Y, 2.4);
    private static final int STEADY_TICKS = 9;
    // A stone wall whose west face is at x = 1, and a player against it with a boat behind.
    private static final int WALL_X = 1;
    private static final Vec3 BY_WALL = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 BOAT_BEHIND = new Vec3(-0.9, FLOOR_Y, 0.5);
    private static final float WALL_TURNED_YAW = 18.6F;
    private static final int WALL_STEADY_TICKS = 6;
    private static final int WALL_TURNED_TICKS = 3;

    @Test
    void turningWhileSneakingBesideABoatIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            PredictionResult turning = sneakThenTurn(f, SNEAKING_SPEED);
            assertTrue(exact(turning), describe(turning));
        }
    }

    @Test
    void turningWhileSneakingWithoutABoatIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            PredictionResult turning = sneakThenTurn(f, SNEAKING_SPEED);
            assertTrue(exact(turning), describe(turning));
        }
    }

    @Test
    void turningAtWalkingSpeedWhileSneakingBesideABoatIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            assertRejected(sneakThenTurn(f, 1.0F), "NoSneakSlow");
        }
    }

    @Test
    void slidingAlongAWallWhileSneakingBesideABoatIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            wall(f);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_BEHIND, 0, 0, 0);
            slideAlongWall(f, SNEAKING_SPEED);
        }
    }

    @Test
    void slidingAlongAWallWhileSneakingWithoutABoatIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            wall(f);
            slideAlongWall(f, SNEAKING_SPEED);
        }
    }

    @Test
    void slidingAlongAWallAtWalkingSpeedWhileSneakingBesideABoatIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            wall(f);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_BEHIND, 0, 0, 0);
            assertRejected(slideAlongWall(f, 1.0F), "NoSneakSlow");
        }
    }

    /**
     * Sneaks forward-left into the wall at yaw 0, then turns to {@link #WALL_TURNED_YAW} with {@code turningFactor}.
     * Entity#collide clips X at the wall's face and Entity#move zeroes the X velocity. Returns the first turned tick.
     */
    private static PredictionResult slideAlongWall(JavaTickFixture f, float turningFactor) throws Exception {
        f.place(BY_WALL, Vec3.ZERO);
        f.player.xRot = f.player.lastTickXRot = 0.0F;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        f.input(SNEAK_FORWARD_LEFT);
        double faceX = WALL_X - 0.3;
        Vec3 position = BY_WALL;
        Vec3 velocity = Vec3.ZERO;
        PredictionResult firstTurned = null;
        for (int tick = 0; tick < WALL_STEADY_TICKS + WALL_TURNED_TICKS; tick++) {
            boolean turned = tick >= WALL_STEADY_TICKS;
            float yaw = turned ? WALL_TURNED_YAW : 0.0F;
            f.player.xRot = yaw;
            Vec3 delta = velocity.add(
                    ClientMoveInput.of(f, SNEAK_FORWARD_LEFT, turned ? turningFactor : SNEAKING_SPEED, SPEED, yaw));
            boolean clipX = position.x + delta.x > faceX;
            position = new Vec3(clipX ? faceX : position.x + delta.x, FLOOR_Y, position.z + delta.z);
            PredictionResult result = f.tick(position, true);
            if (tick == WALL_STEADY_TICKS) firstTurned = result;
            if (turningFactor == SNEAKING_SPEED || !turned) {
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            velocity = new Vec3(clipX ? 0.0 : delta.x * GROUND_FRICTION, 0.0, delta.z * GROUND_FRICTION);
        }
        return firstTurned;
    }

    private static void wall(JavaTickFixture f) {
        for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(WALL_X, y, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** Sneaks at {@link #YAW} to a steady speed, then turns to {@link #TURNED_YAW} with {@code turningFactor}. */
    private static PredictionResult sneakThenTurn(JavaTickFixture f, float turningFactor) throws Exception {
        f.place(START, Vec3.ZERO);
        f.player.xRot = f.player.lastTickXRot = YAW;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        f.input(SNEAK_FORWARD_LEFT);
        Vec3 position = START;
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < STEADY_TICKS; tick++) {
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SNEAK_FORWARD_LEFT, SNEAKING_SPEED, SPEED, YAW));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult steady = f.tick(position, true);
            assertTrue(exact(steady), "tick " + tick + ": " + describe(steady));
            velocity = new Vec3(delta.x * GROUND_FRICTION, 0.0, delta.z * GROUND_FRICTION);
        }
        f.player.xRot = TURNED_YAW;
        Vec3 delta = velocity.add(ClientMoveInput.of(f, SNEAK_FORWARD_LEFT, turningFactor, SPEED, TURNED_YAW));
        return f.tick(position.add(delta.x, 0.0, delta.z), true);
    }
}
