package ac.cult.cultac.network;

import static ac.cult.cultac.network.FluidPools.FORWARD;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A fluid cell changed in the last ticks may hold either state on the client; EntityFluidInteraction#update still
 * finds the fluid in every unchanged cell of the box.
 */
class JavaRecentFluidChangeTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final BlockPos HEAD = new BlockPos(0, FLOOR_Y + 1, 0);
    private static final BlockPos FEET = new BlockPos(0, FLOOR_Y, 0);
    // LivingEntity#travel moves the input by the ground speed on stone, and by 0.02 in water and in lava.
    private static final float LAND_SPEED = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
    private static final float FLUID_SPEED = 0.02F;

    @Test
    void aChangedHeadCellLeavesTheFeetInTheFluid() throws Exception {
        for (BlockDefinition fluid : List.of(BlockIds.WATER, BlockIds.LAVA)) {
            try (var f = FluidPools.pool(fluid, FLOOR_Y + 1)) {
                f.player.compensatedWorld.updateBlock(HEAD, BlockIds.AIR.defaultState());
                PredictionResult land = walk(f, LAND_SPEED);
                assertFalse(exact(land), fluid + " " + describe(land));
            }
            try (var f = FluidPools.pool(fluid, FLOOR_Y + 1)) {
                f.player.compensatedWorld.updateBlock(HEAD, BlockIds.AIR.defaultState());
                PredictionResult swim = walk(f, FLUID_SPEED);
                assertTrue(exact(swim), fluid + " " + describe(swim));
            }
        }
    }

    @Test
    void aChangedFeetCellMayHoldEitherState() throws Exception {
        for (BlockDefinition fluid : List.of(BlockIds.WATER, BlockIds.LAVA)) {
            for (float speed : new float[] {LAND_SPEED, FLUID_SPEED}) {
                try (var f = FluidPools.pool(fluid, FLOOR_Y)) {
                    f.player.compensatedWorld.updateBlock(FEET, BlockIds.AIR.defaultState());
                    PredictionResult result = walk(f, speed);
                    assertTrue(exact(result), fluid + " " + speed + " " + describe(result));
                }
            }
        }
    }

    @Test
    void aFluidPouredAtTheFeetMayNotHaveReachedTheClient() throws Exception {
        for (BlockDefinition fluid : List.of(BlockIds.WATER, BlockIds.LAVA)) {
            for (float speed : new float[] {LAND_SPEED, FLUID_SPEED}) {
                try (var f = new JavaTickFixture()) {
                    f.player.compensatedWorld.updateBlock(FEET, fluid.defaultState());
                    PredictionResult result = walk(f, speed);
                    assertTrue(exact(result), fluid + " " + speed + " " + describe(result));
                }
            }
        }
    }

    @Test
    void anUnansweredChangeKeepsTheFluidTheClientHolds() throws Exception {
        try (var f = FluidPools.pool(BlockIds.WATER, FLOOR_Y)) {
            int transaction = f.player.sendTrailingTransaction();
            f.player.latencyUtils.addRealTimeTask(
                    transaction, () -> f.player.compensatedWorld.updateBlock(FEET, BlockIds.AIR.defaultState()));
            PredictionResult land = walk(f, LAND_SPEED);
            assertFalse(exact(land), describe(land));
        }
        try (var f = FluidPools.pool(BlockIds.WATER, FLOOR_Y)) {
            int transaction = f.player.sendTrailingTransaction();
            f.player.latencyUtils.addRealTimeTask(
                    transaction, () -> f.player.compensatedWorld.updateBlock(FEET, BlockIds.AIR.defaultState()));
            PredictionResult swim = walk(f, FLUID_SPEED);
            assertTrue(exact(swim), describe(swim));
        }
        try (var f = FluidPools.pool(BlockIds.WATER, FLOOR_Y)) {
            int transaction = f.player.sendTrailingTransaction();
            f.player.latencyUtils.addRealTimeTask(
                    transaction, () -> f.player.compensatedWorld.updateBlock(FEET, BlockIds.AIR.defaultState()));
            f.pongThrough(transaction);
            PredictionResult land = walk(f, LAND_SPEED);
            assertTrue(exact(land), describe(land));
        }
    }

    /** One forward tick from rest on the floor at {@code speed}. */
    private static PredictionResult walk(JavaTickFixture f, float speed) {
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        f.place(START, Vec3.ZERO);
        f.input(FORWARD);
        return f.tick(START.add(ClientMoveInput.of(f, FORWARD, 1.0F, speed, 0.0F)), true);
    }
}
