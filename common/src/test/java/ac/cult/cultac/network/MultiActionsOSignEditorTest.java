package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleOpenSignEditor sets the sign's edit screen in place of an open container screen, and
 * AbstractContainerScreen#removed sends no container_close. Closing the editor sends only the sign_update from
 * AbstractSignEditScreen#removed and leaves no screen, so the mouse turns the player again while the server still has
 * the container open.
 */
class MultiActionsOSignEditorTest {
    private static final int CHEST = 51;
    private static final float YAW = 9.750009536743164f;
    private static final float TURNED_YAW = 10.050008773803711f;
    private static final float PITCH = 34.79997634887695f;
    private static final int EDITOR_TICKS = 17;

    private final PacketPlayerWindow window = new PacketPlayerWindow();

    @Test
    void aTurnAfterTheSignEditorOverAChestClosesDoesNotFlag() throws Exception {
        try (var f = chestOpen()) {
            openSignEditor(f, true);
            editSign(f);
            signUpdate(f);
            look(f, TURNED_YAW);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aTurnInTheSignEditorOverAChestFlags() throws Exception {
        try (var f = chestOpen()) {
            openSignEditor(f, true);
            editSign(f);
            look(f, TURNED_YAW);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void aSignUpdateWithNoSignEditorLeavesTheChestOpen() throws Exception {
        try (var f = chestOpen()) {
            editSign(f);
            signUpdate(f);
            look(f, TURNED_YAW);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void aSignUpdateBeforeTheClientHandledTheSignEditorLeavesTheChestOpen() throws Exception {
        try (var f = chestOpen()) {
            openSignEditor(f, false);
            signUpdate(f);
            editSign(f);
            look(f, TURNED_YAW);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void oneSignEditorClosesOnlyTheScreenItReplaced() throws Exception {
        try (var f = chestOpen()) {
            openSignEditor(f, true);
            signUpdate(f);
            openChest(f);
            f.tickEnd();
            signUpdate(f);
            look(f, TURNED_YAW);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void aWithheldSignUpdateDoesNotCloseALaterChest() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3)) {
            enableCheck(f);
            f.tickEnd();
            look(f, YAW);
            f.tickEnd();
            openSignEditor(f, true);
            f.tickEnd();
            openChest(f);
            f.tickEnd();
            signUpdate(f);
            look(f, TURNED_YAW);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void aChestTheServerOpensOverTheSignEditorStaysOpen() throws Exception {
        try (var f = chestOpen()) {
            openSignEditor(f, true);
            var open = openChestUnanswered(f);
            f.pongThrough(open - 1);
            signUpdate(f);
            f.pongThrough(open);
            f.tickEnd();
            look(f, TURNED_YAW);
            assertEquals(1, violations(f));
        }
    }

    private JavaTickFixture chestOpen() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3);
        enableCheck(f);
        f.tickEnd();
        look(f, YAW);
        f.tickEnd();
        openChest(f);
        f.tickEnd();
        return f;
    }

    private static void enableCheck(JavaTickFixture f) {
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
    }

    private void openChest(JavaTickFixture f) {
        f.pongThrough(openChestUnanswered(f));
    }

    private int openChestUnanswered(JavaTickFixture f) {
        var packet = new ClientboundOpenScreen(CHEST, "minecraft:generic_9x6");
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        window.onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private void openSignEditor(JavaTickFixture f, boolean answered) {
        var packet = ClientboundPackets.OPEN_SIGN_EDITOR.opaqueValue();
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SIGN_EDITOR, packet, false);
        window.onOpenSignEditor(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        if (answered) f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void editSign(JavaTickFixture f) {
        for (int tick = 0; tick < EDITOR_TICKS; tick++) f.tickEnd();
    }

    private void signUpdate(JavaTickFixture f) {
        var packet = ServerboundPackets.SIGN_UPDATE.opaqueValue();
        window.onSignUpdate(f.event(ServerboundPackets.SIGN_UPDATE, packet), f.player, packet);
    }

    private static void look(JavaTickFixture f, float yaw) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, PITCH, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(MultiActionsO.class).getViolations();
    }
}
