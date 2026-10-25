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

    /** The capture's tick with OPEN_INVENTORY, then horse_screen_open answered before the next tick. */
    private static JavaTickFixture horseScreenOpened() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4);
        f.player.setExperimentalChecks(true);
        var open = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.OPEN_INVENTORY, 0);
        f.listener.onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, open), f.player, open);
        f.tickEnd();
        var screen = new ClientboundMountScreenOpen(CONTAINER, 0, HORSE);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOUNT_SCREEN_OPEN, screen, false);
        f.player.openWindow.onServerWindow(event, CONTAINER, MenuType.UNKNOWN);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        return f;
    }

    private static void carry(JavaTickFixture f, int slot) {
        var packet = new ServerboundSetCarriedItem(slot);
        var event = f.event(ServerboundPackets.SET_CARRIED_ITEM, packet);
        f.player.checkManager.dispatchEarlyReceive(event);
        f.listener.onSetCarriedItem(event, f.player, packet);
    }
}
