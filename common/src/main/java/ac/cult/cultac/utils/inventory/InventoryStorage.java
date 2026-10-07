package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.engine.SimItemStack;

public class InventoryStorage {
    protected SimItemStack[] items;
    int size;

    public InventoryStorage(int size) {
        this.items = new SimItemStack[size];
        this.size = size;

        for (int i = 0; i < size; i++) {
            items[i] = SimItemStack.EMPTY;
        }
    }

    public int getSize() {
        return size;
    }

    public void setItem(int item, SimItemStack stack) {
        items[item] = stack == null ? SimItemStack.EMPTY : stack;
    }

    public SimItemStack getItem(int index) {
        return items[index];
    }

    public SimItemStack removeItem(int slot, int amount) {
        return slot >= 0 && slot < items.length && !items[slot].isEmpty() && amount > 0
                ? ItemUtil.split(items[slot], amount)
                : SimItemStack.EMPTY;
    }

    public int getMaxStackSize() {
        return 64;
    }
}
