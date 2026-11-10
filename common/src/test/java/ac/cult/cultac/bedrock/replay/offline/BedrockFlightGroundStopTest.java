package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

public class BedrockFlightGroundStopTest {
    // A capture of flight granted to a survival player standing still: the first flying
    // tick reports no movement and a zero delta, and the client flies no further.
    @Test
    public void aFlyingTickThatEndsOnTheGroundEndsSurvivalFlight() {
        assertFalse(flyingAfterAGroundedFlyingTick(GameMode.SURVIVAL, false));
    }

    // With instant building, flight granted on the ground goes on: the client hovers.
    @Test
    public void groundContactDoesNotEndFlightWithInstantBuilding() {
        assertTrue(flyingAfterAGroundedFlyingTick(GameMode.CREATIVE, true));
    }

    private static boolean flyingAfterAGroundedFlyingTick(GameMode mode, boolean instabuild) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = mode;
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
            assertNotNull(process(player, frame(player, 1, ground, -0.0784)));
            player.canFly = true;
            player.isFlying = true;
            player.abilitiesHandled(instabuild, false);
            assertNotNull(process(player, frame(player, 2, ground, 0)));
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            return player.isFlying;
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static Object process(CultPlayer player, BedrockAuthInputFrame frame) {
        return BedrockFrameProcessor.process(player, frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick, Vec3 position, double velocity) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .reportedEndOfTickVelocity(new Vec3(0, velocity, 0))
                .rawInputFlags(1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal())
                .build();
    }
}
