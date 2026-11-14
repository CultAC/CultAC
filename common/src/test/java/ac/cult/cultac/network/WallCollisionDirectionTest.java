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
 * Entity#collide clips each axis only against shapes ahead of the move: a wall behind the player never shortens a
 * move away from it, and a wall ahead stops a move into it.
 */
class WallCollisionDirectionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    /** Flush against a wall filling the block at x = -1. */
    private static final Vec3 FLUSH = new Vec3(0.3, FLOOR_Y, 0.5);
    /** Centred in a one-block corridor between walls at x = -1 and x = 1. */
    private static final Vec3 CORRIDOR = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void knockbackAwayFromTheWallMovesThePlayer() throws Exception {
        try (var f = fixture(FLUSH, true, false, NONE, 0.0F)) {
            f.pongThrough(f.velocity(new Vec3(0.5, 0.0, 0.0)));
            PredictionResult result = f.tick(FLUSH.add(0.5, 0, 0), true);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0, antiKb(f));
        }
    }

    @Test
    void stayingAtTheWallUnderKnockbackAwayFromItFlags() throws Exception {
        try (var f = fixture(FLUSH, true, false, NONE, 0.0F)) {
            f.pongThrough(f.velocity(new Vec3(0.5, 0.0, 0.3)));
            PredictionResult result = f.tick(FLUSH.add(0, 0, 0.3), true);
            assertTrue(result.getOffset() > 1.0E-3, describe(result));
            assertTrue(antiKb(f) > 0, describe(result));
        }
    }

    @Test
    void takingPartOfTheKnockbackAwayFromTheWallFlags() throws Exception {
        try (var f = fixture(FLUSH, true, false, NONE, 0.0F)) {
            f.pongThrough(f.velocity(new Vec3(0.5, 0.0, 0.3)));
            PredictionResult result = f.tick(FLUSH.add(0.25, 0, 0.3), true);
            assertTrue(result.getOffset() > 1.0E-3, describe(result));
            assertTrue(antiKb(f) > 0, describe(result));
        }
    }

    @Test
    void knockbackIntoTheWallStopsAtIt() throws Exception {
        try (var f = fixture(FLUSH, true, false, NONE, 0.0F)) {
            f.pongThrough(f.velocity(new Vec3(-0.5, 0.0, 0.3)));
            PredictionResult result = f.tick(FLUSH.add(0, 0, 0.3), true);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0, antiKb(f));
        }
    }

    @Test
    void knockbackInACorridorStopsAtTheWallAhead() throws Exception {
        for (double direction : new double[] {1.0, -1.0}) {
            try (var f = fixture(CORRIDOR, true, true, NONE, 0.0F)) {
                f.pongThrough(f.velocity(new Vec3(0.5 * direction, 0.0, 0.0)));
                PredictionResult result = f.tick(CORRIDOR.add(0.2 * direction, 0, 0), true);
                assertTrue(exact(result), direction + " " + describe(result));
                assertEquals(0.0, antiKb(f));
            }
        }
    }

    @Test
    void stoppingShortOfTheCorridorWallUnderKnockbackFlags() throws Exception {
        try (var f = fixture(CORRIDOR, true, true, NONE, 0.0F)) {
            f.pongThrough(f.velocity(new Vec3(0.5, 0.0, 0.0)));
            PredictionResult result = f.tick(CORRIDOR, true);
            assertTrue(antiKb(f) > 0, describe(result));
        }
    }

    @Test
    void walkingAlongTheWallIsExact() throws Exception {
        try (var f = fixture(FLUSH, true, false, FORWARD, 0.0F)) {
            Vec3 walk = ClientMoveInput.of(f, FORWARD, 1.0F, 0.1F, 0.0F);
            PredictionResult result = f.tick(FLUSH.add(walk), true);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void walkingDiagonallyIntoTheWallSlidesAlongIt() throws Exception {
        try (var f = fixture(FLUSH, true, false, FORWARD, 45.0F)) {
            Vec3 walk = ClientMoveInput.of(f, FORWARD, 1.0F, 0.1F, 45.0F);
            assertTrue(walk.x < -0.05, "walk " + walk);
            PredictionResult result = f.tick(FLUSH.add(0, 0, walk.z), true);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void walkingDiagonallyAwayFromTheWallIsExact() throws Exception {
        try (var f = fixture(FLUSH, true, false, FORWARD, -45.0F)) {
            Vec3 walk = ClientMoveInput.of(f, FORWARD, 1.0F, 0.1F, -45.0F);
            assertTrue(walk.x > 0.05, "walk " + walk);
            PredictionResult result = f.tick(FLUSH.add(walk), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /** The client stands at the wall until it answers the trailing ping, then takes the knockback in that tick. */
    @Test
    void knockbackAwayFromTheWallAnsweredTwelveTicksLateIsExact() throws Exception {
        try (var f = fixture(FLUSH, true, false, NONE, 0.0F)) {
            int proof = f.velocity(new Vec3(0.5, 0.0, 0.3));
            for (int tick = 0; tick < 12; tick++) {
                PredictionResult waiting = f.tick(FLUSH, true);
                assertTrue(exact(waiting), tick + " " + describe(waiting));
            }
            f.pongThrough(proof);
            PredictionResult result = f.tick(FLUSH.add(0.5, 0, 0.3), true);
            assertTrue(exact(result), describe(result));
            assertEquals(0.0, antiKb(f));
        }
    }

    private static JavaTickFixture fixture(
            Vec3 start, boolean westWall, boolean eastWall, ServerboundPlayerInput input, float yaw) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        for (int y = 0; y <= 2; y++) {
            if (westWall)
                f.player.compensatedWorld.updateBlock(new BlockPos(-1, FLOOR_Y + y, 0), BlockIds.STONE.defaultState());
            if (eastWall)
                f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y + y, 0), BlockIds.STONE.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = yaw;
        f.place(start, Vec3.ZERO);
        f.input(input);
        return f;
    }

    private static double antiKb(JavaTickFixture f) {
        return f.player.checkManager.getKnockbackHandler().violations;
    }
}
