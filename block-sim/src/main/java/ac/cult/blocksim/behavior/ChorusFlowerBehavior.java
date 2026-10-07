package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

public final class ChorusFlowerBehavior extends BlockBehavior {
    private final Set<String> supports;
    public ChorusFlowerBehavior(Set<String> supports) { this.supports = Set.copyOf(supports); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        String plant = level.registry().block(state).bindings().get("ChorusFlowerBlock.plant");
        int below = level.stateAt(pos.relative(Direction.DOWN)); String belowKey = level.registry().block(below).key();
        if (belowKey.equals(plant) || supports.contains(belowKey)) return true;
        if (!level.registry().facts(below).has(StateFacts.AIR)) return false;
        boolean oneNeighbor = false;
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            int neighbor = level.stateAt(pos.relative(direction));
            if (level.registry().block(neighbor).key().equals(plant)) {
                if (oneNeighbor) return false;
                oneNeighbor = true;
            } else if (!level.registry().facts(neighbor).has(StateFacts.AIR)) return false;
        }
        return oneNeighbor;
    }

}
