package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.inventory.inventory.AbstractContainerMenu;
import ac.cult.cultac.utils.inventory.slot.EquipmentSlot;
import ac.cult.cultac.utils.inventory.slot.ResultSlot;
import ac.cult.cultac.utils.inventory.slot.Slot;
import lombok.Getter;

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

    public Inventory(CultPlayer player, InventoryStorage inventoryStorage) {
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

    public SimItemStack getHelmet() {
        return inventoryStorage.getItem(SLOT_HELMET);
    }

    public SimItemStack getChestplate() {
        return inventoryStorage.getItem(SLOT_CHESTPLATE);
    }

    public SimItemStack getLeggings() {
        return inventoryStorage.getItem(SLOT_LEGGINGS);
    }

    public SimItemStack getBoots() {
        return inventoryStorage.getItem(SLOT_BOOTS);
    }

    public SimItemStack getOffhand() {
        return inventoryStorage.getItem(SLOT_OFFHAND);
    }

    public boolean hasItemType(ItemDefinition item) {
        for (int i = 0; i < inventoryStorage.items.length; ++i) {
            if (inventoryStorage.getItem(i).getItem() == item) {
                return true;
            }
        }
        return false;
    }

    public SimItemStack getHeldItem() {
        return inventoryStorage.getItem(selected + HOTBAR_OFFSET);
    }

    public void setHeldItem(SimItemStack item) {
        inventoryStorage.setItem(selected + HOTBAR_OFFSET, item);
    }

    public SimItemStack getOffhandItem() {
        return inventoryStorage.getItem(SLOT_OFFHAND);
    }

    public boolean add(SimItemStack p_36055_) {
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

    public int getSlotWithRemainingSpace(SimItemStack toAdd) {
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

    private boolean hasRemainingSpaceForItem(SimItemStack one, SimItemStack two) {
        return !one.isEmpty()
                && ItemUtil.isSameItemSameTags(one, two)
                && one.getCount() < one.getMaxStackSize()
                && one.getCount() < this.getMaxStackSize();
    }

    private static final int VANILLA_INVENTORY_SIZE = 36;

    static int storageSlotFromVanillaInventoryIndex(int slot) {
        if (slot < 0 || slot >= VANILLA_INVENTORY_SIZE) {
            throw new IllegalArgumentException("Invalid vanilla inventory slot " + slot);
        }
        return slot < 9 ? HOTBAR_OFFSET + slot : slot;
    }

    private int addResource(SimItemStack resource) {
        int i = this.getSlotWithRemainingSpace(resource);
        if (i == -1) {
            i = this.getFreeSlot();
        }

        return i == -1 ? resource.getCount() : this.addResource(i, resource);
    }

    private int addResource(int slot, SimItemStack stack) {
        int i = stack.getCount();
        SimItemStack itemstack = inventoryStorage.getItem(slot);

        if (itemstack.isEmpty()) {
            itemstack = stack.copy();
            itemstack.setCount(0);
            inventoryStorage.setItem(slot, itemstack);
        }

        int j = i;
        if (i > itemstack.getMaxStackSize() - itemstack.getCount()) {
            j = itemstack.getMaxStackSize() - itemstack.getCount();
        }

        if (j > this.getMaxStackSize() - itemstack.getCount()) {
            j = this.getMaxStackSize() - itemstack.getCount();
        }

        if (j == 0) {
            return i;
        } else {
            i = i - j;
            ItemUtil.grow(itemstack, j);
            return i;
        }
    }

    public boolean add(int p_36041_, SimItemStack p_36042_) {
        if (p_36042_.isEmpty()) {
            return false;
        } else {
            if (ItemUtil.isDamaged(p_36042_)) {
                if (p_36041_ == -1) {
                    p_36041_ = this.getFreeSlot();
                }

                if (p_36041_ >= 0) {
                    inventoryStorage.setItem(p_36041_, p_36042_.copy());
                    p_36042_.setCount(0);
                    return true;
                } else if (player.gamemode == GameMode.CREATIVE) {
                    p_36042_.setCount(0);
                    return true;
                } else {
                    return false;
                }
            } else {
                int i;
                do {
                    i = p_36042_.getCount();
                    if (p_36041_ == -1) {
                        p_36042_.setCount(this.addResource(p_36042_));
                    } else {
                        p_36042_.setCount(this.addResource(p_36041_, p_36042_));
                    }
                } while (!p_36042_.isEmpty() && p_36042_.getCount() < i);

                if (p_36042_.getCount() == i && player.gamemode == GameMode.CREATIVE) {
                    p_36042_.setCount(0);
                    return true;
                } else {
                    return p_36042_.getCount() < i;
                }
            }
        }
    }
}
