package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Knockback into a wall the player stands against: Entity#move collides it to zero, so the client stays where it is,
 * and the tick stays exact. ServerboundClientTickEndPacket shows every tick that sent no position.
 */
class KnockbackAgainstAWallTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.7, FLOOR_Y, 0.5);
    private static final Vec3 INTO_WALL = new Vec3(0.5, 0.0, 0.0);
    private static final Vec3 LIFTING = new Vec3(0.4, 0.36, 0.0);
    private static final float WEST = 90.0F;

    @Test
    void walkingAwayInOpenSpaceFlags() throws Exception {
        try (var f = fixture(false, FORWARD)) {
            f.pongThrough(f.velocity(INTO_WALL));
            Vec3 walk = ClientMoveInput.of(f, FORWARD, 1.0F, 0.1F, WEST);
            PredictionResult result = f.tick(START.add(walk), true);
            assertTrue(antiKb(f) > 0, describe(result));
        }
    }

    /**
     * LocalPlayer#sendPosition sends StatusOnly for the new collision state and withholds the position; a move with no
     * vertical part leaves Entity#onGround false.
     */
    @Test
    void theWallStopsTheKnockbackOnATickWithoutAPosition() throws Exception {
        try (var f = fixture(true, FORWARD)) {
            f.pongThrough(f.velocity(INTO_WALL));
            f.statusTick(false, true);
            assertEquals(0.0, antiKb(f));
            assertEquals(START.x, f.player.x);
        }
    }

    @Test
    void theWallStopsTheKnockbackOnATickThatSendsThePosition() throws Exception {
        try (var f = fixture(true, NONE)) {
            f.pongThrough(f.velocity(INTO_WALL));
            PredictionResult result = f.tick(START, false);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0, antiKb(f));
        }
    }

    @Test
    void theWallStopsAnExplosionsKnockback() throws Exception {
        try (var f = fixture(true, NONE)) {
            f.explosion(INTO_WALL);
            PredictionResult result = f.tick(START, false);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /** The client answers the trailing ping in the same Minecraft#runTick that applies the motion, before ticking. */
    @Test
    void knockbackTakenAfterTwentyTicksOfLatencyIsExact() throws Exception {
        try (var f = fixture(false, NONE)) {
            int proof = f.velocity(LIFTING);
            for (int tick = 0; tick < 20; tick++) f.tickEnd();
            f.pongThrough(proof);
            PredictionResult result = f.tick(START.add(LIFTING), false);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0, antiKb(f));
        }
    }

    private static JavaTickFixture fixture(boolean wall, ServerboundPlayerInput input) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        if (wall) {
            for (int y = 0; y <= 2; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y + y, 0), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        }
        f.player.xRot = WEST;
        f.place(START, Vec3.ZERO);
        f.input(input);
        return f;
    }

    private static double antiKb(JavaTickFixture f) {
        return f.player.checkManager.getKnockbackHandler().violations;
    }
}
