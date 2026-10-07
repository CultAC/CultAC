package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

public final class VineBehavior extends BlockBehavior {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static String property(Direction direction) { return direction.name().toLowerCase(Locale.ROOT); }

    private static int countFaces(SimLevel level, int state) {
        int count = bool(level, state, "up") ? 1 : 0;
        for (Direction direction : HORIZONTAL) if (bool(level, state, property(direction))) count++;
        return count;
    }

    private static boolean hasFaces(SimLevel level, int state) { return countFaces(level, state) > 0; }

    private static boolean acceptable(SimLevel level, BlockPos neighbor, Direction direction) {
        return MultifaceBehavior.canAttachTo(level, direction, neighbor, level.stateAt(neighbor));
    }

    private boolean canSupport(SimLevel level, int state, BlockPos pos, Direction direction) {
        if (direction == Direction.DOWN) return false;
        if (acceptable(level, pos.relative(direction), direction)) return true;
        if (!direction.horizontal()) return false;
        int above = level.stateAt(pos.relative(Direction.UP));
        return level.registry().sameBlock(state, above) && bool(level, above, property(direction));
    }

    private int updatedState(SimLevel level, int state, BlockPos pos) {
        BlockPos abovePos = pos.relative(Direction.UP);
        if (bool(level, state, "up")) state = level.registry().with(state, "up", Boolean.toString(acceptable(level, abovePos, Direction.DOWN)));
        for (Direction direction : HORIZONTAL) {
            if (bool(level, state, property(direction))) {
                boolean supported = canSupport(level, state, pos, direction);
                if (!supported) {
                    int above = level.stateAt(abovePos);
                    supported = level.registry().sameBlock(state, above) && bool(level, above, property(direction));
                }
                state = level.registry().with(state, property(direction), Boolean.toString(supported));
            }
        }
        return state;
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return hasFaces(level, updatedState(level, state, pos)); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (direction == Direction.DOWN) return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
        int result = updatedState(level, state, pos);
        return hasFaces(level, result) ? result : level.registry().block("minecraft:air").defaultState();
    }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        int clicked = level.stateAt(context.clickedPos());
        return level.registry().sameBlock(state, clicked) ? countFaces(level, clicked) < 5 : super.canBeReplaced(level, state, context);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); int clicked = level.stateAt(pos);
        boolean vine = level.registry().block(clicked) == block; int state = vine ? clicked : block.defaultState();
        for (Direction direction : context.nearestLookingDirections()) {
            if (direction != Direction.DOWN && !(vine && bool(level, clicked, property(direction))) && canSupport(level, state, pos, direction)) {
                return level.registry().with(state, property(direction), "true");
            }
        }
        return vine ? state : -1;
    }
}
