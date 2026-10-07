package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

public final class MossyCarpetBehavior extends BlockBehavior {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static String property(Direction direction) { return direction.name().toLowerCase(Locale.ROOT); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        int below = level.stateAt(pos.relative(Direction.DOWN));
        return bool(level, state, "bottom") ? !level.registry().facts(below).has(StateFacts.AIR)
            : level.registry().sameBlock(state, below) && bool(level, below, "bottom");
    }

    private static boolean hasFaces(SimLevel level, int state) {
        if (bool(level, state, "bottom")) return true;
        for (Direction direction : HORIZONTAL) if (!level.registry().value(state, property(direction)).equals("none")) return true;
        return false;
    }

    private static boolean canSupport(SimLevel level, BlockPos pos, Direction direction) {
        BlockPos neighbor = pos.relative(direction);
        return direction != Direction.UP && MultifaceBehavior.canAttachTo(level, direction, neighbor, level.stateAt(neighbor));
    }

    private static int updatedState(SimLevel level, int state, BlockPos pos, boolean createSides) {
        createSides |= bool(level, state, "bottom");
        for (Direction direction : HORIZONTAL) {
            String property = property(direction);
            String side = canSupport(level, pos, direction) ? (createSides ? "low" : level.registry().value(state, property)) : "none";
            if (side.equals("low")) {
                int above = level.stateAt(pos.relative(Direction.UP));
                if (level.registry().block(above).key().equals("minecraft:pale_moss_carpet")
                    && !level.registry().value(above, property).equals("none") && !bool(level, above, "bottom")) side = "tall";
                if (!bool(level, state, "bottom")) {
                    int below = level.stateAt(pos.relative(Direction.DOWN));
                    if (level.registry().block(below).key().equals("minecraft:pale_moss_carpet") && level.registry().value(below, property).equals("none")) side = "none";
                }
            }
            state = level.registry().with(state, property, side);
        }
        return state;
    }

    /** MossyCarpetBlock.isValidBonemealTarget/createTopperWithSideChance (always keep supported sides). */
    boolean canCreateTopper(SimLevel level, int state, BlockPos pos) {
        if (!bool(level, state, "bottom")) return false;
        var above = pos.relative(Direction.UP);
        int old = level.stateAt(above);
        boolean same = level.registry().sameBlock(state, old);
        if (same && bool(level, old, "bottom") || !same && !level.registry().facts(old).has(StateFacts.REPLACEABLE)) return false;
        int topper = level.registry().with(level.registry().block(state).defaultState(), "bottom", "false");
        topper = updatedState(level, topper, above, true);
        return hasFaces(level, topper) && topper != old;
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return updatedState(context.level(), block.defaultState(), context.clickedPos(), true); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (!canSurvive(level, state, pos)) return level.registry().block("minecraft:air").defaultState();
        int updated = updatedState(level, state, pos, false);
        return hasFaces(level, updated) ? updated : level.registry().block("minecraft:air").defaultState();
    }

}
