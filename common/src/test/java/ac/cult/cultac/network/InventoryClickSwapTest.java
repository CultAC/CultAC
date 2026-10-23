package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AbstractContainerMenu#doClick swaps same-item stacks whose components differ; hashed claims carry none. */
class InventoryClickSwapTest {
    // InventoryMenu slot indices.
    private static final int CHEST = 6, MAIN = 9, HOTBAR = 36;

    @Test
    void aPickupSwapOfTwoElytrasEquipsTheCarriedOne() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var inventory = f.player.getInventory();
            inventory.inventory.getInventoryStorage().setItem(Inventory.SLOT_CHESTPLATE, elytra(431));
            inventory.menu.setCarried(elytra(0));

            assertTrue(inventory.applyContainerClick(new InventoryClick(
                    0,
                    0,
                    CHEST,
                    0,
                    WindowClickType.PICKUP,
                    Map.of(CHEST, claim("minecraft:elytra", 1)),
                    claim("minecraft:elytra", 1))));
            assertEquals(0, inventory.getChestplate().getDamageValue());
            assertEquals(431, inventory.menu.getCarried().getDamageValue());
        }
    }

    @Test
    void aHotbarSwapOfTwoElytrasTradesTheirComponents() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var inventory = f.player.getInventory();
            var storage = inventory.inventory.getInventoryStorage();
            // An earlier elytra must not be taken as the hotbar slot's source.
            storage.setItem(MAIN + 1, elytra(100));
            storage.setItem(MAIN + 2, elytra(431));
            storage.setItem(Inventory.HOTBAR_OFFSET, elytra(0));

            assertTrue(inventory.applyContainerClick(new InventoryClick(
                    0,
                    0,
                    MAIN + 2,
                    0,
                    WindowClickType.SWAP,
                    Map.of(MAIN + 2, claim("minecraft:elytra", 1), HOTBAR, claim("minecraft:elytra", 1)),
                    SimItemStack.EMPTY)));
            assertEquals(0, storage.getItem(MAIN + 2).getDamageValue());
            assertEquals(431, storage.getItem(Inventory.HOTBAR_OFFSET).getDamageValue());
            assertEquals(100, storage.getItem(MAIN + 1).getDamageValue());
        }
    }

    @Test
    void aPickupSwapOfDifferentlyNamedStacksKeepsEachNameWithItsCount() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var inventory = f.player.getInventory();
            var storage = inventory.inventory.getInventoryStorage();
            storage.setItem(MAIN, named("a", 10));
            inventory.menu.setCarried(named("b", 5));

            assertTrue(inventory.applyContainerClick(new InventoryClick(
                    0,
                    0,
                    MAIN,
                    0,
                    WindowClickType.PICKUP,
                    Map.of(MAIN, claim("minecraft:dirt", 5)),
                    claim("minecraft:dirt", 10))));
            assertTrue(SimItemStack.matches(named("b", 5), storage.getItem(MAIN)));
            assertTrue(SimItemStack.matches(named("a", 10), inventory.menu.getCarried()));
        }
    }

    @Test
    void aPickupOfSameComponentsMergesWithoutTakingOtherComponents() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var inventory = f.player.getInventory();
            var storage = inventory.inventory.getInventoryStorage();
            storage.setItem(MAIN, named("a", 10));
            storage.setItem(MAIN + 1, named("b", 3));
            inventory.menu.setCarried(named("a", 5));

            assertTrue(inventory.applyContainerClick(new InventoryClick(
                    0,
                    0,
                    MAIN,
                    0,
                    WindowClickType.PICKUP,
                    Map.of(MAIN, claim("minecraft:dirt", 15)),
                    SimItemStack.EMPTY)));
            assertTrue(SimItemStack.matches(named("a", 15), storage.getItem(MAIN)));
            assertTrue(inventory.menu.getCarried().isEmpty());
        }
    }

    private static SimItemStack elytra(int damage) {
        var stack = item("minecraft:elytra");
        if (damage != 0) stack.damage(damage);
        return stack;
    }

    private static SimItemStack named(String name, int count) {
        var stack = item("minecraft:dirt", count);
        stack.setComponent("minecraft:custom_name", new ac.cult.blocksim.data.nbt.NbtValue.Text(name));
        return stack;
    }

    /** A 1.21.5+ hashed claim decodes to item and count only. */
    private static SimItemStack claim(String key, int count) {
        return item(key, count);
    }
}
