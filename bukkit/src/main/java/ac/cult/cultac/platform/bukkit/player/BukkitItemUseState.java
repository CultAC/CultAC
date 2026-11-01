package ac.cult.cultac.platform.bukkit.player;

import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.ItemUseState;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.UseEffects;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

/** Interprets native UseEffects and legacy use animations at the Paper boundary. */
final class BukkitItemUseState {
    private static final DataComponentType<UseEffects> EFFECTS = component();

    private BukkitItemUseState() {}

    static ItemUseState read(Player player) {
        ItemStack stack = CraftItemStack.asNMSCopy(player.getActiveItem());
        if (stack.isEmpty()) return ItemUseState.NONE;
        Hand hand = player.getHandRaised() == EquipmentSlot.OFF_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
        if (EFFECTS != null) {
            UseEffects effects = stack.getOrDefault(EFFECTS, UseEffects.DEFAULT);
            return new ItemUseState(true, hand, effects.canSprint(), effects.speedMultiplier());
        }
        ItemUseAnimation animation = stack.getUseAnimation();
        boolean slow = animation == ItemUseAnimation.EAT
                || animation == ItemUseAnimation.DRINK
                || animation == ItemUseAnimation.BLOCK;
        return new ItemUseState(true, hand, !slow, slow ? 0.2F : 1.0F);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataComponentType<UseEffects> component() {
        try {
            return (DataComponentType)
                    DataComponents.class.getField("USE_EFFECTS").get(null);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }
}
