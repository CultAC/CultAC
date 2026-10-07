package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

public final class StairBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); Direction face = context.clickedFace();
        int state = level.registry().with(block.defaultState(), "facing", context.horizontalDirection().name().toLowerCase(Locale.ROOT));
        state = level.registry().with(state, "half", face != Direction.DOWN && (face == Direction.UP || !(context.clickLocation().y() - pos.y() > 0.5)) ? "bottom" : "top");
        state = level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
        return level.registry().with(state, "shape", stairsShape(level, state, pos));
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Native water tick scheduling has no client effect.
        return direction.horizontal() ? level.registry().with(state, "shape", stairsShape(level, state, pos))
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    private static String stairsShape(SimLevel level, int state, BlockPos pos) {
        Direction facing = facing(level, state); String half = level.registry().value(state, "half");
        int behind = level.stateAt(pos.relative(facing));
        if (isStairs(level, behind) && half.equals(level.registry().value(behind, "half"))) {
            Direction behindFacing = facing(level, behind);
            if (!behindFacing.axisName().equals(facing.axisName()) && canTakeShape(level, state, pos, behindFacing.opposite())) {
                return behindFacing == facing.counterClockwise() ? "outer_left" : "outer_right";
            }
        }
        int front = level.stateAt(pos.relative(facing.opposite()));
        if (isStairs(level, front) && half.equals(level.registry().value(front, "half"))) {
            Direction frontFacing = facing(level, front);
            if (!frontFacing.axisName().equals(facing.axisName()) && canTakeShape(level, state, pos, frontFacing)) {
                return frontFacing == facing.counterClockwise() ? "inner_left" : "inner_right";
            }
        }
        return "straight";
    }

    private static boolean canTakeShape(SimLevel level, int state, BlockPos pos, Direction direction) {
        int neighbor = level.stateAt(pos.relative(direction));
        return !isStairs(level, neighbor) || facing(level, neighbor) != facing(level, state)
            || !level.registry().value(neighbor, "half").equals(level.registry().value(state, "half"));
    }
    private static boolean isStairs(SimLevel level, int state) { return isFamily(level, state, "StairBlock"); }
}
