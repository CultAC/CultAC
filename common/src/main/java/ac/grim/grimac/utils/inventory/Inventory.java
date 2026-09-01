package ac.grim.grimac.utils.inventory;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.inventory.AbstractContainerMenu;
import ac.grim.grimac.utils.inventory.slot.EquipmentSlot;
import ac.grim.grimac.utils.inventory.slot.ResultSlot;
import ac.grim.grimac.utils.inventory.slot.Slot;
import org.bukkit.inventory.ItemStack;
import lombok.Getter;
import org.bukkit.GameMode;
import org.bukkit.Material;

public class Inventory extends AbstractContainerMenu {
    public static final int SLOT_OFFHAND = 45;
    public static final int SLOT_BODY = 46;
    public static final int SLOT_SADDLE = 47;
    public static final int STORAGE_SIZE = 48;
    public static final int HOTBAR_OFFSET = 36;
    public static final int ITEMS_START = 9;
    public static final int ITEMS_END = 45;
    public static final int SLOT_HELMET = 4;
    public static final int SLOT_CHESTPLATE = 5;
    public static final int SLOT_LEGGINGS = 6;
    public static final int SLOT_BOOTS = 7;
    public int selected = 0;
    @Getter
    InventoryStorage inventoryStorage;

    public Inventory(GrimPlayer player, InventoryStorage inventoryStorage) {
        this.inventoryStorage = inventoryStorage;

        super.setPlayer(player);
        super.setPlayerInventory(this);

        // Result slot
        addSlot(new ResultSlot(new InventoryStorage(1), 0));
        // Crafting slots
        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(inventoryStorage, i));
        }
        for (int i = 0; i < 4; i++) {
            addSlot(new EquipmentSlot(EquipmentType.byArmorID(i), inventoryStorage, i + 4));
        }
        // Inventory slots
        for (int i = 0; i < 9 * 4; i++) {
            addSlot(new Slot(inventoryStorage, i + 9));
        }
        // Offhand
        addSlot(new Slot(inventoryStorage, 45));
    }

    public ItemStack getHelmet() {
        return inventoryStorage.getItem(SLOT_HELMET);
    }

    public ItemStack getChestplate() {
        return inventoryStorage.getItem(SLOT_CHESTPLATE);
    }

    public ItemStack getLeggings() {
        return inventoryStorage.getItem(SLOT_LEGGINGS);
    }

    public ItemStack getBoots() {
        return inventoryStorage.getItem(SLOT_BOOTS);
    }

    public ItemStack getOffhand() {
        return inventoryStorage.getItem(SLOT_OFFHAND);
    }

    public boolean hasItemType(Material item) {
        for (int i = 0; i < inventoryStorage.items.length; ++i) {
            if (inventoryStorage.getItem(i).getType() == item) {
                return true;
            }
        }
        return false;
    }

    public ItemStack getHeldItem() {
        return inventoryStorage.getItem(selected + HOTBAR_OFFSET);
    }

    public void setHeldItem(ItemStack item) {
        inventoryStorage.setItem(selected + HOTBAR_OFFSET, item);
    }

    public ItemStack getOffhandItem() {
        return inventoryStorage.getItem(SLOT_OFFHAND);
    }

    public boolean add(ItemStack p_36055_) {
        return this.add(-1, p_36055_);
    }

    public int getFreeSlot() {
        for (int i = 0; i < VANILLA_INVENTORY_SIZE; ++i) {
            int storageSlot = storageSlotFromVanillaInventoryIndex(i);
            if (inventoryStorage.getItem(storageSlot).isEmpty()) {
                return storageSlot;
            }
        }

        return -1;
    }

    public int getSlotWithRemainingSpace(ItemStack toAdd) {
        if (this.hasRemainingSpaceForItem(getHeldItem(), toAdd)) {
            return HOTBAR_OFFSET + this.selected;
        } else if (this.hasRemainingSpaceForItem(getOffhand(), toAdd)) {
            return SLOT_OFFHAND;
        } else {
            for (int i = 0; i < VANILLA_INVENTORY_SIZE; ++i) {
                int storageSlot = storageSlotFromVanillaInventoryIndex(i);
                if (this.hasRemainingSpaceForItem(inventoryStorage.getItem(storageSlot), toAdd)) {
                    return storageSlot;
                }
            }

            return -1;
        }
    }

    private boolean hasRemainingSpaceForItem(ItemStack one, ItemStack two) {
        return !one.isEmpty() && ItemUtil.isSameItemSameTags(one, two) && one.getAmount() < one.getMaxStackSize() && one.getAmount() < this.getMaxStackSize();
    }

    private static final int VANILLA_INVENTORY_SIZE = 36;

    static int storageSlotFromVanillaInventoryIndex(int slot) {
        if (slot < 0 || slot >= VANILLA_INVENTORY_SIZE) {
            throw new IllegalArgumentException("Invalid vanilla inventory slot " + slot);
        }
        return slot < 9 ? HOTBAR_OFFSET + slot : slot;
    }

    private int addResource(ItemStack resource) {
        int i = this.getSlotWithRemainingSpace(resource);
        if (i == -1) {
            i = this.getFreeSlot();
        }

        return i == -1 ? resource.getAmount() : this.addResource(i, resource);
    }

    private int addResource(int slot, ItemStack stack) {
        int i = stack.getAmount();
        ItemStack itemstack = inventoryStorage.getItem(slot);

        if (itemstack.isEmpty()) {
            itemstack = stack.clone();
            itemstack.setAmount(0);
            inventoryStorage.setItem(slot, itemstack);
        }

        int j = i;
        if (i > itemstack.getMaxStackSize() - itemstack.getAmount()) {
            j = itemstack.getMaxStackSize() - itemstack.getAmount();
        }

        if (j > this.getMaxStackSize() - itemstack.getAmount()) {
            j = this.getMaxStackSize() - itemstack.getAmount();
        }

        if (j == 0) {
            return i;
        } else {
            i = i - j;
            ItemUtil.grow(itemstack, j);
            return i;
        }
    }

    public boolean add(int p_36041_, ItemStack p_36042_) {
        if (p_36042_.isEmpty()) {
            return false;
        } else {
            if (ItemUtil.isDamaged(p_36042_)) {
                if (p_36041_ == -1) {
                    p_36041_ = this.getFreeSlot();
                }

                if (p_36041_ >= 0) {
                    inventoryStorage.setItem(p_36041_, p_36042_.clone());
                    p_36042_.setAmount(0);
                    return true;
                } else if (player.gamemode == GameMode.CREATIVE) {
                    p_36042_.setAmount(0);
                    return true;
                } else {
                    return false;
                }
            } else {
                int i;
                do {
                    i = p_36042_.getAmount();
                    if (p_36041_ == -1) {
                        p_36042_.setAmount(this.addResource(p_36042_));
                    } else {
                        p_36042_.setAmount(this.addResource(p_36041_, p_36042_));
                    }
                } while (!p_36042_.isEmpty() && p_36042_.getAmount() < i);

                if (p_36042_.getAmount() == i && player.gamemode == GameMode.CREATIVE) {
                    p_36042_.setAmount(0);
                    return true;
                } else {
                    return p_36042_.getAmount() < i;
                }
            }
        }
    }

}
