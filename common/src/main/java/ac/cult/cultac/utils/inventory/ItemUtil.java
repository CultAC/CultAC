package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.SimItemStack;

/** Item operations shared by compensated inventory and platform values. */
public final class ItemUtil {
    private static final class Defaults {
        private static final ItemRegistry ITEMS = new ItemRegistry(DataTables.defaults());
    }

    private ItemUtil() {}

    public static ItemRegistry modelItems() {
        return Defaults.ITEMS;
    }

    public static InventoryItem inventoryValue(SimItemStack stack) {
        return stack == null || stack.isEmpty()
                ? InventoryItem.EMPTY
                : new InventoryItem(stack.definition().id(), stack.count());
    }

    public static SimItemStack empty() {
        return SimItemStack.EMPTY;
    }

    public static SimItemStack of(ItemDefinition item, int amount) {
        return item == null || item == ItemTypes.AIR || amount <= 0
                ? SimItemStack.EMPTY
                : modelItems().stack(item.key(), amount);
    }

    public static SimItemStack copy(SimItemStack stack) {
        return stack == null ? SimItemStack.EMPTY : stack.copy();
    }

    public static SimItemStack split(SimItemStack stack, int amount) {
        return stack == null || stack.isEmpty() || amount <= 0 ? SimItemStack.EMPTY : stack.split(amount);
    }

    public static void grow(SimItemStack stack, int amount) {
        if (stack != null && amount > 0) stack.count(Math.max(0, stack.count() + amount));
    }

    public static boolean isSameItemSameTags(SimItemStack first, SimItemStack second) {
        if (first == null || first.isEmpty()) return second == null || second.isEmpty();
        return second != null && !second.isEmpty() && first.sameItemSameComponents(second);
    }

    public static boolean isDamaged(SimItemStack stack) {
        return getDamageValue(stack) > 0;
    }

    public static int getDamageValue(SimItemStack stack) {
        return stack == null ? 0 : stack.components().integer("minecraft:damage", 0);
    }

    public static int getMaxDamage(SimItemStack stack) {
        return stack == null ? 0 : Math.max(0, stack.prototype().integer("minecraft:max_damage", 0));
    }

    public static int enchantmentLevel(SimItemStack stack, String enchantment) {
        return stack == null || stack.isEmpty()
                ? 0
                : ItemComponents.enchantments(stack.components()).getOrDefault(enchantment, 0);
    }

    public static String name(ItemDefinition item) {
        return item.key().substring(item.key().indexOf(':') + 1).toUpperCase(java.util.Locale.ROOT);
    }

    public static boolean isSword(ItemDefinition item) {
        if (item == null) return false;
        var tool = ItemComponents.tool(modelItems().defaults(item.key()));
        return tool != null && !tool.canDestroyBlocksInCreative();
    }
}
