package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.charge;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.lifted;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.release;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.trident;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hotbar-key swaps in the player's inventory screen before a riptide charge. AbstractContainerMenu#doClick's SWAP moves
 * the button's Inventory item into the clicked slot and the clicked slot's item into the button's. From 1.21.5 the
 * click's changed slots carry component hashes only, so two tridents read alike in the claim.
 */
class JavaRiptideSwapClickTest {
    private static final int MAIN_SLOT = 27;

    @Test
    void swapsThroughTheMainInventoryMoveEachTridentToItsButtonsSlot() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3)) {
            swapRiptideIIIntoTheFirstSlot(f);
            InventoryStorage storage = f.player.getInventory().inventory.getInventoryStorage();
            assertEquals(2, RiptideUtil.getRiptideLevel(storage.getItem(Inventory.HOTBAR_OFFSET)));
            assertEquals(3, RiptideUtil.getRiptideLevel(storage.getItem(MAIN_SLOT)));
            assertEquals("minecraft:mace", storage.getItem(Inventory.HOTBAR_OFFSET + 1).itemKey());
        }
    }

    @Test
    void theReleaseSpinsAtTheSwappedInTridentsLevel() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3)) {
            swapRiptideIIIntoTheFirstSlot(f);
            charge(f);
            release(f);
            var result = f.tick(lifted(2), false);
            assertTrue(exact(result), describe(result));
        }
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3)) {
            swapRiptideIIIntoTheFirstSlot(f);
            charge(f);
            release(f);
            assertRejected(f.tick(lifted(3), false), "VerticalOffset");
        }
    }

    @Test
    void aReleaseWithoutTheSwapsStillSpinsAtTheHeldLevel() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3)) {
            stock(f);
            charge(f);
            release(f);
            assertRejected(f.tick(lifted(2), false), "VerticalOffset");
        }
    }

    /**
     * An inventory and its clicks: menu slot 36 riptide III, 37 riptide II and 27 a mace, then SWAP clicks on
     * slot 27 with button 1 and with button 0, each claiming its two changed slots by item alone.
     */
    private static void swapRiptideIIIntoTheFirstSlot(JavaTickFixture f) {
        stock(f);
        var inventory = f.player.getInventory();
        var trident = claim("minecraft:trident");
        assertTrue(inventory.applyContainerClick(swap(1, Map.of(MAIN_SLOT, trident, 37, claim("minecraft:mace")))));
        assertTrue(inventory.applyContainerClick(swap(0, Map.of(MAIN_SLOT, trident, 36, trident))));
    }

    private static void stock(JavaTickFixture f) {
        InventoryStorage storage = f.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(Inventory.HOTBAR_OFFSET, trident(3));
        storage.setItem(Inventory.HOTBAR_OFFSET + 1, trident(2));
        storage.setItem(MAIN_SLOT, mace());
    }

    private static InventoryClick swap(int button, Map<Integer, SimItemStack> changed) {
        return new InventoryClick(0, 448, MAIN_SLOT, button, WindowClickType.SWAP, changed, SimItemStack.EMPTY);
    }

    /** A hashed click claim: item and count, no components. */
    private static SimItemStack claim(String item) {
        return OfflineCultTestBootstrap.item(item);
    }

    private static SimItemStack mace() {
        SimItemStack mace = OfflineCultTestBootstrap.item("minecraft:mace");
        mace.setComponent(
                "minecraft:enchantments", CanonicalSnbt.parse("{'minecraft:density':5,'minecraft:wind_burst':3}"));
        return mace;
    }
}
