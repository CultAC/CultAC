package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.protocol.*;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * Geyser emits a teleport again every 20 inputs its client sends away from the target. When the server's packets reach
 * the client late, such a retry is written before the client's answer to the first emission arrives, and the client
 * takes it after it answered the first one and moved on: each emission puts the client back on the target and is
 * answered by its own input. Delivery pattern of the lossy-link captures: the teleport is answered, the player walks,
 * and a retry of it written before that answer arrived sets the player back on the target.
 */
public class BedrockTeleportRetryAnswerTest {
    private static final BedrockCoordinateFrame ORIGIN = new BedrockCoordinateFrame(20112, 20208, 1);

    @Test
    public void aRetryWrittenBeforeTheFirstAnswerWaitsForItsOwnAnswer() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var teleports = player.getSetbackTeleportUtil().bedrockTeleports();
            Vec3 local = new Vec3(-81.5, 120, -153.5);
            Vec3 world = ORIGIN.toWorld(local);
            var operation = new BedrockTeleportOperation(7, BedrockTeleportProvenance.GEYSER, null);
            long first =
                    teleports.addImmediateBedrockTransportTeleport(world, true, ORIGIN, packet(local), operation, 10);
            long retry =
                    teleports.addImmediateBedrockTransportTeleport(world, true, ORIGIN, packet(local), operation, 30);

            // The first emission and its receipt are handled; the retry is still on its way.
            player.lastTransactionReceived.set(11);
            teleports.confirmBedrockOrigin(first, operation, ORIGIN, packet(local));
            assertNotNull(teleports.unansweredBedrockTransport(retry));
            assertFalse(teleports.unansweredBedrockTransport(retry).isBedrockOriginConfirmed());
            assertTrue(teleports
                    .acknowledgeBedrockTeleportFrame(teleports.resolveBedrockCoordinates(input(player, 100, local)))
                    .isTeleport());
            assertTrue(teleports.mustAcknowledgeBedrockTransportTeleport());
            assertNotNull(teleports.unansweredBedrockTransport(retry));

            // The retry puts the client back on the target and its next input answers it.
            player.lastTransactionReceived.set(31);
            teleports.confirmBedrockOrigin(retry, operation, ORIGIN, packet(local));
            var answer = teleports.acknowledgeBedrockTeleportFrame(
                    teleports.resolveBedrockCoordinates(input(player, 140, local)));
            assertTrue(answer.isTeleport());
            assertEquals(world, answer.getTeleportData().getLocation());
            assertFalse(teleports.mustAcknowledgeBedrockTransportTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void aRetryTakenAfterThePlayerWalkedOnIsItsTeleportAnswer() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 4; x++)
                for (int z = 0; z < 4; z++)
                    player.compensatedWorld.updateBlock(
                            x,
                            105,
                            z,
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:stone")
                                    .defaultState());
            Vec3 ground = new Vec3(1.5, 106, 1.5);
            player.x = player.lastX = ground.x;
            player.y = player.lastY = ground.y;
            player.z = player.lastZ = ground.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, ground.x, ground.y, ground.z);
            assertNotNull(process(player, frame(player, 2969, ground, -0.0784, false)));

            var teleports = player.getSetbackTeleportUtil().bedrockTeleports();
            Vec3 start = new Vec3(2.5, 106, 1.5);
            var operation = new BedrockTeleportOperation(7, BedrockTeleportProvenance.GEYSER, null);
            long first = teleports.addImmediateBedrockTransportTeleport(
                    start, true, BedrockCoordinateFrame.IDENTITY, packet(start), operation, 10);
            long retry = teleports.addImmediateBedrockTransportTeleport(
                    start, true, BedrockCoordinateFrame.IDENTITY, packet(start), operation, 30);

            player.lastTransactionReceived.set(11);
            teleports.confirmBedrockOrigin(first, operation, BedrockCoordinateFrame.IDENTITY, packet(start));
            assertNotNull(process(player, frame(player, 2970, start, 0, true)));
            assertTrue(teleports.mustAcknowledgeBedrockTransportTeleport());

            // The client walks from the start while the retry is on its way (captured sneak deltas).
            double[] walked = {-0.00588, -0.01711, -0.05673};
            for (int i = 0; i < walked.length; i++) {
                assertNull(process(player, frame(player, 2971 + i, start.add(walked[i], 0, 0), -0.0784, false)));
            }

            player.lastTransactionReceived.set(31);
            teleports.confirmBedrockOrigin(retry, operation, BedrockCoordinateFrame.IDENTITY, packet(start));
            assertNotNull(process(player, frame(player, 2974, start, 0, true)));
            assertFalse(teleports.mustAcknowledgeBedrockTransportTeleport());
            assertNotNull(process(player, frame(player, 2975, start, -0.0784, false)));
            assertEquals(
                    0,
                    player.checkManager
                            .getSimulationProcessor()
                            .getLastPrediction()
                            .getOffset(),
                    1.0E-5);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static Object process(CultPlayer player, BedrockAuthInputFrame frame) {
        return BedrockFrameProcessor.process(player, frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
    }

    private static Vec3 packet(Vec3 feet) {
        return feet.add(0, 1.6200103759765625, 0);
    }

    private static BedrockAuthInputFrame input(CultPlayer player, long tick, Vec3 local) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .clientTick(tick)
                .position(local)
                .packetPosition(packet(local))
                .rawInputFlags(1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal())
                .build();
    }

    private static BedrockAuthInputFrame frame(
            CultPlayer player, long tick, Vec3 position, double velocity, boolean teleport) {
        long flags = teleport
                ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal()
                : 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .packetPosition(packet(position))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .reportedEndOfTickVelocity(new Vec3(0, velocity, 0))
                .rawInputFlags(flags)
                .build();
    }
}
