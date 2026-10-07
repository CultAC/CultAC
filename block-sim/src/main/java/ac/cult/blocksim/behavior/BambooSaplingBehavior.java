package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

public final class BambooSaplingBehavior extends BlockBehavior {
    private final Set<String> supports;
    public BambooSaplingBehavior(Set<String> supports) { this.supports = Set.copyOf(supports); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return supports.contains(level.registry().block(level.stateAt(pos.relative(Direction.DOWN))).key()); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (!canSurvive(level, state, pos)) return level.registry().block("minecraft:air").defaultState();
        return direction == Direction.UP && level.registry().block(neighborState).key().equals("minecraft:bamboo") ? level.registry().block("minecraft:bamboo").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
