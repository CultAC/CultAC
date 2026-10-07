package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.SimItemStack;

/** ItemStack's wrappers, including adventure predicates and after-use components. */
public interface StackActions {
    SimInteraction useOn(SimItemStack originalStack, UseContext context);
    SimInteraction use(SimItemStack originalStack, UseContext context);
}
