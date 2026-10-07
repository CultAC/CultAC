package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;

public final class BarrierBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return context.level().registry().with(block.defaultState(), "waterlogged", Boolean.toString(context.level().fluidAt(context.clickedPos()).is("minecraft:water")));
    }
    @Override
    public boolean canPlaceLiquid(SimLevel level, int state, BlockPos pos, String fluidType, boolean creativePlayer) {
        return creativePlayer && SimpleWaterlogged.canPlaceLiquid(fluidType);
    }

    @Override
    public String pickupBlock(SimLevel level, int state, BlockPos pos, boolean creativePlayer) {
        return creativePlayer ? SimpleWaterlogged.pickupBlock(level, state, pos) : null;
    }
}
