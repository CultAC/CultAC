package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;

public final class LanternBehavior extends BlockBehavior {
    private final SupportRules support;
    public LanternBehavior(SupportRules support) { this.support = support; }

    private Direction connectedDirection(SimLevel level, int state) { return bool(level, state, "hanging") ? Direction.DOWN : Direction.UP; }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        var direction = connectedDirection(level, state).opposite();
        return support.canSupportCenter(level, pos.relative(direction), direction.opposite());
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level();
        for (Direction direction : context.nearestLookingDirections()) {
            if (direction.horizontal()) continue;
            int state = level.registry().with(block.defaultState(), "hanging", Boolean.toString(direction == Direction.UP));
            if (canSurvive(level, state, context.clickedPos())) return level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(context.clickedPos()).is("minecraft:water")));
        }
        return -1;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Fluid scheduling has no writes on the client.
        return connectedDirection(level, state).opposite() == direction && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
