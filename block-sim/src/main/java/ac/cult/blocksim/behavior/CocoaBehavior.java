package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;
import java.util.Set;

public final class CocoaBehavior extends BlockBehavior {
    private final Set<String> supports;
    public CocoaBehavior(Set<String> supports) { this.supports = Set.copyOf(supports); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        return supports.contains(level.registry().block(level.stateAt(pos.relative(facing(level, state)))).key());
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); int state = block.defaultState();
        for (Direction direction : context.nearestLookingDirections()) {
            if (!direction.horizontal()) continue;
            state = level.registry().with(state, "facing", direction.name().toLowerCase(Locale.ROOT));
            if (canSurvive(level, state, context.clickedPos())) return state;
        }
        return -1;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction == facing(level, state) && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
