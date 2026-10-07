package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

public final class TripWireBehavior extends BlockBehavior {
    private boolean connectsTo(SimLevel level, int state, int neighborState, Direction direction) {
        String hook = level.registry().block(state).bindings().get("TripWireBlock.hook");
        return level.registry().block(neighborState).key().equals(hook) ? facing(level, neighborState) == direction.opposite() : level.registry().sameBlock(state, neighborState);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); int state = block.defaultState();
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            state = level.registry().with(state, direction.name().toLowerCase(Locale.ROOT), Boolean.toString(connectsTo(level, state, level.stateAt(context.clickedPos().relative(direction)), direction)));
        }
        return state;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction.horizontal() ? level.registry().with(state, direction.name().toLowerCase(Locale.ROOT), Boolean.toString(connectsTo(level, state, neighborState, direction)))
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
