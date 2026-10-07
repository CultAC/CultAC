package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;

/** Fence and bar families share placement and shape updates, with explicit connection predicates. */
public final class HorizontalConnectionBehavior extends BlockBehavior {
    public enum Kind { FENCE, BARS }
    private final ConnectionRules rules;
    private final Kind kind;
    public HorizontalConnectionBehavior(ConnectionRules rules, Kind kind) { this.rules = rules; this.kind = kind; }
    private boolean connects(SimLevel level, int own, int neighbor, BlockPos neighborPos, Direction face) {
        if (!rules.exception(level, neighbor) && level.isFaceSturdy(neighbor, neighborPos, face)) return true;
        String key = level.registry().block(neighbor).key();
        if (kind == Kind.BARS) return isFamily(level, neighbor, "IronBarsBlock") || rules.walls.contains(key);
        boolean sameFence = rules.fences.contains(key) && rules.woodenFences.contains(key) == rules.woodenFences.contains(level.registry().block(own).key());
        return sameFence || isFamily(level, neighbor, "FenceGateBlock") && FenceGateBehavior.connectsToDirection(level, neighbor, face);
    }
    @Override public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); var pos = context.clickedPos(); int result = block.defaultState();
        Direction[] order = kind == Kind.FENCE ? new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}
            : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction direction : order) {
            var neighbor = pos.relative(direction); int state = level.stateAt(neighbor);
            result = level.registry().with(result, direction.name().toLowerCase(Locale.ROOT), Boolean.toString(connects(level, block.defaultState(), state, neighbor, direction.opposite())));
        }
        return level.registry().with(result, "waterlogged", Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
    }
    @Override public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction.horizontal() ? level.registry().with(state, direction.name().toLowerCase(Locale.ROOT),
            Boolean.toString(connects(level, state, neighborState, neighborPos, direction.opposite()))) : state;
    }
}
