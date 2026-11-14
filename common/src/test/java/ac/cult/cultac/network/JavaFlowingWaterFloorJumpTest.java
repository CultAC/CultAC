package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A player standing on the floor under a layer of flowing water: LivingEntity#aiStep runs jumpFromGround only while
 * the water height (FlowingFluid#getOwnHeight, amount / 9) is at most Entity#getFluidJumpThreshold's 0.4, and
 * jumpInLiquid's 0.04 above it.
 */
class JavaFlowingWaterFloorJumpTest {
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double GROUND_JUMP = 0.41999998688697815;
    private static final double LIQUID_JUMP = -0.005 + 0.04;

    /** Levels 3 and 4 hold 5/9 and 4/9 of a block: above the threshold. */
    @ParameterizedTest
    @ValueSource(strings = {"3", "4"})
    void aFlowingFloorAboveTheThresholdTakesOnlyTheLiquidJump(String level) throws Exception {
        try (var f = flowingFloor(level)) {
            PredictionResult ground = jump(f, GROUND_JUMP);
            assertFalse(exact(ground), "level " + level + ": " + describe(ground));
        }
        try (var f = flowingFloor(level)) {
            PredictionResult liquid = jump(f, LIQUID_JUMP);
            assertTrue(exact(liquid), "level " + level + ": " + describe(liquid));
        }
    }

    /** Levels 5 and 6 hold 3/9 and 2/9 of a block: at most the threshold. */
    @ParameterizedTest
    @ValueSource(strings = {"5", "6"})
    void aFlowingFloorWithinTheThresholdTakesTheGroundJump(String level) throws Exception {
        try (var f = flowingFloor(level)) {
            PredictionResult ground = jump(f, GROUND_JUMP);
            assertTrue(exact(ground), "level " + level + ": " + describe(ground));
        }
    }

    /** Flowing water of {@code level} on every cell of the floor layer, so no cell's flow pushes the player. */
    private static JavaTickFixture flowingFloor(String level) throws Exception {
        var f = new JavaTickFixture();
        int water = REGISTRY.with(BlockIds.WATER.defaultState(), "level", level);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), water);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    /** A still tick standing on the floor, then the first tick holding jump, rising by {@code rise}. */
    private static PredictionResult jump(JavaTickFixture f, double rise) {
        Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
        f.place(start, new Vec3(0.0, -0.005, 0.0));
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        PredictionResult still = f.tick(start, true);
        assertTrue(exact(still), "standing: " + describe(still));
        f.input(JUMP);
        return f.tick(start.add(0.0, rise, 0.0), false);
    }
}
