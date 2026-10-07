package ac.cult.cultac.utils.inventory;

import ac.cult.cultac.protocol.value.EquipmentSlot;

/** LivingEntity#canGlideUsing, with the current native item boundary and an owned slot. */
public final class ClientGlider {
    private ClientGlider() {}

    public static boolean canUse(ac.cult.blocksim.engine.SimItemStack stack, EquipmentSlot slot) {
        return ac.cult.blocksim.data.ItemComponents.canGlide(stack, slot.getName());
    }
}
