package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The server sends two teleports before the client answers either. ClientPacketListener#handleMovePlayer handles them
 * in order, so a client that answered the first is about to be moved by the second: the newer teleport stays the
 * position a setback returns to. A client that answers only the first and then moves illegally must not be set back to
 * the first teleport's target, which would undo the second.
 */
class TeleportOrderSafePositionTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 FIRST = new Vec3(3.5, FLOOR_Y, 0.5);
    private static final Vec3 SECOND = new Vec3(6.5, FLOOR_Y, 0.5);

    @Test
    void anAnswerToAnOlderTeleportKeepsTheNewerTeleportSafe() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(START, new Vec3(0, -0.0784000015258789, 0));
            f.playerPosition(START, Vec3.ZERO, Set.of());
            f.tick(START, true);

            int firstLeading = send(f, 40, FIRST);
            send(f, 41, SECOND);
            assertEquals(
                    SECOND, f.player.getSetbackTeleportUtil().safePosition().getPos());

            answer(f, 40, firstLeading, FIRST);
            assertEquals(
                    SECOND,
                    f.player.getSetbackTeleportUtil().safePosition().getPos(),
                    "the answer to the older teleport replaced the newer one");

            // A move no tick from the first teleport's target reaches.
            f.tick(FIRST.add(0, 3, 0), false);
            SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(setback);
            assertFalse(setback.isPlugin(), "the illegal move was not set back");
            assertEquals(SECOND, setback.getTeleportData().getLocation(), "the setback undid the newer teleport");
        }
    }

    /** A server player_position with its leading and trailing pings; returns the leading ping. */
    private static int send(JavaTickFixture f, int id, Vec3 target) {
        var packet =
                new ClientboundPlayerPosition(id, JavaTickFixture.vec3d(target), new Vec3d(0, 0, 0), 0, 0, Set.of());
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        return leading;
    }

    /** The 26.3 acceptance, after the client answers the teleport's leading ping. */
    private static void answer(JavaTickFixture f, int id, int leading, Vec3 target) {
        f.pongThrough(leading);
        var packet = new ServerboundAcceptTeleportation(id, JavaTickFixture.vec3d(target), 0, 0);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the teleport acceptance was rejected");
    }
}
