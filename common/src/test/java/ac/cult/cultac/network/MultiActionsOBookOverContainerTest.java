package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenBook;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleOpenBook sets a BookViewScreen in place of the open container screen when the item in
 * that hand carries book content. AbstractContainerScreen#removed sends no container_close, and closing the book
 * leaves no screen, so the mouse turns the player again while the server still has the container open. As in a 26.3
 * capture, the server opens a chest boat's generic_9x3 container and then the written book the player used.
 */
class MultiActionsOBookOverContainerTest {
    private static final int CHEST_BOAT = 1;
    private static final float YAW = 79.93611f;
    private static final float PITCH = 0.78093004f;
    private static final int READING_TICKS = 12;

    private final PacketPlayerWindow window = new PacketPlayerWindow();

    @Test
    void aTurnAfterClosingABookOpenedOverTheContainerDoesNotFlag() throws Exception {
        try (var f = containerOpen(writtenBook())) {
            openBook(f);
            look(f, 69.70886f, 3.0013177f);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void anOpenBookWithNoBookInThatHandLeavesTheContainerOpen() throws Exception {
        try (var f = containerOpen(SimItemStack.EMPTY)) {
            openBook(f);
            look(f, 69.70886f, 3.0013177f);
            assertEquals(1, violations(f));
        }
    }

    private static SimItemStack writtenBook() {
        var book = ItemUtil.modelItems().stack("minecraft:written_book", 1);
        var content = JsonParser.parseString("{\"title\":\"a\",\"author\":\"a\",\"pages\":[\"a\"]}");
        book.components(book.components().with("minecraft:written_book_content", content));
        return book;
    }

    private JavaTickFixture containerOpen(SimItemStack mainHand) throws Exception {
        var f = new JavaTickFixture();
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.player.getInventory().inventory.setHeldItem(mainHand);
        f.tickEnd();
        look(f, YAW, PITCH);
        f.tickEnd();
        var packet = new ClientboundOpenScreen(CHEST_BOAT, "minecraft:generic_9x3");
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        window.onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.tickEnd();
        return f;
    }

    private void openBook(JavaTickFixture f) {
        var packet = new ClientboundOpenBook(Hand.MAIN_HAND);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_BOOK, packet, false);
        window.onOpenBook(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 0; tick < READING_TICKS; tick++) f.tickEnd();
    }

    private static void look(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(MultiActionsO.class).getViolations();
    }
}
