package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.interaction.*;

/** Glass bottles locally drain honey and create an item; shearing is server-only. */
public final class BeehiveBehavior extends BlockBehavior {
    private final ItemRegistry items;
    public BeehiveBehavior(ItemRegistry items) { this.items = items; }
    @Override public int placementState(BlockDefinition block, PlacementContext context) {
        return context.level().registry().with(block.defaultState(), "facing", context.horizontalDirection().opposite().name().toLowerCase(java.util.Locale.ROOT));
    }
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        if (number(context.level(), state, "honey_level") < 5 || !context.stack().is("minecraft:glass_bottle")) return SimInteraction.TRY_WITH_EMPTY_HAND;
        context.stack().shrink(1);
        var honey = items.stack("minecraft:honey_bottle", 1);
        if (context.stack().isEmpty()) context.player().hand(context.hand(), honey);
        else context.player().inventory().add(honey);
        // Level.addFreshEntity returns false on the client, so occupants remain in the hive.
        context.level().setBlock(context.clickedPos(), context.level().registry().with(state, "honey_level", "0"), 3);
        return SimInteraction.SUCCESS;
    }
}
