package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

public final class CactusBehavior extends BlockBehavior {
    private final Set<String> support, lava;
    public CactusBehavior(Set<String> support, Set<String> lava) { this.support = Set.copyOf(support); this.lava = Set.copyOf(lava); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            var adjacent = pos.relative(direction);
            if (level.registry().facts(level.stateAt(adjacent)).has(StateFacts.SOLID) || lava.contains(level.fluidAt(adjacent).type())) return false;
        }
        int below = level.stateAt(pos.relative(Direction.DOWN));
        return (level.registry().sameBlock(state, below) || support.contains(level.registry().block(below).key()))
            && !level.registry().facts(level.stateAt(pos.relative(Direction.UP))).has(StateFacts.LIQUID);
    }

}
