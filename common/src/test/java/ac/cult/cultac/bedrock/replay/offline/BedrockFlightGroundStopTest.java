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

    // Flight granted on the ground the tick jump is pressed: the client rises 0.071602 = 0.15 - 0.0784,
    // reports 0.042961, and still stops flying, though the move ends in the air.
    @Test
    public void aFlyingTickThatStartsOnTheGroundEndsSurvivalFlightWhereverItsMoveEnds() {
        var player = groundedPlayer();
        try {
            Vec3 ground = new Vec3(player.x, player.y, player.z);
            assertNotNull(process(player, frame(player, 1, ground, -0.0784, true)));
            grantFlight(player);
            var jump = builder(player, 2, new Vec3(ground.x, ground.y + 0.071602, ground.z), 0.042961)
                    .jumping(true)
                    .wantUp(true)
                    .rawInputFlags(flag(PlayerAuthInputData.JUMPING) | flag(PlayerAuthInputData.WANT_UP))
                    .build();
            assertNotNull(process(player, jump));
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            assertFalse(player.isFlying);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    // Descending onto the ground, the client flies the landing tick and the next one, and stops
    // flying only after the tick that started on the ground.
    @Test
    public void aFlyingTickThatLandsKeepsSurvivalFlightForTheNextTick() {
        var player = groundedPlayer();
        try {
            Vec3 ground = new Vec3(player.x, player.y, player.z);
            Vec3 aloft = new Vec3(ground.x, ground.y + 0.5, ground.z);
            player.y = player.lastY = aloft.y;
            player.onGround = player.lastOnGround = false;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, aloft.x, aloft.y, aloft.z);
            grantFlight(player);
            assertNotNull(process(player, frame(player, 1, aloft, 0, false)));
            assertNotNull(process(player, descend(player, 2, new Vec3(aloft.x, aloft.y - 0.22, aloft.z), -0.132, false)));
            assertNotNull(process(player, descend(player, 3, ground, 0, true)));
            assertTrue(player.isFlying);
            assertNotNull(process(player, frame(player, 4, ground, 0, true)));
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            assertFalse(player.isFlying);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static CultPlayer groundedPlayer() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        player.gamemode = GameMode.SURVIVAL;
        player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++)
                player.compensatedWorld.updateBlock(
                        x, 105, z, DataTables.defaults().registry().block("minecraft:stone").defaultState());
        Vec3 ground = new Vec3(1.5, 106, 1.5);
        player.x = player.lastX = ground.x;
        player.y = player.lastY = ground.y;
        player.z = player.lastZ = ground.z;
        player.onGround = player.lastOnGround = true;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, ground.x, ground.y, ground.z);
        return player;
    }

    private static void grantFlight(CultPlayer player) {
        player.canFly = true;
        player.isFlying = true;
        player.abilitiesHandled(false, false);
    }

    private static BedrockAuthInputFrame descend(
            CultPlayer player, long tick, Vec3 position, double velocity, boolean landed) {
        return builder(player, tick, position, velocity)
                .sneaking(true)
                .rawInputFlags(flag(PlayerAuthInputData.SNEAKING)
                        | flag(PlayerAuthInputData.SNEAK_DOWN)
                        | flag(PlayerAuthInputData.WANT_DOWN)
                        | (landed ? flag(PlayerAuthInputData.VERTICAL_COLLISION) : 0L))
                .build();
    }

    private static BedrockAuthInputFrame frame(
            CultPlayer player, long tick, Vec3 position, double velocity, boolean grounded) {
        return builder(player, tick, position, velocity)
                .rawInputFlags(grounded ? flag(PlayerAuthInputData.VERTICAL_COLLISION) : 0L)
                .build();
    }

    private static BedrockAuthInputFrame.Builder builder(CultPlayer player, long tick, Vec3 position, double velocity) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .reportedEndOfTickVelocity(new Vec3(0, velocity, 0));
    }

    private static long flag(PlayerAuthInputData data) {
        return 1L << data.ordinal();
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
