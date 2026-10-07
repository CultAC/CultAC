package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;

public final class HangingMossBehavior extends BlockBehavior {
    private boolean canStay(SimLevel level, int state, BlockPos pos) {
        BlockPos above = pos.relative(Direction.UP); int neighbor = level.stateAt(above);
        return MultifaceBehavior.canAttachTo(level, Direction.UP, above, neighbor) || level.registry().sameBlock(state, neighbor);
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return canStay(level, state, pos); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Unsupported moss only schedules a server tick before choosing the client tip state.
        return level.registry().with(state, "tip", Boolean.toString(!level.registry().sameBlock(state, level.stateAt(pos.relative(Direction.DOWN)))));
    }
}
