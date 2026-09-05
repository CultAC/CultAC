package ac.cult.cultac.utils.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Tool;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

public final class ItemUtil {
    private ItemUtil() {
    }

    public static ItemStack empty() {
        return ItemStack.empty();
    }

    public static ItemStack of(Material material, int amount) {
        if (material == null || material.isAir() || amount <= 0) {
            return ItemStack.empty();
        }
        return new ItemStack(material, amount);
    }

    public static ItemStack copy(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.empty() : stack.clone();
    }

    public static ItemStack split(ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return ItemStack.empty();
        }

        int removed = Math.min(amount, stack.getAmount());
        ItemStack copy = stack.clone();
        copy.setAmount(removed);
        stack.setAmount(stack.getAmount() - removed);
        return copy;
    }

    public static void grow(ItemStack stack, int amount) {
        if (stack == null || amount <= 0) {
            return;
        }
        stack.setAmount(Math.max(0, stack.getAmount() + amount));
    }

    public static boolean isSameItemSameTags(ItemStack first, ItemStack second) {
        if (first == null || first.isEmpty()) {
            return second == null || second.isEmpty();
        }
        if (second == null || second.isEmpty()) {
            return false;
        }
        if (first.getType() != second.getType()) {
            return false;
        }
        if (first instanceof CraftItemStack || second instanceof CraftItemStack) {
            return net.minecraft.world.item.ItemStack.isSameItemSameComponents(
                    CraftItemStack.asNMSCopy(first),
                    CraftItemStack.asNMSCopy(second));
        }
        return java.util.Objects.equals(first.getItemMeta(), second.getItemMeta());
    }

    public static boolean isDamaged(ItemStack stack) {
        return stack != null && stack.getItemMeta() instanceof Damageable damageable && damageable.hasDamage();
    }

    public static int getDamageValue(ItemStack stack) {
        if (!(stack != null && stack.getItemMeta() instanceof Damageable damageable)) {
            return 0;
        }
        return damageable.getDamage();
    }

    public static int getMaxDamage(ItemStack stack) {
        return stack == null ? 0 : Math.max(0, stack.getType().getMaxDurability());
    }

    public static boolean isSword(Material type) {
        if (type == null) {
            return false;
        }

        Item item = CraftMagicNumbers.getItem(type);
        Tool tool = item == null ? null : item.components().get(DataComponents.TOOL);
        return tool != null && !tool.canDestroyBlocksInCreative();
    }
}
