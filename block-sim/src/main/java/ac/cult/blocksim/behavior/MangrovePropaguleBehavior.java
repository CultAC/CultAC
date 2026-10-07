package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class MangrovePropaguleBehavior extends VegetationBehavior {
    private final Set<String> hangingSupport;

    public MangrovePropaguleBehavior(Set<String> support, Set<String> hangingSupport) {
        super(support);
        this.hangingSupport = Set.copyOf(hangingSupport);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        int state = context.level().registry().with(super.placementState(block, context), "waterlogged", Boolean.toString(context.level().fluidAt(context.clickedPos()).is("minecraft:water")));
        return context.level().registry().with(state, "age", "4");
    }

    @Override
    protected boolean mayPlaceOn(SimLevel level, int state, BlockPos pos) { return super.mayPlaceOn(level, state, pos); }

    private boolean isHanging(SimLevel level, int state) { return bool(level, state, "hanging"); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        return isHanging(level, state)
            ? hangingSupport.contains(level.registry().block(level.stateAt(pos.relative(Direction.UP))).key())
            : super.canSurvive(level, state, pos);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Native water scheduling is a no-op on the client.
        return direction == Direction.UP && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
