package ac.grim.grimac.utils.inventory;

import org.bukkit.inventory.ItemStack;

public class InventoryStorage {
    protected ItemStack[] items;
    int size;

    public InventoryStorage(int size) {
        this.items = new ItemStack[size];
        this.size = size;

        for (int i = 0; i < size; i++) {
            items[i] = ItemStack.empty();
        }
    }

    public int getSize() {
        return size;
    }

    public void setItem(int item, ItemStack stack) {
        items[item] = stack == null ? ItemStack.empty() : stack;
    }

    public ItemStack getItem(int index) {
        return items[index];
    }

    public ItemStack removeItem(int slot, int amount) {
        return slot >= 0 && slot < items.length && !items[slot].isEmpty() && amount > 0
                ? ItemUtil.split(items[slot], amount)
                : ItemStack.empty();
    }

    public int getMaxStackSize() {
        return 64;
    }
}
