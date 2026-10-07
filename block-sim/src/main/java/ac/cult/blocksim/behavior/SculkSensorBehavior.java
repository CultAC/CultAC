package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;

public class SculkSensorBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return context.level().registry().with(block.defaultState(), "waterlogged", Boolean.toString(context.level().fluidAt(context.clickedPos()).is("minecraft:water")));
    }
    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) {
        return number(level, state, "power");
    }

    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return direction == Direction.UP ? signal(level, state, pos, direction) : 0;
    }
}
