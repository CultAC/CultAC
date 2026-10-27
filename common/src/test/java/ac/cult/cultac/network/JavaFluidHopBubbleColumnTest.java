package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LivingEntity#travelInWater ends with jumpOutOfFluid's 0.3 hop, and LivingEntity#aiStep's
 * Entity#applyEffectsFromBlocks then runs the bubble column on that hop: the column's push is not lost to it.
 */
class JavaFluidHopBubbleColumnTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void theBubbleColumnPushesTheHop() throws Exception {
        try (var f = new JavaTickFixture()) {
            int column = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), false);
            for (int x = -1; x <= 0; x++) {
                for (int z = -1; z <= 1; z++) {
                    int fluid = x == 0 && z == 0 ? column : BlockIds.WATER.defaultState();
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), fluid);
                }
            }
            // A one-block wall the player drifts into, with free space above it for the hop.
            for (int z = -1; z <= 1; z++)
                f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, z), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(new Vec3(0.69, FLOOR_Y + 0.5, 0.5), new Vec3(0.05, 0, 0));
            f.input(NONE);

            PredictionResult drift = f.tick(new Vec3(0.7 - 1.0E-7, FLOOR_Y + 0.5, 0.5), false);
            assertTrue(exact(drift), describe(drift));

            // The offset box is above the single water layer, so isFree permits the hop, then the surface adds 0.1.
            var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carried.stream().anyMatch(v -> v.y > 0.3 + 0.06),
                    "the column's push follows the hop: carry=" + carried);
        }
    }
}
