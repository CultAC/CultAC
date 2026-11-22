package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerLookAt;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleLookAt turns the player with Entity#lookAt, which takes the look from the player's own
 * feet or eyes to the point with Mth#atan2, and sends nothing; LocalPlayer#sendPosition reports the look on the next
 * tick, a container screen open or not. The position and look come from a 26.3 capture of {@code rotate @s facing ^5 ^
 * ^} over an open chest; the point is LocalCoordinates#apply from the server's look.
 */
class MultiActionsOLookAtTest {
    private static final int CHEST = 2;
    private static final double X = -329.2290420147618;
    private static final double Y = 76.0;
    private static final double Z = -423.1577337794673;
    private static final float YAW = 2.3895304f;
    private static final float PITCH = 24.1535f;
    private static final ClientboundPlayerLookAt FACING = new ClientboundPlayerLookAt(
            false, -324.23356855601685, 75.9999999989495, -422.949276184562, false, 0, false);
    private static final float LOOK_YAW = -87.61036f;
    private static final float LOOK_PITCH = 1.20195285E-8f;

    private final PacketPlayerWindow window = new PacketPlayerWindow();
    private final PacketServerTeleport teleport = new PacketServerTeleport();

    @Test
    void theLookAServerLookAtGaveOverAChestDoesNotFlag() throws Exception {
        try (var f = chestOpen()) {
            lookAt(f);
            f.tickEnd();
            look(f, LOOK_YAW, LOOK_PITCH);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aTurnPastTheLookAServerLookAtGaveOverAChestFlags() throws Exception {
        try (var f = chestOpen()) {
            lookAt(f);
            f.tickEnd();
            look(f, LOOK_YAW + 0.5f, LOOK_PITCH);
            assertEquals(1, violations(f));
        }
    }

    private JavaTickFixture chestOpen() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3);
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.player.x = f.player.lastX = X;
        f.player.y = f.player.lastY = Y;
        f.player.z = f.player.lastZ = Z;
        f.tickEnd();
        look(f, YAW, PITCH);
        f.tickEnd();
        var packet = new ClientboundOpenScreen(CHEST, "minecraft:generic_9x3");
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        window.onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        f.tickEnd();
        f.tickEnd();
        return f;
    }

    private void lookAt(JavaTickFixture f) {
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_LOOK_AT, FACING, false);
        teleport.onPlayerLookAt(event, f.player, FACING);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void look(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(MultiActionsO.class).getViolations();
    }
}
