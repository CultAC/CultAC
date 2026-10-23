package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.value.EquipmentSlot;

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

    public static EquipmentType getEquipmentSlotForItem(SimItemStack stack) {
        if (stack == null || stack.isEmpty()) return MAINHAND;
        var equippable = ItemComponents.equippable(stack.components());
        return equippable == null ? MAINHAND : fromSlot(EquipmentSlot.byName(equippable.slot()));
    }

    private static EquipmentType fromSlot(EquipmentSlot slot) {
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
