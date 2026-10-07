package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class SnowyBehavior extends BlockBehavior {
    private final Set<String> snowTag;
    public SnowyBehavior(Set<String> snowTag) { this.snowTag = Set.copyOf(snowTag); }

    private boolean isSnowySetting(SimLevel level, int state) { return snowTag.contains(level.registry().block(state).key()); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return context.level().registry().with(block.defaultState(), "snowy",
            Boolean.toString(isSnowySetting(context.level(), context.level().stateAt(context.clickedPos().relative(Direction.UP)))));
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction == Direction.UP
            ? level.registry().with(state, "snowy", Boolean.toString(isSnowySetting(level, neighborState)))
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
