package ac.cult.blocksim.engine;

import ac.cult.blocksim.interaction.PlacementContext;

/** Applies the stack's components to the client block entity, before setPlacedBy. */
@FunctionalInterface
public interface BlockEntityComponentWriter {
    void apply(PlacementContext context);
}
