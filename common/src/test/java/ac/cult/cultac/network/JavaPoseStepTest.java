package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** Entity#collide steps with the entity's own bounding box, which the pose sets. */
class JavaPoseStepTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.69);
    /** A bottom slab ahead, its top 1.5 below a ceiling: room for a crouching player's box, not a standing one's. */
    private static final double SLAB_TOP = FLOOR_Y + 0.5;

    @Test
    void aCrouchingPlayerStepsUnderACeilingTooLowToStand() throws Exception {
        try (var f = crouchingBeforeTheSlab()) {
            PredictionResult step = f.tick(new Vec3(START.x, SLAB_TOP, START.z + 0.1), true);
            assertTrue(exact(step), describe(step));
        }
    }

    @Test
    void aCrouchingPlayerCannotStepHigherThanTheSlab() throws Exception {
        try (var f = crouchingBeforeTheSlab()) {
            PredictionResult step = f.tick(new Vec3(START.x, SLAB_TOP + 0.1, START.z + 0.1), true);
            assertFalse(exact(step), describe(step));
        }
    }

    /** A sneaking client standing still at {@link #START} for a tick, so Player#updatePlayerPose has crouched it. */
    private static JavaTickFixture crouchingBeforeTheSlab() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 1), state("stone_slab", "type", "bottom"));
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 2, 1), BlockIds.STONE.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(START, Vec3.ZERO);
        f.input(SNEAK);
        assertTrue(exact(f.tick(START, true)));
        assertEquals(Pose.CROUCHING, f.player.pose);
        f.place(START, new Vec3(0, -0.0784000015258789, 0.1));
        return f;
    }

    private static int state(String block, String property, String value) {
        var registry = DataTables.defaults().registry();
        return registry.with(registry.block("minecraft:" + block).defaultState(), property, value);
    }
}
