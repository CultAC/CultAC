package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;
import java.util.Set;

public final class ChorusPlantBehavior extends BlockBehavior {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private final Set<String> supports;
    public ChorusPlantBehavior(Set<String> supports) { this.supports = Set.copyOf(supports); }
    private boolean connects(SimLevel level, int state, int neighbor, Direction direction) {
        String key = level.registry().block(neighbor).key();
        return level.registry().sameBlock(state, neighbor) || key.equals("minecraft:chorus_flower") || direction == Direction.DOWN && supports.contains(key);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return withConnections(context.level(), context.clickedPos(), block.defaultState()); }

    private int withConnections(SimLevel level, BlockPos pos, int state) {
        for (Direction direction : Direction.values()) state = level.registry().withIfValid(state, direction.name().toLowerCase(Locale.ROOT),
            Boolean.toString(connects(level, state, level.stateAt(pos.relative(direction)), direction)));
        return state;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // An unsupported plant only schedules a server tick; it remains unchanged on the client.
        return !canSurvive(level, state, pos) ? super.updateShape(level, state, pos, direction, neighborPos, neighborState)
            : level.registry().with(state, direction.name().toLowerCase(Locale.ROOT), Boolean.toString(connects(level, state, neighborState, direction)));
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        int below = level.stateAt(pos.relative(Direction.DOWN));
        boolean blocked = !level.registry().facts(level.stateAt(pos.relative(Direction.UP))).has(StateFacts.AIR) && !level.registry().facts(below).has(StateFacts.AIR);
        for (Direction direction : HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (level.registry().sameBlock(state, level.stateAt(neighbor))) {
                if (blocked) return false;
                int neighborBelow = level.stateAt(neighbor.relative(Direction.DOWN));
                if (level.registry().sameBlock(state, neighborBelow) || supports.contains(level.registry().block(neighborBelow).key())) return true;
            }
        }
        return level.registry().sameBlock(state, below) || supports.contains(level.registry().block(below).key());
    }
}
