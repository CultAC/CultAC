package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

public final class SugarCaneBehavior extends BlockBehavior {
    private final Set<String> support, adjacentBlocks, adjacentFluids;
    public SugarCaneBehavior(Set<String> support, Set<String> adjacentBlocks, Set<String> adjacentFluids) {
        this.support = Set.copyOf(support); this.adjacentBlocks = Set.copyOf(adjacentBlocks); this.adjacentFluids = Set.copyOf(adjacentFluids);
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        var below = pos.relative(Direction.DOWN); int belowState = level.stateAt(below);
        if (level.registry().sameBlock(state, belowState)) return true;
        if (support.contains(level.registry().block(belowState).key())) {
            for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                var adjacent = below.relative(direction);
                if (adjacentFluids.contains(level.fluidAt(adjacent).type()) || adjacentBlocks.contains(level.registry().block(level.stateAt(adjacent)).key())) return true;
            }
        }
        return false;
    }

}
