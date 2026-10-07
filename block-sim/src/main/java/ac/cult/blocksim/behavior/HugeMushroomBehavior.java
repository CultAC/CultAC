package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

public final class HugeMushroomBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        int state = block.defaultState(); SimLevel level = context.level();
        for (Direction direction : Direction.values()) state = level.registry().with(state, direction.name().toLowerCase(Locale.ROOT),
            Boolean.toString(!level.registry().sameBlock(state, level.stateAt(context.clickedPos().relative(direction)))));
        return state;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return level.registry().sameBlock(state, neighborState) ? level.registry().with(state, direction.name().toLowerCase(Locale.ROOT), "false")
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
