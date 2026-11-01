package ac.cult.cultac.utils.inventory.inventory;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.slot.Slot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

public abstract class AbstractContainerMenu {
    @Setter
    protected CultPlayer player;

    @Setter
    Inventory playerInventory;

    @Getter
    List<Slot> slots = new ArrayList<>();

    @NotNull
    SimItemStack carriedItem;

    private int quickcraftStatus;
    private int quickcraftType = -1;
    private final Set<Slot> quickcraftSlots = new LinkedHashSet<>();

    public AbstractContainerMenu(CultPlayer player, Inventory playerInventory) {
        this.player = player;
        this.playerInventory = playerInventory;
        this.carriedItem = SimItemStack.EMPTY;
    }

    public AbstractContainerMenu() {
        this.carriedItem = SimItemStack.EMPTY;
    }

    public Slot addSlot(Slot slot) {
        this.slots.add(slot);
        return slot;
    }

    public void addFourRowPlayerInventory() {
        for (int slot = Inventory.ITEMS_START; slot < Inventory.ITEMS_END; slot++) {
            addSlot(new Slot(playerInventory.getInventoryStorage(), slot));
        }
    }

    public SimItemStack getCarried() {
        return carriedItem;
    }

    public void setCarried(SimItemStack stack) {
        carriedItem = stack == null ? SimItemStack.EMPTY : stack;
    }

    public Slot getSlot(int slotID) {
        if (slotID < 0 || slotID >= this.slots.size()) {
            return null;
        }
        return this.slots.get(slotID);
    }

    public int getMaxStackSize() {
        return 64;
    }

    /**
     * AbstractContainerMenu#doClick for a QUICK_CRAFT click. Returns the slot of a drag that ends on exactly one slot,
     * which vanilla then clicks as PICKUP with the drag type as the button, or -1.
     */
    public int quickCraft(int slotIndex, int button, boolean infiniteMaterials) {
        int expectedStatus = quickcraftStatus;
        quickcraftStatus = button & 3;
        if ((expectedStatus != 1 || quickcraftStatus != 2) && expectedStatus != quickcraftStatus) {
            resetQuickCraft();
        } else if (getCarried().isEmpty()) {
            resetQuickCraft();
        } else if (quickcraftStatus == 0) {
            quickcraftType = button >> 2 & 3;
            if (quickcraftType == 0 || quickcraftType == 1 || quickcraftType == 2 && infiniteMaterials) {
                quickcraftStatus = 1;
                quickcraftSlots.clear();
            } else {
                resetQuickCraft();
            }
        } else if (quickcraftStatus == 1) {
            Slot slot = getSlot(slotIndex);
            SimItemStack carried = getCarried();
            if (slot != null
                    && canItemQuickReplace(slot, carried)
                    && slot.mayPlace(carried)
                    && (quickcraftType == 2 || carried.getCount() > quickcraftSlots.size())) {
                quickcraftSlots.add(slot);
            }
        } else if (quickcraftStatus == 2) {
            int single = quickcraftSlots.size() == 1
                    ? slots.indexOf(quickcraftSlots.iterator().next())
                    : -1;
            resetQuickCraft();
            return single;
        } else {
            resetQuickCraft();
        }
        return -1;
    }

    /** AbstractContainerMenu#doClick ends a drag on any other click type, which then does nothing. */
    public boolean endQuickCraft() {
        if (quickcraftStatus == 0) return false;
        resetQuickCraft();
        return true;
    }

    public int quickcraftType() {
        return quickcraftType;
    }

    private void resetQuickCraft() {
        quickcraftStatus = 0;
        quickcraftSlots.clear();
    }

    // AbstractContainerMenu#canItemQuickReplace with ignoreSize.
    private static boolean canItemQuickReplace(Slot slot, SimItemStack stack) {
        SimItemStack item = slot.getItem();
        if (item.isEmpty()) return true;
        return ItemUtil.isSameItemSameTags(stack, item) && item.getCount() <= stack.getMaxStackSize();
    }
}
