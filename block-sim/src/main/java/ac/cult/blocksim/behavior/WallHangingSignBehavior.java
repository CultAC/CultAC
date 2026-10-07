package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class WallHangingSignBehavior extends SignBehavior {
    private final Set<String> wallHangingSigns;
    public WallHangingSignBehavior(Set<String> wallHangingSigns) { this.wallHangingSigns = Set.copyOf(wallHangingSigns); }
    private boolean canAttachTo(SimLevel level, int state, BlockPos attachPos, Direction attachFace) {
        int attachState = level.stateAt(attachPos);
        return wallHangingSigns.contains(level.registry().block(attachState).key())
            ? facing(level, attachState).axisName().equals(facing(level, state).axisName()) : level.isFaceSturdy(attachState, attachPos, attachFace);
    }
    public boolean canPlace(SimLevel level, int state, BlockPos pos) {
        Direction clockwise = facing(level, state).clockwise(), counterClockwise = facing(level, state).counterClockwise();
        return canAttachTo(level, state, pos.relative(clockwise), counterClockwise) || canAttachTo(level, state, pos.relative(counterClockwise), clockwise);
    }
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level();
        for (Direction direction : context.nearestLookingDirections()) {
            if (!direction.horizontal() || direction.axisName().equals(context.clickedFace().axisName())) continue;
            int state = level.registry().with(block.defaultState(), "facing", direction.opposite().name().toLowerCase(java.util.Locale.ROOT));
            if (canSurvive(level, state, context.clickedPos()) && canPlace(level, state, context.clickedPos())) return level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(context.clickedPos()).type().equals("minecraft:water")));
        }
        return -1;
    }
    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // canSurvive is the inherited true default; canPlace is placement-only.
        return direction.axisName().equals(facing(level, state).clockwise().axisName()) && !canSurvive(level, state, pos)
            ? level.registry().block("minecraft:air").defaultState() : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
