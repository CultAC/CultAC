package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

public final class ObserverBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return context.level().registry().with(block.defaultState(), "facing", context.nearestLookingDirection().name().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean wireConnectsTo(SimLevel level, int state, BlockPos pos, Direction direction) {
        return direction == facing(level, state);
    }

    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) {
        return bool(level, state, "powered") ? 15 : 0;
    }

    @Override
    public int signal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return facing(level, state) == direction ? ownSignal(level, state, pos) : 0;
    }

    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return signal(level, state, pos, direction);
    }
}
