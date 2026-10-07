package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimLevel;

/** Predicate evaluation uses the client's state and full block-entity NBT, never server state. */
@FunctionalInterface
public interface AdventureRuleMatcher {
    boolean test(SimItemStack stack, String component, SimLevel level, BlockPos pos);
}
