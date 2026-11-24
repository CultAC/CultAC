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
    // A 26.3 capture of `rotate @s facing ~ ~ ~5` over an open chest, and the look the client reported after it.
    private static final double SOUTH_X = 896.836566967495;
    private static final double SOUTH_Y = 72.0;
    private static final double SOUTH_Z = 1052.429891659464;
    private static final ClientboundPlayerLookAt FACING_SOUTH =
            new ClientboundPlayerLookAt(false, SOUTH_X, SOUTH_Y, 1057.429891659464, false, 0, false);

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

    /** Entity#lookAt scales Mth#atan2 by the float Mth#RAD_TO_DEG: due south it gives one float step below 90. */
    @Test
    void theLookAtAPointDueSouthOverAChestDoesNotFlag() throws Exception {
        try (var f = chestOpen(SOUTH_X, SOUTH_Y, SOUTH_Z, -86.37381f, 35.055176f)) {
            lookAt(f, FACING_SOUTH);
            f.tickEnd();
            look(f, -7.6293945E-6f, -0.0f);
            assertEquals(0, violations(f));
        }
    }

    private JavaTickFixture chestOpen() throws Exception {
        return chestOpen(X, Y, Z, YAW, PITCH);
    }

    private JavaTickFixture chestOpen(double x, double y, double z, float yaw, float pitch) throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3);
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.player.x = f.player.lastX = x;
        f.player.y = f.player.lastY = y;
        f.player.z = f.player.lastZ = z;
        f.tickEnd();
        look(f, yaw, pitch);
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
        lookAt(f, FACING);
    }

    private void lookAt(JavaTickFixture f, ClientboundPlayerLookAt packet) {
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_LOOK_AT, packet, false);
        teleport.onPlayerLookAt(event, f.player, packet);
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
