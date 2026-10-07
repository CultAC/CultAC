package ac.cult.cultac.utils.inventory.slot;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.InventoryStorage;

public class ResultSlot extends Slot {

    public ResultSlot(InventoryStorage container, int slot) {
        super(container, slot);
    }

    @Override
    public boolean mayPlace(SimItemStack p_40178_) {
        return false;
    }

    @Override
    public void onTake(CultPlayer player, SimItemStack p_150639_) {
        // Resync the player's inventory
    }
}
