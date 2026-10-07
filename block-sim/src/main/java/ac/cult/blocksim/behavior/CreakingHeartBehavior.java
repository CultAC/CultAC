package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class CreakingHeartBehavior extends BlockBehavior {
    private final Set<String> paleOakLogs;
    public CreakingHeartBehavior(Set<String> paleOakLogs) { this.paleOakLogs = Set.copyOf(paleOakLogs); }

    private boolean hasRequiredLogs(SimLevel level, int state, BlockPos pos) {
        String axis = level.registry().value(state, "axis");
        for (Direction direction : Direction.values()) {
            if (!direction.axisName().equals(axis)) continue;
            int neighbor = level.stateAt(pos.relative(direction));
            if (!paleOakLogs.contains(level.registry().block(neighbor).key()) || !level.registry().value(neighbor, "axis").equals(axis)) return false;
        }
        return true;
    }

    private int updateState(SimLevel level, int state, BlockPos pos) {
        return hasRequiredLogs(level, state, pos) && level.registry().value(state, "creaking_heart_state").equals("uprooted")
            ? level.registry().with(state, "creaking_heart_state", level.creakingActiveAt(pos) ? "awake" : "dormant") : state;
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        int state = context.level().registry().with(block.defaultState(), "axis", context.clickedFace().axisName());
        return updateState(context.level(), state, context.clickedPos());
    }

}
