package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CraftBukkit's ServerGamePacketListenerImpl#handleContainerClick ignores a click whose slot is below -1 and not
 * outside the window, so its claimed slots never become server state.
 */
class ClickOutsideMenuSlotsTest {
    private static final int CHEST_MENU_SLOT = 6;
    private static final int ELYTRA_MENU_SLOT = 9;
    private static final Vec3 FROM = new Vec3(8.5, JavaTickFixture.FLOOR_Y + 40, 8.5);
    private static final Vec3 CARRIED = new Vec3(0.5, -0.1, 0.8);
    private static final float YAW = 30.0F, PITCH = -20.0F;

    @Test
    void aClickBelowTheNoSlotValueKeepsTheWornChestplate() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            var event = click(f, -2, swap());
            assertTrue(event.isCancelled());
            f.player.getInventory().resyncCancelledClick(event.getPacket());

            assertEquals("minecraft:iron_chestplate", f.player.getInventory().getChestplate().itemKey());
            assertTrue(startGlide(f).isCancelled());
        }
    }

    @Test
    void aClickBelowTheNoSlotValueForwardedInObservationDoesNotBecomeServerState() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            var event = click(f, -2, swap());
            f.player.getInventory().onClickForwarded(event.getPacket());

            assertEquals("minecraft:iron_chestplate", f.player.getInventory().getChestplate().itemKey());
            assertNull(f.player.getInventory().serverViewSlot(chestStorageSlot(f)));
        }
    }

    @Test
    void aClickOutsideTheWindowIsNotCancelled() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            assertFalse(click(f, -999, Map.of()).isCancelled());
        }
    }

    @Test
    void aClickWithNoSlotIsNotCancelled() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            assertFalse(click(f, -1, Map.of()).isCancelled());
        }
    }

    private static Map<Integer, SimItemStack> swap() {
        return Map.of(CHEST_MENU_SLOT, item("minecraft:elytra"), ELYTRA_MENU_SLOT, item("minecraft:iron_chestplate"));
    }

    private static int chestStorageSlot(JavaTickFixture f) {
        return f.player.getInventory().menu.getSlot(CHEST_MENU_SLOT).getContainerSlot();
    }

    private static JavaTickFixture wearingChestplateWithElytra() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        f.place(FROM, CARRIED);
        f.player.getInventory().menu.getSlot(CHEST_MENU_SLOT).set(item("minecraft:iron_chestplate"));
        f.player.getInventory().menu.getSlot(ELYTRA_MENU_SLOT).set(item("minecraft:elytra"));
        return f;
    }

    private static SimItemStack item(String key) {
        return OfflineCultTestBootstrap.item(key);
    }

    private static PacketReceiveEvent<InventoryClick> click(JavaTickFixture f, int slot, Map<Integer, SimItemStack> changed) {
        var click = new InventoryClick(0, 0, slot, 0, WindowClickType.PICKUP, changed, SimItemStack.EMPTY);
        var event = new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, type(InventoryClick.class), click);
        f.player.getInventory().onContainerClick(event, f.player, click);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static <T> PacketType<T> type(Class<T> record) {
        return (PacketType<T>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == record)
                .findFirst()
                .orElseThrow();
    }

    private static PacketReceiveEvent<ServerboundPlayerCommand> startGlide(JavaTickFixture f) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        return event;
    }
}
