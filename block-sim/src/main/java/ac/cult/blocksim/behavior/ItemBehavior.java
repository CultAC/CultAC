package ac.cult.blocksim.behavior;

import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.data.ItemComponents;

/** Portable item interactions; item families receive the component-driven Item defaults. */
public interface ItemBehavior {
    SimInteraction useOn(UseContext context);
    SimInteraction use(UseContext context);

    default boolean canDestroyBlock(SimItemStack stack, SimPlayer player) {
        var tool = ItemComponents.tool(stack.components());
        return tool == null || tool.canDestroyBlocksInCreative() || !player.state().infiniteMaterials();
    }

    default int useDuration(SimItemStack stack, SimPlayer player) {
        var consumable = ItemComponents.consumable(stack.components());
        if (consumable != null) return consumable.ticks();
        return !stack.components().has("minecraft:blocks_attacks") && !stack.components().has("minecraft:kinetic_weapon") ? 0 : 72000;
    }
}
