package ac.cult.cultac.utils.inventory.slot;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
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

    public boolean mayPickup(CultPlayer p_39744_) {
        SimItemStack itemstack = this.getItem();
        return (itemstack.isEmpty()
                        || p_39744_.gamemode == GameMode.CREATIVE
                        || ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(
                                        itemstack, "minecraft:binding_curse")
                                == 0)
                && super.mayPickup(p_39744_);
    }
}
