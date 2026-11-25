package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsV;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class ClientPositionCacheTest {
    @Test
    void teleportResponseDoesNotReplaceTheSendPositionCache() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_2)) {
            var check = spy(new BadPacketsV(f.player));
            doReturn(true).when(check).alert(any(java.util.function.Supplier.class));
            f.player.setExperimentalChecks(true);
            f.player.lastX = 0.5;
            f.player.lastY = 64.0;
            f.player.lastZ = 0.5;
            var packet = new ServerboundMovePlayer(0.5, 64.0, 0.5, 0, 0, true, false, true, false);
            var event = new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet);
            var sent = f.player.packetStateData.sentPositionCache;
            event.setAnswersTeleport(true);
            check.onMovePlayer(event, f.player, packet);
            event.setAnswersTeleport(false);
            sent.onTickMovement(true, new Vec3(0.5, 64.0, 0.5));
            check.onMovePlayer(event, f.player, packet);
            assertEquals(0, check.getViolations());
            sent.onTickMovement(true, new Vec3(0.5, 64.0, 0.5));
            check.onMovePlayer(event, f.player, packet);
            assertEquals(1, check.getViolations());
            sent.onNewLocalPlayer();
            sent.onTickMovement(true, new Vec3(0.5, 64.0, 0.5));
            check.onMovePlayer(event, f.player, packet);
            assertEquals(1, check.getViolations());
        }
    }
}
