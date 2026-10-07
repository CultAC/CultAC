package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;

public final class ShelfMushroomBehavior extends BlockBehavior {
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        return SupportRules.fullFace(level, pos, facing(level, state));
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        // Both families use the same ordered horizontal candidates and full-face support.
        return BasicBlockRules.wallPlacementState(block, context);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction == facing(level, state).opposite() && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
