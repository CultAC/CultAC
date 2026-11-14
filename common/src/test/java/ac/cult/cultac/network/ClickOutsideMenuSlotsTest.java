package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ServerGamePacketListenerImpl#handleContainerClick ignores a click whose slot fails
 * AbstractContainerMenu#isValidSlotIndex for the server's open menu, so its claimed slots never become server state.
 */
class ClickOutsideMenuSlotsTest {
    private static final int CHEST_MENU_SLOT = 6;
    private static final int ELYTRA_MENU_SLOT = 9;
    private static final int INVENTORY_MENU_SLOTS = 46;
    private static final int CHEST_WINDOW = 5;
    private static final int CHEST_MENU_SLOTS = 27 + 36;
    private static final Vec3 FROM = new Vec3(8.5, JavaTickFixture.FLOOR_Y + 40, 8.5);
    private static final Vec3 CARRIED = new Vec3(0.5, -0.1, 0.8);
    private static final float YAW = 30.0F, PITCH = -20.0F;

    @Test
    void aClickPastTheLastMenuSlotKeepsTheWornChestplate() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            var event = click(f, 100, swap());
            assertTrue(event.isCancelled());
            f.player.getInventory().resyncCancelledClick(event.getPacket());

            assertEquals("minecraft:iron_chestplate", f.player.getInventory().getChestplate().itemKey());
            assertTrue(startGlide(f).isCancelled());
            assertFalse(f.player.isGliding);
        }
    }

    @Test
    void aClickPastTheLastMenuSlotForwardedInObservationDoesNotBecomeServerState() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            var event = click(f, INVENTORY_MENU_SLOTS, swap());
            f.player.getInventory().onClickForwarded(event.getPacket());

            assertEquals("minecraft:iron_chestplate", f.player.getInventory().getChestplate().itemKey());
            assertNull(f.player.getInventory().serverViewSlot(chestStorageSlot(f)));
            assertTrue(startGlide(f).isCancelled());
        }
    }

    @Test
    void aClickOnTheLastMenuSlotIsNotCancelled() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            var event = click(f, INVENTORY_MENU_SLOTS - 1, swap());
            assertFalse(event.isCancelled());
        }
    }

    @Test
    void aShiftClickEquipOnAValidSlotIsMirroredAndTheGlideIsAccepted() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            f.player.getInventory().menu.getSlot(CHEST_MENU_SLOT).set(SimItemStack.EMPTY);
            var event = click(
                    f,
                    0,
                    ELYTRA_MENU_SLOT,
                    WindowClickType.QUICK_MOVE,
                    Map.of(CHEST_MENU_SLOT, item("minecraft:elytra"), ELYTRA_MENU_SLOT, SimItemStack.EMPTY));
            assertFalse(event.isCancelled());
            f.player.getInventory().onClickForwarded(event.getPacket());

            assertEquals("minecraft:elytra", f.player.getInventory().getChestplate().itemKey());
            assertFalse(startGlide(f).isCancelled());
            assertTrue(f.player.isGliding);
            f.player.refreshPlayerPose();
            Vec3 glide = ElytraTransform.getElytraMovement(
                    f.player, CARRIED, ReachUtils.getLook(f.player, YAW, PITCH), PITCH, 0.08);
            PredictionResult result = f.tick(FROM.add(glide), false);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void aShorterSetContentKeepsThePlayerInventorySlotsOfTheMenu() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            openChest(f, "minecraft:generic_9x3", 27);
            assertFalse(click(f, CHEST_WINDOW, 40, WindowClickType.QUICK_MOVE, Map.of()).isCancelled());
            assertFalse(click(f, CHEST_WINDOW, CHEST_MENU_SLOTS - 1, WindowClickType.QUICK_MOVE, Map.of())
                    .isCancelled());
        }
    }

    @Test
    void aClickPastTheLastSlotOfAnOpenMenuIsCancelled() throws Exception {
        try (var f = wearingChestplateWithElytra()) {
            openChest(f, "minecraft:generic_9x3", CHEST_MENU_SLOTS);
            assertTrue(click(f, CHEST_WINDOW, CHEST_MENU_SLOTS, WindowClickType.QUICK_MOVE, Map.of())
                    .isCancelled());
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
        return click(f, 0, slot, WindowClickType.PICKUP, changed);
    }

    private static PacketReceiveEvent<InventoryClick> click(
            JavaTickFixture f, int window, int slot, WindowClickType type, Map<Integer, SimItemStack> changed) {
        var click = new InventoryClick(window, 0, slot, 0, type, changed, SimItemStack.EMPTY);
        var event = new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, type(InventoryClick.class), click);
        f.player.getInventory().onContainerClick(event, f.player, click);
        return event;
    }

    /** ClientboundOpenScreen then a set_content for its window carrying {@code items} stacks. */
    private static void openChest(JavaTickFixture f, String menuType, int items) {
        var open = new ClientboundOpenScreen(CHEST_WINDOW, menuType);
        var openEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, open, false);
        f.player.getInventory().onOpenScreen(openEvent, f.player, open);
        openEvent.tasksAfter().forEach(Runnable::run);
        var content = new InventoryPackets.Content(
                CHEST_WINDOW, 1, new ArrayList<>(Collections.nCopies(items, SimItemStack.EMPTY)), SimItemStack.EMPTY);
        var contentEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, type(InventoryPackets.Content.class), content, false);
        f.player.getInventory().onContainerSetContent(contentEvent, f.player, content);
        contentEvent.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
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
