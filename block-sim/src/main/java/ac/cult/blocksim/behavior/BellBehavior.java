package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Locale;

public final class BellBehavior extends BlockBehavior {
    private final SupportRules support;
    public BellBehavior(SupportRules support) { this.support = support; }

    private static Direction connectedDirection(SimLevel level, int state) {
        return switch (level.registry().value(state, "attachment")) {
            case "floor" -> Direction.UP;
            case "ceiling" -> Direction.DOWN;
            default -> facing(level, state).opposite();
        };
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        Direction direction = connectedDirection(level, state).opposite();
        return direction == Direction.UP ? support.canSupportCenter(level, pos.relative(Direction.UP), Direction.DOWN) : SupportRules.canAttach(level, pos, direction);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); Direction face = context.clickedFace();
        if (!face.horizontal()) {
            int state = level.registry().with(block.defaultState(), "attachment", face == Direction.DOWN ? "ceiling" : "floor");
            state = level.registry().with(state, "facing", context.horizontalDirection().name().toLowerCase(Locale.ROOT));
            return canSurvive(level, state, pos) ? state : -1;
        }
        boolean doubleAttached = face.axisName().equals("x")
            && sturdy(level, pos.relative(Direction.WEST), Direction.EAST) && sturdy(level, pos.relative(Direction.EAST), Direction.WEST)
            || face.axisName().equals("z") && sturdy(level, pos.relative(Direction.NORTH), Direction.SOUTH) && sturdy(level, pos.relative(Direction.SOUTH), Direction.NORTH);
        int state = level.registry().with(block.defaultState(), "facing", face.opposite().name().toLowerCase(Locale.ROOT));
        state = level.registry().with(state, "attachment", doubleAttached ? "double_wall" : "single_wall");
        if (canSurvive(level, state, pos)) return state;
        state = level.registry().with(state, "attachment", sturdy(level, pos.relative(Direction.DOWN), Direction.UP) ? "floor" : "ceiling");
        return canSurvive(level, state, pos) ? state : -1;
    }

    private static boolean sturdy(SimLevel level, BlockPos pos, Direction direction) { return level.isFaceSturdy(level.stateAt(pos), pos, direction); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        String attachment = level.registry().value(state, "attachment"); Direction connected = connectedDirection(level, state).opposite();
        if (connected == direction && !canSurvive(level, state, pos) && !attachment.equals("double_wall")) return level.registry().block("minecraft:air").defaultState();
        if (direction.axisName().equals(facing(level, state).axisName())) {
            if (attachment.equals("double_wall") && !level.isFaceSturdy(neighborState, neighborPos, direction)) {
                return level.registry().with(level.registry().with(state, "attachment", "single_wall"), "facing", direction.opposite().name().toLowerCase(Locale.ROOT));
            }
            if (attachment.equals("single_wall") && connected.opposite() == direction && level.isFaceSturdy(neighborState, neighborPos, facing(level, state))) {
                return level.registry().with(state, "attachment", "double_wall");
            }
        }
        return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    private static boolean properHit(SimLevel level, int state, Direction direction, double clickY) {
        if (!direction.horizontal() || clickY > 0.8124F) return false;
        return switch (level.registry().value(state, "attachment")) {
            case "floor" -> facing(level, state).axisName().equals(direction.axisName());
            case "single_wall", "double_wall" -> !facing(level, state).axisName().equals(direction.axisName());
            case "ceiling" -> true;
            default -> false;
        };
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        // onHit returns true for a proper hit even though client attemptToRing has no effect.
        return properHit(context.level(), state, context.clickedFace(), context.clickLocation().y() - context.clickedPos().y()) ? SimInteraction.SUCCESS : SimInteraction.PASS;
    }
}
