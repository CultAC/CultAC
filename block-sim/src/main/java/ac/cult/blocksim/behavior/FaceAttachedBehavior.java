package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

public class FaceAttachedBehavior extends BlockBehavior {
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        return SupportRules.canAttach(level, pos, connectedDirection(level, state).opposite());
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level();
        for (Direction direction : context.nearestLookingDirections()) {
            int state = level.registry().with(block.defaultState(), "face", direction.horizontal() ? "wall" : direction == Direction.UP ? "ceiling" : "floor");
            Direction facing = direction.horizontal() ? direction.opposite() : context.horizontalDirection();
            state = level.registry().with(state, "facing", facing.name().toLowerCase(Locale.ROOT));
            if (level.behavior(state).canSurvive(level, state, context.clickedPos())) return state;
        }
        return -1;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return connectedDirection(level, state).opposite() == direction && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    protected static Direction connectedDirection(SimLevel level, int state) {
        return switch (level.registry().value(state, "face")) {
            case "ceiling" -> Direction.DOWN;
            case "floor" -> Direction.UP;
            default -> facing(level, state);
        };
    }
}
