package ac.grim.grimac.utils.inventory;

import ac.grim.grimac.network.protocol.util.SpigotConversionUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.equipment.Equippable;
import org.bukkit.inventory.ItemStack;

public enum EquipmentType {
    MAINHAND,
    OFFHAND,
    FEET,
    LEGS,
    CHEST,
    HEAD;

    public static EquipmentType byArmorID(int id) {
        switch (id) {
            case 0:
                return HEAD;
            case 1:
                return CHEST;
            case 2:
                return LEGS;
            case 3:
                return FEET;
            default:
                return MAINHAND;
        }
    }

    public static EquipmentType getEquipmentSlotForItem(ItemStack p_147234_) {
        if (p_147234_ == null || p_147234_.isEmpty()) {
            return MAINHAND;
        }

        Equippable equippable = SpigotConversionUtil.toNmsItemStack(p_147234_).get(DataComponents.EQUIPPABLE);
        return equippable == null ? MAINHAND : fromNmsSlot(equippable.slot());
    }

    private static EquipmentType fromNmsSlot(net.minecraft.world.entity.EquipmentSlot slot) {
        return switch (slot) {
            case OFFHAND -> OFFHAND;
            case FEET -> FEET;
            case LEGS -> LEGS;
            case CHEST -> CHEST;
            case HEAD -> HEAD;
            default -> MAINHAND;
        };
    }
}
