package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.*;

public final class RepeaterBehavior extends DiodeBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        int state = super.placementState(block, context);
        return context.level().registry().with(state, "locked", Boolean.toString(isLocked(context.level(), context.clickedPos(), state)));
    }
    private boolean isLocked(SimLevel level, BlockPos pos, int state) { return alternateSignal(level, pos, state, true) > 0; }
    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction == Direction.DOWN && !canSurviveOn(level, neighborPos, neighborState) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        if (!context.player().state().mayBuild()) return SimInteraction.PASS;
        context.level().setBlock(context.clickedPos(), context.level().registry().with(state, "delay", Integer.toString(number(context.level(), state, "delay") % 4 + 1)), 3);
        return SimInteraction.SUCCESS;
    }
    @Override
    public boolean wireConnectsTo(SimLevel level, int state, BlockPos pos, Direction direction) {
        Direction facing = facing(level, state);
        return facing == direction || facing.opposite() == direction;
    }
}
