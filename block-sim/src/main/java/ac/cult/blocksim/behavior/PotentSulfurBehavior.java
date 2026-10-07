package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class PotentSulfurBehavior extends BlockBehavior {
    private final Set<String> continuous, periodic;
    public PotentSulfurBehavior(Set<String> continuous, Set<String> periodic) {
        this.continuous = Set.copyOf(continuous); this.periodic = Set.copyOf(periodic);
    }

    private static boolean isSourceIfFluid(SimLevel level, int state) {
        SimFluidState fluid = SimFluidState.of(level.registry().facts(state));
        return fluid.isEmpty() || fluid.isSource();
    }

    private int validState(SimLevel level, int state, BlockPos pos) {
        if (!level.fluidAt(pos.relative(Direction.UP)).isSourceOfType("minecraft:water")) return level.registry().with(state, "potent_sulfur_state", "dry");
        int below = level.stateAt(pos.relative(Direction.DOWN)); String key = level.registry().block(below).key();
        if (continuous.contains(key) && isSourceIfFluid(level, below)) return level.registry().with(state, "potent_sulfur_state", "continuous");
        if (periodic.contains(key) && isSourceIfFluid(level, below)) {
            // Resetting the block entity's server countdown does not change client blocks or inventory.
            return level.registry().value(state, "potent_sulfur_state").equals("erupting") ? state : level.registry().with(state, "potent_sulfur_state", "dormant");
        }
        return level.registry().with(state, "potent_sulfur_state", "wet");
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return validState(context.level(), block.defaultState(), context.clickedPos()); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) { return validState(level, state, pos); }
}
