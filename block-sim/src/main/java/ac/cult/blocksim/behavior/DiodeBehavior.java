package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.shapes.SupportType;
import ac.cult.blocksim.engine.Signals;
import ac.cult.blocksim.interaction.PlacementContext;

public class DiodeBehavior extends BlockBehavior {
    protected boolean canSurviveOn(SimLevel level, BlockPos pos, int state) { return level.isFaceSturdy(state, pos, Direction.UP, SupportType.RIGID); }
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { var below = pos.relative(Direction.DOWN); return canSurviveOn(level, below, level.stateAt(below)); }
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return context.level().registry().with(block.defaultState(), "facing", context.horizontalDirection().opposite().name().toLowerCase(java.util.Locale.ROOT)); }
    protected int alternateSignal(SimLevel level, BlockPos pos, int state, boolean onlyDiodes) {
        Direction direction = facing(level, state), clockwise = direction.clockwise(), counterClockwise = direction.counterClockwise();
        var signals = new Signals(level);
        return Math.max(signals.controlInputSignal(pos.relative(clockwise), clockwise, onlyDiodes), signals.controlInputSignal(pos.relative(counterClockwise), counterClockwise, onlyDiodes));
    }
    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) {
        return bool(level, state, "powered") ? outputSignal(level, pos, state) : 0;
    }

    protected int outputSignal(SimLevel level, BlockPos pos, int state) { return 15; }

    @Override
    public int signal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return facing(level, state) == direction ? ownSignal(level, state, pos) : 0;
    }

    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return signal(level, state, pos, direction);
    }
}
