package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsD;
import ac.cult.cultac.checks.impl.groundspoof.NoFallExecutor;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** Paper clamps the pitch to [-90, 90] (Entity#setXRot), so BadPacketsD forwards the move with that pitch. */
class JavaInvalidPitchTest {
    private static final Vec3 START = new Vec3(.5, FLOOR_Y + 6, .5);
    private static final Vec3 FALLEN = START.add(0, -.3, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void aFallWithAnInvalidPitchReachesTheServerWithTheClampedPitch() throws Exception {
        try (var f = falling()) {
            PacketReceiveEvent<ServerboundMovePlayer> event = move(f, 100.0F, false);
            PredictionResult result =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();

            assertTrue(exact(result), describe(result));
            assertFalse(event.isCancelled(), "the server lost a predicted movement");
            assertEquals(90.0F, event.getPacket().pitch());
            assertEquals(FALLEN.y, event.getPacket().y());
            assertEquals(90.0F, f.player.yRot);
        }
    }

    @Test
    void thePitchCorrectionKeepsNoFallsGroundRewrite() throws Exception {
        try (var f = falling()) {
            f.player.checkManager.getListener(NoFallExecutor.class).setEnabled(true);
            PacketReceiveEvent<ServerboundMovePlayer> event = move(f, -100.0F, true);

            assertRejected(f.player.checkManager.getSimulationProcessor().getLastPrediction(), "GroundSpoof");
            assertFalse(event.getPacket().onGround(), "the server received the spoofed ground");
            assertEquals(-90.0F, event.getPacket().pitch());
        }
    }

    private static JavaTickFixture falling() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.checkManager.getListener(BadPacketsD.class).setEnabled(true);
        f.place(START, new Vec3(0, -.3, 0));
        f.pongThrough(f.player.sendTrailingTransaction());
        f.input(NONE);
        return f;
    }

    private static PacketReceiveEvent<ServerboundMovePlayer> move(JavaTickFixture f, float pitch, boolean onGround) {
        var packet = new ServerboundMovePlayer(
                FALLEN.x, FALLEN.y, FALLEN.z, f.player.xRot, pitch, onGround, false, true, true);
        PacketReceiveEvent<ServerboundMovePlayer> event = f.event(ServerboundPackets.MOVE_PLAYER, packet);
        f.listener.onMovePlayer(event, f.player, packet);
        f.tickEnd();
        return event;
    }
}
