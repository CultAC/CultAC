package ac.cult.cultac.utils.inventory.inventory;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.slot.Slot;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractContainerMenu {
    @Setter
    protected CultPlayer player;
    @Setter
    Inventory playerInventory;
    @Getter
    List<Slot> slots = new ArrayList<>();
    @Getter
    @NotNull
    ItemStack carriedItem;

    public AbstractContainerMenu(CultPlayer player, Inventory playerInventory) {
        this.player = player;
        this.playerInventory = playerInventory;
        this.carriedItem = ItemStack.empty();
    }

    public AbstractContainerMenu() {
        this.carriedItem = ItemStack.empty();
    }

    public Slot addSlot(Slot slot) {
        slot.slotListIndex = this.slots.size();
        this.slots.add(slot);
        return slot;
    }

    public void addFourRowPlayerInventory() {
        for (int slot = Inventory.ITEMS_START; slot < Inventory.ITEMS_END; slot++) {
            addSlot(new Slot(playerInventory.getInventoryStorage(), slot));
        }
    }

    public ItemStack getCarried() {
        return getCarriedItem();
    }

    public void setCarried(ItemStack stack) {
        carriedItem = stack == null ? ItemStack.empty() : stack;
    }

    public ItemStack getPlayerInventoryItem(int slot) {
        return playerInventory.getInventoryStorage().getItem(slot);
    }

    public void setPlayerInventoryItem(int slot, ItemStack stack) {
        playerInventory.getInventoryStorage().setItem(slot, stack);
    }

    public Slot getSlot(int slotID) {
        if (slotID < 0 || slotID >= this.slots.size()) { return null; }
        return this.slots.get(slotID);
    }

    public int getMaxStackSize() {
        return 64;
    }
}
