package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

public final class BigDripleafStemBehavior extends BlockBehavior {
    private final Set<String> supportTag;
    public BigDripleafStemBehavior(Set<String> supportTag) { this.supportTag = Set.copyOf(supportTag); }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        int below = level.stateAt(pos.relative(Direction.DOWN));
        int above = level.stateAt(pos.relative(Direction.UP));
        return (level.registry().sameBlock(state, below) || supportTag.contains(level.registry().block(below).key()))
            && (level.registry().sameBlock(state, above) || level.registry().block(above).key().equals("minecraft:big_dripleaf"));
    }
}
