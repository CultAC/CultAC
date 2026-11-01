package ac.cult.cultac.utils.inventory.slot;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.utils.inventory.EquipmentType;
import ac.cult.cultac.utils.inventory.InventoryStorage;

public class EquipmentSlot extends Slot {
    EquipmentType type;

    public EquipmentSlot(EquipmentType type, InventoryStorage menu, int slot) {
        super(menu, slot);
        this.type = type;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean mayPlace(SimItemStack p_39746_) {
        return type == EquipmentType.getEquipmentSlotForItem(p_39746_);
    }
}
