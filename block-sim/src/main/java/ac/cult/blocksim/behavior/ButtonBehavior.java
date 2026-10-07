package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;

public final class ButtonBehavior extends FaceAttachedBehavior {
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        if (bool(context.level(), state, "powered")) return SimInteraction.CONSUME;
        press(context.level(), state, context.clickedPos());
        return SimInteraction.SUCCESS;
    }

    private void press(SimLevel level, int state, BlockPos pos) {
        level.setBlock(pos, level.registry().with(state, "powered", "true"), 3);
        // Client neighbor notifications are empty; ticks, sound, and game events have no writes.
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
