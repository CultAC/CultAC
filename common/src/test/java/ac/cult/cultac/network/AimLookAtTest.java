package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.aim.AimDuplicateLook;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerLookAt;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleLookAt turns the player with Entity#lookAt and sends nothing, leaving LocalPlayer#yRotLast
 * and xRotLast at the last rotation sendPosition sent, so the next tick's sendPosition reports the new look. The
 * position and look come from a 26.3 capture of {@code rotate @s facing ~5 ~5 ~}.
 */
class AimLookAtTest {
    private static final Vec3 FACING_AT = new Vec3(894.4738786097156, 72.0, 1067.1178436671285);
    private static final ClientboundPlayerLookAt FACING =
            new ClientboundPlayerLookAt(false, 899.4738786097156, 77.0, 1067.1178436671285, false, 0, false);
    private static final float FACING_YAW = -90.0f;
    private static final float FACING_PITCH = -44.999996f;

    @Test
    void theRotReportingTheLookOfALookAtIsNoDuplicateLook() throws Exception {
        try (var f = fixture(AimDuplicateLook.class, FACING_AT)) {
            look(f, 12.5f, 20.0f);
            lookAt(f, FACING);
            look(f, FACING_YAW, FACING_PITCH);
            assertEquals(0, violations(f, AimDuplicateLook.class));
        }
    }

    @Test
    void aRotRepeatingTheLookOfALookAtFlags() throws Exception {
        try (var f = fixture(AimDuplicateLook.class, FACING_AT)) {
            look(f, 12.5f, 20.0f);
            lookAt(f, FACING);
            look(f, FACING_YAW, FACING_PITCH);
            look(f, FACING_YAW, FACING_PITCH);
            assertEquals(1, violations(f, AimDuplicateLook.class));
        }
    }

    private static JavaTickFixture fixture(Class<? extends Check> checked, Vec3 at) throws Exception {
        var f = new JavaTickFixture();
        Check check = f.player.checkManager.getCheck(checked);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.player.x = f.player.lastX = at.x;
        f.player.y = f.player.lastY = at.y;
        f.player.z = f.player.lastZ = at.z;
        f.tickEnd();
        return f;
    }

    private static void lookAt(JavaTickFixture f, ClientboundPlayerLookAt packet) {
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_LOOK_AT, packet, false);
        new PacketServerTeleport().onPlayerLookAt(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** One client tick whose sendPosition sends only a Rot. */
    private static void look(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.tickEnd();
    }

    private static double violations(JavaTickFixture f, Class<? extends Check> checked) {
        return f.player.checkManager.getCheck(checked).getViolations();
    }
}
