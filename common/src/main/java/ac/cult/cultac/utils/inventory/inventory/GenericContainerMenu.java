package ac.cult.cultac.utils.inventory.inventory;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import ac.cult.cultac.utils.inventory.slot.Slot;

public class GenericContainerMenu extends AbstractContainerMenu {
    public GenericContainerMenu(CultPlayer player, Inventory playerInventory, int containerSlots) {
        this(player, playerInventory, containerSlots, true);
    }

    public GenericContainerMenu(CultPlayer player, Inventory playerInventory, int containerSlots, int trailingSlots) {
        super(player, playerInventory);

        InventoryStorage containerStorage = new InventoryStorage(containerSlots + trailingSlots);
        for (int i = 0; i < containerSlots; i++) addSlot(new Slot(containerStorage, i));
        addFourRowPlayerInventory();
        for (int i = 0; i < trailingSlots; i++) addSlot(new Slot(containerStorage, containerSlots + i));
    }

    public GenericContainerMenu(CultPlayer player, Inventory playerInventory, int containerSlots, boolean includePlayerInventory) {
        super(player, playerInventory);

        InventoryStorage containerStorage = new InventoryStorage(containerSlots);
        for (int i = 0; i < containerSlots; i++) {
            addSlot(new Slot(containerStorage, i));
        }

        if (includePlayerInventory) {
            addFourRowPlayerInventory();
        }
    }
}
