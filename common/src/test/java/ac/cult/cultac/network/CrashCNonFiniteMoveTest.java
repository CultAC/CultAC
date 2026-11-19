package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.crash.CrashC;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import org.junit.jupiter.api.Test;

/**
 * ServerGamePacketListenerImpl#handleMovePlayer disconnects any move carrying a non-finite value, rotation-only moves
 * included. CrashC must reject such a move at the early receive stage, before CheckManagerListener writes the rotation
 * or starts the client tick.
 */
class CrashCNonFiniteMoveTest {
    @Test
    void aRotationOnlyMoveWithNonFiniteYawOrPitchIsRejectedBeforeItsRotationIsApplied() throws Exception {
        float[][] rotations = {
            {Float.NaN, 0}, {Float.POSITIVE_INFINITY, 0}, {Float.NEGATIVE_INFINITY, 0}, {0, Float.NaN}
        };
        for (float[] rotation : rotations) {
            try (var f = new JavaTickFixture()) {
                float yaw = f.player.xRot;
                float pitch = f.player.yRot;
                var rot = new ServerboundMovePlayer(0, 0, 0, rotation[0], rotation[1], true, false, false, true);
                var event = receive(f, rot);

                assertTrue(event.isCancelled(), "a non-finite rotation reached the movement listener");
                assertEquals(1, f.player.checkManager.getCheck(CrashC.class).getViolations());
                assertEquals(yaw, f.player.xRot);
                assertEquals(pitch, f.player.yRot);
            }
        }
    }

    @Test
    void aNonFinitePositionIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            var pos = new ServerboundMovePlayer(Double.NaN, 64, 0.5, 0, 0, true, false, true, false);
            var event = receive(f, pos);

            assertTrue(event.isCancelled());
            assertEquals(1, f.player.checkManager.getCheck(CrashC.class).getViolations());
        }
    }

    @Test
    void finiteMovesOfEveryShapePass() throws Exception {
        try (var f = new JavaTickFixture()) {
            ServerboundMovePlayer[] moves = {
                new ServerboundMovePlayer(0, 0, 0, 179.5f, -12.25f, true, false, false, true),
                new ServerboundMovePlayer(0.5, 64, 0.5, 0, 0, true, false, true, false),
                new ServerboundMovePlayer(0.5, 64, 0.5, 90, 45, true, false, true, true),
                new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, false)
            };
            for (var move : moves) {
                var event = f.event(ServerboundPackets.MOVE_PLAYER, move);
                f.player.checkManager.dispatchEarlyReceive(event);
                assertFalse(event.isCancelled(), "the early stage rejected a finite move");
            }
            assertEquals(0, f.player.checkManager.getCheck(CrashC.class).getViolations());
        }
    }

    /** The decoder's early stage; an early cancel skips every ordinary handler. */
    private static PacketReceiveEvent<ServerboundMovePlayer> receive(JavaTickFixture f, ServerboundMovePlayer packet) {
        var event = f.event(ServerboundPackets.MOVE_PLAYER, packet);
        f.player.checkManager.dispatchEarlyReceive(event);
        if (!event.isCancelled()) f.listener.onMovePlayer(event, f.player, packet);
        return event;
    }
}
