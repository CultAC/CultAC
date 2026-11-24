package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsU;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick runs handleKeybinds, the only caller of the pick key, while no screen is open, and sets
 * InBedChatScreen, the only sender of STOP_SLEEPING, while no screen is open. ClientPacketListener#handleOpenBook and
 * the WIN_GAME game event set a screen in place of a container screen, whose AbstractContainerScreen#removed sends no
 * container_close.
 */
class MultiActionsUPickLeaveBedTest {
    private static final int CHEST = 51;

    private final PacketPlayerWindow window = new PacketPlayerWindow();

    @Test
    void eachActionWithTheInventoryOpenFlagsAndNamesItself() throws Exception {
        try (var f = fixture()) {
            var verbose = new ArrayList<String>();
            f.player.observer = new PlayerObserver() {
                @Override
                public void flagged(Check check, double violations, Supplier<String> text) {
                    verbose.add(text.get());
                }
            };
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_ENTITY);
            pick(f, ServerboundPackets.PICK_ITEM);
            leaveBed(f);
            assertEquals(4, violations(f));
            assertEquals(List.of("action=pickBlock", "action=pickEntity", "action=pick", "action=leaveBed"), verbose);
        }
    }

    @Test
    void aFlaggedActionIsCancelledWhereTheCheckMayModifyPackets() throws Exception {
        try (var f = fixture()) {
            var check = spy(f.player.checkManager.getListener(MultiActionsU.class));
            doReturn(true).when(check).shouldModifyPackets();
            var pick = ServerboundPackets.PICK_ITEM_FROM_BLOCK.opaqueValue();
            var wake = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.STOP_SLEEPING, 0);

            var closed = f.event(ServerboundPackets.PICK_ITEM_FROM_BLOCK, pick);
            check.onPickBlock(closed, f.player, pick);
            assertFalse(closed.isCancelled());

            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var picked = f.event(ServerboundPackets.PICK_ITEM_FROM_BLOCK, pick);
            check.onPickBlock(picked, f.player, pick);
            assertTrue(picked.isCancelled());
            var woken = f.event(ServerboundPackets.PLAYER_COMMAND, wake);
            check.onPlayerCommand(woken, f.player, wake);
            assertTrue(woken.isCancelled());
        }
    }

    @Test
    void noActionFlagsWithNoScreenOpen() throws Exception {
        try (var f = fixture()) {
            assertFalse(pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK).isCancelled());
            assertFalse(pick(f, ServerboundPackets.PICK_ITEM_FROM_ENTITY).isCancelled());
            assertFalse(pick(f, ServerboundPackets.PICK_ITEM).isCancelled());
            assertFalse(leaveBed(f).isCancelled());
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aPickAfterTheInventoryClosedDoesNotFlag() throws Exception {
        try (var f = fixture()) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aPickFlagsOnlyOnceTheClientHasHandledTheChest() throws Exception {
        try (var f = fixture()) {
            int open = openChestUnanswered(f);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            f.pongThrough(open - 1);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            assertEquals(0, violations(f));
            f.pongThrough(open);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            assertEquals(1, violations(f));
        }
    }

    /** The chest screen replaced InBedChatScreen, and its container_close precedes the Leave Bed button. */
    @Test
    void leavingTheBedAfterClosingAChestOpenedOverItDoesNotFlag() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(openChestUnanswered(f));
            assertEquals(1, flagsOf(f, () -> leaveBed(f)));
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            leaveBed(f);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void aPickAfterABookTheServerOpenedOverAChestDoesNotFlag() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(openChestUnanswered(f));
            int book = openBook(f);
            assertEquals(1, flagsOf(f, () -> pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK)));
            f.pongThrough(book);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            leaveBed(f);
            assertEquals(1, violations(f));
        }
    }

    /** Minecraft#startUseItem sent the book's use, and the inventory key and a click followed before open_book. */
    @Test
    void anInventoryClickBetweenTheBooksPingsDoesNotProveTheInventoryStayed() throws Exception {
        try (var f = fixture()) {
            int book = openBook(f);
            f.pongThrough(book - 1);
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.pongThrough(book);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void anInventoryClickAfterTheBookProvesTheInventoryIsOpen() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(openBook(f));
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            assertEquals(1, violations(f));
        }
    }

    /** WinScreen sends PERFORM_RESPAWN and leaves no screen until the respawn arrives. */
    @Test
    void aPickAfterTheCreditsOverAChestDoesNotFlag() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(openChestUnanswered(f));
            gameEvent(f, GameEventType.WIN_GAME);
            f.pongThrough(f.player.lastTransactionSent.get());
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void anotherGameEventLeavesTheChestOpen() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(openChestUnanswered(f));
            int sent = f.player.lastTransactionSent.get();
            gameEvent(f, GameEventType.CHANGE_GAME_MODE);
            assertEquals(sent, f.player.lastTransactionSent.get());
            pick(f, ServerboundPackets.PICK_ITEM_FROM_BLOCK);
            assertEquals(1, violations(f));
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3);
        f.player.setExperimentalChecks(true);
        return f;
    }

    private static PacketReceiveEvent<Opaque> pick(JavaTickFixture f, PacketType<Opaque> type) {
        var packet = type.opaqueValue();
        var event = f.event(type, packet);
        MultiActionsU check = f.player.checkManager.getListener(MultiActionsU.class);
        if (type == ServerboundPackets.PICK_ITEM_FROM_BLOCK) check.onPickBlock(event, f.player, packet);
        else if (type == ServerboundPackets.PICK_ITEM_FROM_ENTITY) check.onPickEntity(event, f.player, packet);
        else check.onPickItem(event, f.player, packet);
        return event;
    }

    private static PacketReceiveEvent<ServerboundPlayerCommand> leaveBed(JavaTickFixture f) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.STOP_SLEEPING, 0);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        f.player.checkManager.getListener(MultiActionsU.class).onPlayerCommand(event, f.player, packet);
        return event;
    }

    private static int flagsOf(JavaTickFixture f, Runnable action) {
        double before = violations(f);
        action.run();
        return (int) (violations(f) - before);
    }

    private int openChestUnanswered(JavaTickFixture f) {
        var packet = new ClientboundOpenScreen(CHEST, "minecraft:generic_9x6");
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        window.onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    /** The transaction of the ping after open_book; the ping before it is the one before. */
    private int openBook(JavaTickFixture f) {
        var packet = ClientboundPackets.OPEN_BOOK.opaqueValue();
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_BOOK, packet, false);
        window.onOpenBook(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private void gameEvent(JavaTickFixture f, GameEventType type) {
        var packet = new ClientboundGameEvent(type, 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        window.onGameEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getListener(MultiActionsU.class).getViolations();
    }
}
