package ac.cult.cultac.utils.inventory.slot;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import org.bukkit.inventory.ItemStack;

public class Slot {
    public final int inventoryStorageSlot;
    public int slotListIndex;
    InventoryStorage container;

    public Slot(InventoryStorage container, int slot) {
        this.container = container;
        this.inventoryStorageSlot = slot;
    }

    public ItemStack getItem() {
        return container.getItem(inventoryStorageSlot);
    }

    public boolean isBackedBy(InventoryStorage storage) {
        return this.container == storage;
    }

    public int getContainerSlot() {
        return inventoryStorageSlot;
    }

    public boolean mayPlace(ItemStack itemstack) {
        return true;
    }

    public void set(ItemStack itemstack2) {
        container.setItem(inventoryStorageSlot, itemstack2);
    }

    public int getMaxStackSize() {
        return container.getMaxStackSize();
    }

    public int getMaxStackSize(ItemStack itemstack2) {
        return Math.min(itemstack2.getMaxStackSize(), getMaxStackSize());
    }

    public ItemStack remove(int p_40227_) {
        return this.container.removeItem(this.inventoryStorageSlot, p_40227_);
    }

    public boolean mayPickup(CultPlayer p_40228_) {
        return true;
    }

    public void onTake(CultPlayer player, ItemStack stack) {
    }
}
