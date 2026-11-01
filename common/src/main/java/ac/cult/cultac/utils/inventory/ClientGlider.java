package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.value.EquipmentSlot;

/** LivingEntity#canGlideUsing, with the current native item boundary and an owned slot. */
public final class ClientGlider {
    private ClientGlider() {}

    public static boolean canUse(SimItemStack stack, EquipmentSlot slot) {
        return ItemComponents.canGlide(stack, slot.getName());
    }
}
