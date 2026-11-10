package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LivingEntity#tick runs aiStep for a dying player until LocalPlayer#tickDeath removes it at deathTime 20, so a dead
 * client still travels and sends its moves; none of them reaches the server.
 */
class DeadLocalPlayerTravelTest {
    private static final double X = -1148.6871780866577 + 1149;
    private static final double Z = -1017.0722963683052 + 1018;
    private static final double Y_SHIFT = FLOOR_Y - 84;
    private static final float YAW = -512.17426F;
    private static final float PITCH = 13.90925F;
    /** The dying client's sent y from its teleport answer to its landing on the floor. */
    private static final double[] SLIDE = {
        84.75220334025373,
        84.67380333872785,
        84.52380333276739,
        84.37380332680692,
        84.22380332084646,
        84.07380331488599,
        84.0
    };

    @Test
    void theTickAfterADyingPlayerLandsSendsNothingAndStaysPut() throws Exception {
        try (JavaTickFixture f = dyingInAVine()) {
            for (int tick = 0; tick < SLIDE.length; tick++) {
                f.move(at(SLIDE[tick]), tick == SLIDE.length - 1);
                f.tickEnd();
            }
            PredictionResult before = lastPrediction(f);
            f.tickEnd();
            PredictionResult landed = lastPrediction(f);
            assertNotSame(before, landed, "the idle tick committed no prediction");
            assertTrue(exact(landed), describe(landed));
        }
    }

    @Test
    void everyTickOfADyingPlayersSlideIsPredictedAndHeldFromTheServer() throws Exception {
        try (JavaTickFixture f = dyingInAVine()) {
            for (int tick = 0; tick < SLIDE.length; tick++) {
                PredictionResult before = lastPrediction(f);
                PacketReceiveEvent<ServerboundMovePlayer> move = f.move(at(SLIDE[tick]), tick == SLIDE.length - 1);
                f.tickEnd();
                assertTrue(move.isCancelled(), "tick " + tick + ": a dead player's move reached the server");
                PredictionResult result = lastPrediction(f);
                assertNotSame(before, result, "tick " + tick + " committed no prediction");
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
            }
        }
    }

    @Test
    void aDyingPlayerClimbingTheVineStillFlags() throws Exception {
        try (JavaTickFixture f = dyingInAVine()) {
            f.move(at(SLIDE[0]), false);
            f.tickEnd();
            PredictionResult before = lastPrediction(f);
            PacketReceiveEvent<ServerboundMovePlayer> rise = f.move(at(SLIDE[0] + 0.42), false);
            f.tickEnd();
            assertTrue(rise.isCancelled(), "a dead player's move reached the server");
            assertNotSame(before, lastPrediction(f), "the rise committed no prediction");
            assertRejected(lastPrediction(f), "VerticalOffset");
        }
    }

    /** 01a1b68d-6b7a seq 32265: killed in a vine column, then placed back above the floor it slides down to. */
    private static JavaTickFixture dyingInAVine() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.VINE.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        f.teleport(f.player.sendTrailingTransaction(), at(SLIDE[0]), new RelativeFlag(0), YAW, PITCH, YAW, PITCH);
        var health = new ClientboundSetHealth(0.0F, 17, 9.0F);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_HEALTH, health, false);
        new PacketPlayerRespawn().onSetHealth(event, f.player, health);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        return f;
    }

    private static Vec3 at(double y) {
        return new Vec3(X, y + Y_SHIFT, Z);
    }

    private static PredictionResult lastPrediction(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }
}
