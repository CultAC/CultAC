package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimPlayer;

/** ItemUtils' client inventory transformations. */
public final class ItemResults {
    private ItemResults() { }
    public static SimItemStack filled(SimItemStack original, SimPlayer player, SimItemStack filled) { return filled(original,player,filled,true); }
    public static SimItemStack filled(SimItemStack original, SimPlayer player, SimItemStack filled, boolean limitCreative) {
        if (limitCreative && player.state().infiniteMaterials()) {
            if (!player.inventory().contains(filled)) player.inventory().add(filled);
            return original;
        }
        original.consume(1,player);
        if (original.isEmpty()) return filled;
        if (!player.inventory().add(filled)) dropOnClient(filled);
        return original;
    }
    private static void dropOnClient(SimItemStack stack) {
        // LivingEntity.drop returns null on the client, before creating an entity;
        // it does not consume or otherwise mutate this leftover stack.
    }
}
