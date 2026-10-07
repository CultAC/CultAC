package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class ConcretePowderBehavior extends BlockBehavior {
    private final Set<String> water;
    public ConcretePowderBehavior(Set<String> water) { this.water = Set.copyOf(water); }

    private boolean canSolidify(SimLevel level, int state) { return water.contains(level.registry().facts(state).fluid()); }

    private boolean touchesLiquid(SimLevel level, BlockPos pos) {
        BlockPos testPos = pos;
        for (Direction direction : Direction.values()) {
            int state = level.stateAt(testPos);
            if (direction != Direction.DOWN || canSolidify(level, state)) {
                testPos = pos.relative(direction); state = level.stateAt(testPos);
                // Vanilla intentionally passes the powder position to the support query.
                if (canSolidify(level, state) && !level.isFaceSturdy(state, pos, direction.opposite())) return true;
            }
        }
        return false;
    }

    private boolean shouldSolidify(SimLevel level, BlockPos pos, int replaced) { return canSolidify(level, replaced) || touchesLiquid(level, pos); }

    private static int concrete(SimLevel level, BlockDefinition block) { return level.registry().block(block.bindings().get("ConcretePowderBlock.concrete")).defaultState(); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos();
        return shouldSolidify(level, pos, level.stateAt(pos)) ? concrete(level, block) : super.placementState(block, context);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // FallingBlock only schedules a server tick before returning the default update.
        return touchesLiquid(level, pos) ? concrete(level, level.registry().block(state)) : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
