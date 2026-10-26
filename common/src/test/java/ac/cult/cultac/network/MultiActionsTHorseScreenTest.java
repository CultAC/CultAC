package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsT;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import org.junit.jupiter.api.Test;

/**
 * 1.21.4 Minecraft#handleKeybinds picks a hotbar slot and then sends OPEN_INVENTORY for a horse through
 * LocalPlayer#sendOpenInventory; the screen opens only when ClientPacketListener#handleHorseScreenOpen runs before the
 * next tick. That tick's MultiPlayerGameMode#tick sends the slot from ensureHasSentCarriedItem with the screen open.
 */
class MultiActionsTHorseScreenTest {
    private static final int HORSE = 9222;
    private static final int CONTAINER = 26;

    @Test
    void theSlotPickedBeforeTheHorseScreenOpenedDoesNotFlag() throws Exception {
        try (var f = horseScreenOpened()) {
            carry(f, 6);
            f.tickEnd();
            assertEquals(
                    0, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotChangeAfterATickWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpened()) {
            carry(f, 6);
            f.tickEnd();
            carry(f, 7);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSecondSlotInTheFirstTickWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpened()) {
            carry(f, 6);
            carry(f, 7);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotAfterTheMovementOfTheFirstTickWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpened()) {
            var rot = new ServerboundMovePlayer(0, 0, 0, 272.58f, 6.97f, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            carry(f, 6);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotAfterThePlayerInputOfTheFirstTickWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpened()) {
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, true));
            carry(f, 6);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotAfterTheSprintCommandOfTheFirstTickWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpened()) {
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            f.listener.onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            carry(f, 6);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void theSlotBeforeThePlayerInputOfTheFirstTickWithTheHorseScreenOpenDoesNotFlag() throws Exception {
        try (var f = horseScreenOpened()) {
            carry(f, 6);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, true));
            f.tickEnd();
            assertEquals(
                    0, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    /** A window-0 click shows the inventory open; the slots after the tick's first come from no vanilla path. */
    @Test
    void slotsAfterTheFirstInTheTickOfAnInventoryClickFlag() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            f.player.setExperimentalChecks(true);
            f.tickEnd();
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            carry(f, 4);
            carry(f, 5);
            carry(f, 6);
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            f.tickEnd();
            assertEquals(
                    2, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void theEchoOfASetHeldSlotWithTheHorseScreenOpenDoesNotFlag() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            setHeldSlot(f, 3);
            carry(f, 3);
            f.tickEnd();
            assertEquals(
                    0, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotOtherThanTheSetHeldSlotWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            setHeldSlot(f, 3);
            carry(f, 4);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotAfterTheEchoOfASetHeldSlotWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            setHeldSlot(f, 3);
            carry(f, 3);
            carry(f, 4);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void theSetHeldSlotSlotInALaterTickWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            setHeldSlot(f, 3);
            carry(f, 3);
            f.tickEnd();
            carry(f, 4);
            f.tickEnd();
            carry(f, 3);
            assertEquals(
                    2, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotOfMinusOneWithTheHorseScreenOpenAndNoSetHeldSlotFlags() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            carry(f, -1);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void theCarriedSlotAfterASetHeldSlotOfItWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            setHeldSlot(f, 6);
            carry(f, 6);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void theSetHeldSlotSlotAfterTheMovementWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            setHeldSlot(f, 3);
            var rot = new ServerboundMovePlayer(0, 0, 0, 272.58f, 6.97f, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            carry(f, 3);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void theSetHeldSlotSlotBeforeItsTransactionWithTheHorseScreenOpenFlags() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            sendSetHeldSlot(f, 3);
            carry(f, 3);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void aSlotOtherThanASetHeldSlotAppliedAfterTheHorseScreenOpenedFlags() throws Exception {
        try (var f = horseScreenOpened()) {
            setHeldSlot(f, 3);
            carry(f, 5);
            assertEquals(
                    1, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    @Test
    void theEchoOfASetHeldSlotAppliedAfterTheHorseScreenOpenedDoesNotFlag() throws Exception {
        try (var f = horseScreenOpened()) {
            setHeldSlot(f, 3);
            carry(f, 3);
            f.tickEnd();
            assertEquals(
                    0, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    /** A scroll after set_held_slot and before horse_screen_open changes the selected slot the next tick sends. */
    @Test
    void aSlotOtherThanASetHeldSlotAppliedBeforeTheHorseScreenOpenedDoesNotFlag() throws Exception {
        try (var f = horseInventoryCommandSent()) {
            setHeldSlot(f, 3);
            openHorseScreen(f);
            carry(f, 5);
            f.tickEnd();
            assertEquals(
                    0, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    /** A scroll after the screen closes and before the server opens another changes the slot the next tick sends. */
    @Test
    void aSlotOtherThanASetHeldSlotAppliedBeforeTheScreenClosedAndOpenedAgainDoesNotFlag() throws Exception {
        try (var f = horseScreenOpenForATick()) {
            setHeldSlot(f, 3);
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            openHorseScreen(f);
            carry(f, 5);
            f.tickEnd();
            assertEquals(
                    0, f.player.checkManager.getListener(MultiActionsT.class).getViolations());
        }
    }

    private static JavaTickFixture horseScreenOpenForATick() throws Exception {
        var f = horseScreenOpened();
        carry(f, 6);
        f.tickEnd();
        return f;
    }

    /** ClientPacketListener#handleSetHeldSlot answered before the client's next tick. */
    private static void setHeldSlot(JavaTickFixture f, int slot) {
        sendSetHeldSlot(f, slot);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void sendSetHeldSlot(JavaTickFixture f, int slot) {
        var held = new ClientboundSetHeldSlot(slot);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_HELD_SLOT, held, false);
        f.player.checkManager.getInventory().onSetHeldSlot(event, f.player, held);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    /** The capture's tick with OPEN_INVENTORY, then horse_screen_open answered before the next tick. */
    private static JavaTickFixture horseScreenOpened() throws Exception {
        var f = horseInventoryCommandSent();
        openHorseScreen(f);
        return f;
    }

    private static JavaTickFixture horseInventoryCommandSent() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4);
        f.player.setExperimentalChecks(true);
        var open = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.OPEN_INVENTORY, 0);
        f.listener.onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, open), f.player, open);
        f.tickEnd();
        return f;
    }

    private static void openHorseScreen(JavaTickFixture f) {
        var screen = new ClientboundMountScreenOpen(CONTAINER, 0, HORSE);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOUNT_SCREEN_OPEN, screen, false);
        f.player.openWindow.onServerWindow(event, CONTAINER, MenuType.UNKNOWN);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void carry(JavaTickFixture f, int slot) {
        var packet = new ServerboundSetCarriedItem(slot);
        var event = f.event(ServerboundPackets.SET_CARRIED_ITEM, packet);
        f.player.checkManager.dispatchEarlyReceive(event);
        f.listener.onSetCarriedItem(event, f.player, packet);
    }
}
