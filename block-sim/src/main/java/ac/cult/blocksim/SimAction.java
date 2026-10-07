package ac.cult.blocksim;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.Hand;

/** Actions already resolved to a proven client tick by the packet adapter. */
public sealed interface SimAction {
    record UseOn(Hand hand, BlockHit hit) implements SimAction { }
    record Use(Hand hand) implements SimAction { }
    /** Existing packet/check callers have already proved this tick completed destruction. */
    record DestroyBlock(BlockPos pos) implements SimAction { }
    record StartBreak(BlockPos pos, Direction face) implements SimAction { }
    record ContinueBreak(BlockPos pos, Direction face) implements SimAction { }
    record AbortBreak() implements SimAction { }
}
