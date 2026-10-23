package ac.cult.cultac.utils.inventory.slot;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.utils.inventory.InventoryStorage;

public class Slot {
    public final int inventoryStorageSlot;
    InventoryStorage container;

    public Slot(InventoryStorage container, int slot) {
        this.container = container;
        this.inventoryStorageSlot = slot;
    }

    public SimItemStack getItem() {
        return container.getItem(inventoryStorageSlot);
    }

    public boolean isBackedBy(InventoryStorage storage) {
        return this.container == storage;
    }

    public int getContainerSlot() {
        return inventoryStorageSlot;
    }

    public boolean mayPlace(SimItemStack itemstack) {
        return true;
    }

    public void set(SimItemStack itemstack2) {
        container.setItem(inventoryStorageSlot, itemstack2);
    }

    public int getMaxStackSize() {
        return container.getMaxStackSize();
    }

    public int getMaxStackSize(SimItemStack itemstack2) {
        return Math.min(itemstack2.getMaxStackSize(), getMaxStackSize());
    }
}
