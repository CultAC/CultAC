package ac.cult.cultac.utils.inventory.slot;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.EquipmentType;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import org.bukkit.inventory.ItemStack;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;

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
    public boolean mayPlace(ItemStack p_39746_) {
        return type == EquipmentType.getEquipmentSlotForItem(p_39746_);
    }

    public boolean mayPickup(CultPlayer p_39744_) {
        ItemStack itemstack = this.getItem();
        return (itemstack.isEmpty() || p_39744_.gamemode == GameMode.CREATIVE || itemstack.getEnchantmentLevel(Enchantment.BINDING_CURSE) == 0) && super.mayPickup(p_39744_);
    }
}
