package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.groundspoof.NoFallExecutor;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A client at rest above the floor that sent no position for a tick, so its next move may skip that tick's gravity and
 * fall anywhere down to the floor. Entity#move sets onGround only when collision stops a downward move, so a move that
 * ends above the floor claims the ground falsely however far it could have fallen, and the server must not reset its
 * fall distance there.
 */
class JavaGroundClaimAboveFloorTest {
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 0.05, 0.5);
    /** Half of LivingEntity#travelInAir's first fall from rest: within the skipped tick's reach, short of the floor. */
    private static final Vec3 SHORT_OF_FLOOR = HOVER.add(0, -0.08 * 0.98F / 2, 0);

    /** The control: the first fall from rest stops on the floor and claims it. */
    @Test
    void aFallThatStopsOnTheFloorClaimsTheGround() throws Exception {
        try (var f = restingAfterAWithheldTick(HOVER)) {
            PredictionResult result = f.tick(new Vec3(HOVER.x, FLOOR_Y, HOVER.z), true);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void aGroundClaimShortOfTheFloorReachesTheServerOffTheGround() throws Exception {
        try (var f = restingAfterAWithheldTick(HOVER)) {
            PacketReceiveEvent<ServerboundMovePlayer> move = f.move(SHORT_OF_FLOOR, true);
            f.tickEnd();
            PredictionResult result =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();

            assertRejected(result, "GroundSpoof");
            assertFalse(move.getPacket().onGround(), "the server received the claimed ground");
            assertEquals(
                    DesyncStatus.FALSE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());
        }
    }

    /**
     * Entity#move keeps the player where it was when collision leaves a move within sqrt(1.0E-7), yet still sets
     * onGround, so LocalPlayer#sendPosition sends StatusOnly from above the floor.
     */
    @Test
    void aClipTheSetPosGuardDropsStillClaimsTheGround() throws Exception {
        Vec3 justAbove = new Vec3(0.5, FLOOR_Y + 1.0E-4, 0.5);
        try (var f = restingAfterAWithheldTick(justAbove)) {
            var status = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, true, false, false, false);
            PacketReceiveEvent<ServerboundMovePlayer> event = f.event(ServerboundPackets.MOVE_PLAYER, status);
            f.listener.onMovePlayer(event, f.player, status);
            f.tickEnd();

            assertTrue(event.getPacket().onGround(), "the server lost the ground");
            assertEquals(0, f.player.checkManager.getListener(NoFallExecutor.class).violations);
            assertEquals(
                    DesyncStatus.TRUE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());
        }
    }

    private static JavaTickFixture restingAfterAWithheldTick(Vec3 at) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.checkManager.getListener(NoFallExecutor.class).setEnabled(true);
        f.place(at, Vec3.ZERO);
        // LocalPlayer#sendPosition sends nothing for a tick that neither moves nor changes onGround.
        f.tickEnd();
        return f;
    }
}
