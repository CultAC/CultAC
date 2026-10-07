package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;

public final class LeverBehavior extends FaceAttachedBehavior {
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        // Client branch computes a particle state only; pull and its world write are server-only.
        return SimInteraction.SUCCESS;
    }

    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) {
        return bool(level, state, "powered") ? 15 : 0;
    }

    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return bool(level, state, "powered") && connectedDirection(level, state) == direction ? 15 : 0;
    }
}
