package ac.cult.cultac.utils.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InventoryMenuSlotTest {

    private final Inventory inventory = new Inventory(null, new InventoryStorage(Inventory.STORAGE_SIZE));

    @Test
    void armorStorageSlotsMapToClientArmorMenuSlots() {
        assertEquals(5, inventory.menuSlotForStorage(Inventory.SLOT_HELMET));
        assertEquals(6, inventory.menuSlotForStorage(Inventory.SLOT_CHESTPLATE));
        assertEquals(7, inventory.menuSlotForStorage(Inventory.SLOT_LEGGINGS));
        assertEquals(8, inventory.menuSlotForStorage(Inventory.SLOT_BOOTS));
    }

    @Test
    void craftingStorageSlotsFollowTheResultSlot() {
        for (int storage = 0; storage < 4; storage++) {
            assertEquals(storage + 1, inventory.menuSlotForStorage(storage));
        }
    }

    @Test
    void mainInventoryHotbarAndOffhandKeepTheirIndex() {
        for (int storage = Inventory.ITEMS_START; storage < Inventory.ITEMS_END; storage++) {
            assertEquals(storage, inventory.menuSlotForStorage(storage));
        }
        assertEquals(45, inventory.menuSlotForStorage(Inventory.SLOT_OFFHAND));
    }

    @Test
    void storageSlotsOutsideThePlayerMenuHaveNoMenuSlot() {
        assertEquals(-1, inventory.menuSlotForStorage(Inventory.SLOT_BODY));
        assertEquals(-1, inventory.menuSlotForStorage(Inventory.SLOT_SADDLE));
    }
}
