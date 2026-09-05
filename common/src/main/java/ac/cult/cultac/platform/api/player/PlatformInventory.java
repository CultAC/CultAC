package ac.cult.cultac.platform.api.player;

import org.bukkit.inventory.ItemStack;

public interface PlatformInventory {
    ItemStack getStack(int bukkitSlot, int vanillaSlot);
}
