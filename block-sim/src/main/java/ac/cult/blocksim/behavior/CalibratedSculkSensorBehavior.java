package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;

public final class CalibratedSculkSensorBehavior extends SculkSensorBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return context.level().registry().with(super.placementState(block, context), "facing", context.horizontalDirection().name().toLowerCase(java.util.Locale.ROOT));
    }
    @Override
    public int signal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return direction != facing(level, state) ? super.ownSignal(level, state, pos) : 0;
    }
}
