package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.MutableBundleContents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AbstractContainerScreen#quickCraftToSlots sends a right-button drag as QUICK_CRAFT start, one add per slot and end.
 * AbstractContainerMenu#doClick clicks a drag that ends on exactly one slot as PICKUP with the drag type as the
 * button, so a carried bundle dragged onto one empty slot puts its selected item there.
 */
class BundleQuickCraftTest {
    private static final int HOTBAR_0 = 36;
    private static final int OUTSIDE = -999;
    private static final int RIGHT = 1;
    private static final int MIDDLE = 2;

    @Test
    void aRightDragOfABundleOntoOneEmptySlotTakesOutItsItem() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_1)) {
            var inventory = f.player.getInventory();
            var pickaxe = ItemUtil.modelItems().stack("minecraft:diamond_pickaxe", 1);
            var full = bundle(pickaxe);
            var empty = bundle();
            inventory.menu.setCarried(full);

            assertTrue(inventory.applyContainerClick(drag(0, OUTSIDE, Map.of(), full)));
            assertTrue(inventory.applyContainerClick(drag(1, HOTBAR_0, Map.of(), full)));
            assertTrue(inventory.applyContainerClick(drag(2, OUTSIDE, Map.of(HOTBAR_0, pickaxe), empty)));

            var hotbar = inventory.inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET);
            assertEquals("minecraft:diamond_pickaxe", hotbar.itemKey());
            assertTrue(ItemUtil.isSameItemSameTags(empty, inventory.menu.getCarried()));
        }
    }

    // AbstractContainerMenu#isValidQuickcraftType: a middle drag needs Player#hasInfiniteMaterials (instabuild).
    @Test
    void aMiddleDragWithInfiniteMaterialsOutsideCreativeEndsOnTheNextClick() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_1)) {
            f.player.gamemode = GameMode.SURVIVAL;
            f.player.canInstabuild = true;
            var inventory = f.player.getInventory();
            var stone = ItemUtil.modelItems().stack("minecraft:stone", 2);
            inventory.menu.setCarried(stone);

            assertTrue(inventory.applyContainerClick(drag(0, MIDDLE, OUTSIDE, Map.of(), stone)));
            var pickup = new InventoryClick(
                    0, 0, HOTBAR_0, 0, WindowClickType.PICKUP, Map.of(HOTBAR_0, stone), SimItemStack.EMPTY);
            assertTrue(inventory.applyContainerClick(pickup));

            assertTrue(inventory.inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET).isEmpty());
            assertTrue(ItemUtil.isSameItemSameTags(stone, inventory.menu.getCarried()));
        }
    }

    private static InventoryClick drag(int header, int slot, Map<Integer, SimItemStack> changed, SimItemStack carried) {
        return drag(header, RIGHT, slot, changed, carried);
    }

    // AbstractContainerMenu#getQuickcraftMask
    private static InventoryClick drag(
            int header, int type, int slot, Map<Integer, SimItemStack> changed, SimItemStack carried) {
        return new InventoryClick(0, 0, slot, header | type << 2, WindowClickType.QUICK_CRAFT, changed, carried);
    }

    private static SimItemStack bundle(SimItemStack... items) {
        var bundle = ItemUtil.modelItems().stack("minecraft:bundle", 1);
        var contents = new MutableBundleContents(bundle.components().bundle(), ItemUtil.modelItems());
        for (var item : items) contents.tryInsert(item.copy());
        bundle.bundleContents(contents.toImmutable());
        return bundle;
    }
}
