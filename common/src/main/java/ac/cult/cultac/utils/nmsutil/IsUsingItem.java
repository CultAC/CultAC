package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.UseEffects;
import org.bukkit.inventory.ItemStack;

public class IsUsingItem {
    /** Servers older than 1.21.11 have no UseEffects item component. */
    private static final boolean USE_EFFECTS_AVAILABLE = hasUseEffects();

    public static boolean isUsingItem(CultPlayer player) {
        if (player.bukkitPlayer == null) return false;

        ItemStack activeItem = player.bukkitPlayer.getActiveItem();
        return activeItem != null && !activeItem.getType().isAir();
    }

    public static boolean isSlowDueToUsingItem(CultPlayer player) {
        ItemStack activeItem = getActiveItem(player);
        if (activeItem == null || activeItem.getType().isAir()) {
            return false;
        }
        if (USE_EFFECTS_AVAILABLE) {
            return !getUseEffects(activeItem).canSprint();
        }
        // Before the UseEffects component existed, the client slowed while using
        // items with eat, drink, or block animations.
        ItemUseAnimation animation = SpigotConversionUtil.toNmsItemStack(activeItem).getUseAnimation();
        return animation == ItemUseAnimation.EAT
                || animation == ItemUseAnimation.DRINK
                || animation == ItemUseAnimation.BLOCK;
    }

    public static float getUseItemSpeedMultiplier(CultPlayer player) {
        ItemStack activeItem = getActiveItem(player);
        if (activeItem == null || activeItem.getType().isAir()) {
            return 1.0F;
        }
        if (USE_EFFECTS_AVAILABLE) {
            return getUseEffects(activeItem).speedMultiplier();
        }
        ItemUseAnimation animation = SpigotConversionUtil.toNmsItemStack(activeItem).getUseAnimation();
        return animation == ItemUseAnimation.EAT
                || animation == ItemUseAnimation.DRINK
                || animation == ItemUseAnimation.BLOCK ? 0.2F : 1.0F;
    }

    public static void stopUseItem(CultPlayer player) {
        if (player.bukkitPlayer == null) return;
        player.bukkitPlayer.clearActiveItem();
    }

    private static ItemStack getActiveItem(CultPlayer player) {
        if (player.bukkitPlayer == null) return null;
        return player.bukkitPlayer.getActiveItem();
    }

    private static UseEffects getUseEffects(ItemStack item) {
        return SpigotConversionUtil.toNmsItemStack(item).getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT);
    }

    private static boolean hasUseEffects() {
        try {
            DataComponents.class.getField("USE_EFFECTS");
            return true;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }
}
