package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.StatePossibilities;

/** A successful local setBlock, in call order, including cascaded writes. */
public record BlockWrite(BlockPos pos, int oldState, int newState,
                         StatePossibilities oldPossibilities, StatePossibilities newPossibilities, WriteCondition condition) {
    public BlockWrite(BlockPos pos, int oldState, int newState) {
        this(pos, oldState, newState, StatePossibilities.exact(oldState), StatePossibilities.exact(newState), null);
    }
    public BlockWrite(BlockPos pos, StatePossibilities oldState, StatePossibilities newState) {
        this(pos, oldState.state(), newState.state(), oldState, newState, null);
    }
    public BlockWrite(BlockPos pos, StatePossibilities oldState, StatePossibilities newState, WriteCondition condition) {
        this(pos, oldState.state(), newState.state(), oldState, newState, condition);
    }
    public BlockWrite {
        if (oldState != oldPossibilities.state() || newState != newPossibilities.state()) throw new IllegalArgumentException("State and declared values disagree");
    }
}
