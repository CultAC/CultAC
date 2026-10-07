package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

public final class GrindstoneBehavior extends FaceAttachedBehavior {
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return true; }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        // Menus and stats are guarded by !level.isClientSide().
        return SimInteraction.SUCCESS;
    }
}
