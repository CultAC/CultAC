package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

public final class SoulFireBehavior extends BaseFireBehavior {
    public SoulFireBehavior(Set<String> soulFireBases) { super(soulFireBases); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return soulFireBase(level, level.stateAt(pos.relative(Direction.DOWN))); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return canSurvive(level, state, pos) ? level.registry().block(state).defaultState() : level.registry().block("minecraft:air").defaultState();
    }
}
