package ac.grim.grimac.utils.inventory.inventory;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.inventory.InventoryStorage;
import ac.grim.grimac.utils.inventory.slot.Slot;

public class GenericContainerMenu extends AbstractContainerMenu {
    public GenericContainerMenu(GrimPlayer player, Inventory playerInventory, int containerSlots) {
        this(player, playerInventory, containerSlots, true);
    }

    public GenericContainerMenu(GrimPlayer player, Inventory playerInventory, int containerSlots, int trailingSlots) {
        super(player, playerInventory);

        InventoryStorage containerStorage = new InventoryStorage(containerSlots + trailingSlots);
        for (int i = 0; i < containerSlots; i++) addSlot(new Slot(containerStorage, i));
        addFourRowPlayerInventory();
        for (int i = 0; i < trailingSlots; i++) addSlot(new Slot(containerStorage, containerSlots + i));
    }

    public GenericContainerMenu(GrimPlayer player, Inventory playerInventory, int containerSlots, boolean includePlayerInventory) {
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
