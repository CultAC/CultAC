package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/** Exercises the compensated block sample, movement check and carried state together. */
public class BedrockGeyserTest {
    @Test
    public void aDistantSourceLaunchesAndCarriesThroughTheTopOfThePlume() {
        run(true, false, false);
    }

    @Test
    public void aDormantUpdateStopsTheLaunchDespiteARetainedEffect() {
        run(true, false, true);
    }

    @Test
    public void theSameVelocityWithoutAGeyserIsRejected() {
        run(false, false, false);
    }

    @Test
    public void extraVelocityInsideAValidPlumeIsRejected() {
        run(true, true, false);
    }

    private static void run(boolean source, boolean extra, boolean stop) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(279 >> 4, -108 >> 4);
            var registry = DataTables.defaults().registry();
            if (source) {
                player.compensatedWorld.updateBlock(
                        279,
                        64,
                        -108,
                        registry.with(
                                registry.block("minecraft:potent_sulfur").defaultState(),
                                "potent_sulfur_state",
                                "continuous"));
                for (int y = 65; y <= 68; y++)
                    player.compensatedWorld.updateBlock(
                            279, y, -108, registry.block("minecraft:water").defaultState());
            }
            if (source) {
                // These shapes depend on the moving actor. Column scans use their independent shapes.
                player.compensatedWorld.updateBlock(
                        279, 75, -108, registry.block("minecraft:scaffolding").defaultState());
                player.compensatedWorld.updateBlock(
                        279, 76, -108, registry.block("minecraft:powder_snow").defaultState());
            }
            // Rest above the source, then remove the intervening floor. The initial fall reaches its plume
            // while the source is nineteen blocks below the player, outside a normal collision sample.
            player.compensatedWorld.updateBlock(
                    279, 82, -108, registry.block("minecraft:stone").defaultState());
            player.x = player.lastX = 279.5;
            player.y = player.lastY = 83;
            player.z = player.lastZ = -107.5;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
            process(player, 0, 83, -0.0784F, true);
            process(player, 1, 83, -0.0784F, true);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            player.compensatedWorld.updateBlock(
                    279, 82, -108, registry.block("minecraft:air").defaultState());
            float y = 83, velocity = -0.0784F;
            for (int tick = 2; tick < (source && !extra ? stop ? 20 : 60 : 3); tick++) {
                if (stop && tick == 10)
                    player.compensatedWorld.updateBlock(
                            279,
                            64,
                            -108,
                            registry.with(
                                    registry.block("minecraft:potent_sulfur").defaultState(),
                                    "potent_sulfur_state",
                                    "dormant"));
                y += velocity;
                velocity = (velocity - 0.08F) * 0.98F;
                if ((!stop || tick < 10) && y < 89 && velocity < 0.7F) velocity += 0.2F;
                var prediction = process(player, tick, y, velocity + (extra ? 0.01F : 0), false);
                if (source && !extra) {
                    var observation = prediction.observation();
                    assertNotNull("tick " + tick, observation);
                    assertEquals("tick " + tick + " velocity", 0, observation.velocityOffset(), 0.001);
                    assertEquals("tick " + tick + " position", 0, observation.positionOffset(), 0.001);
                    assertEquals(
                            "tick " + tick, 0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
                }
            }
            if (!source || extra) assertTrue(player.checkManager.getListener(BedrockMovement.class).violations > 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockPredictionResult process(
            CultPlayer player, int tick, float y, float velocity, boolean ground) {
        var v = new Vec3(0, velocity, 0);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2225)
                .clientTick(5000 + tick)
                .inputMode(1)
                .playMode(2)
                .position(new Vec3(279.5, y, -107.5))
                .packetPosition(new Vec3(279.5, y + 1.62001F, -107.5))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .delta(v)
                .reportedEndOfTickVelocity(v)
                .rawInputFlags(ground ? 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal() : 0)
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull(result);
        return result.getProfileResult(BedrockPredictionResult.class);
    }
}
