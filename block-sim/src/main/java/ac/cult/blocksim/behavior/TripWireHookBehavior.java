package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.interaction.PlacementContext;

public final class TripWireHookBehavior extends BlockBehavior {
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        Direction direction = facing(level, state);
        BlockPos support = pos.relative(direction.opposite());
        return direction.horizontal() && level.isFaceSturdy(level.stateAt(support), support, direction);
    }
    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction.opposite() == facing(level, state) && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level();
        int state = level.registry().with(level.registry().with(block.defaultState(), "powered", "false"), "attached", "false");
        for (Direction direction : context.nearestLookingDirections()) {
            if (direction.horizontal()) {
                state = level.registry().with(state, "facing", direction.opposite().name().toLowerCase(java.util.Locale.ROOT));
                if (canSurvive(level, state, context.clickedPos())) return state;
            }
        }
        return -1;
    }
    @Override
    public void setPlacedBy(SimLevel level, int state, BlockPos pos, SimPlayer player, SimItemStack stack) {
        TripWireConnections.calculateState(level, pos, state, false);
    }
    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) {
        return bool(level, state, "powered") ? 15 : 0;
    }

    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        if (!bool(level, state, "powered")) return 0;
        return facing(level, state) == direction ? 15 : 0;
    }
}
