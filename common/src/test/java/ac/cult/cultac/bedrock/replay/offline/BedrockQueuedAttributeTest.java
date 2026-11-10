package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Map;
import org.junit.Test;

public class BedrockQueuedAttributeTest {
    @Test
    public void anAttributeAcknowledgedBeforeTheHistoryIsResetStaysOnThePlayer() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
            process(player, 1);
            assertEquals(0.1F, state(player).movementAttribute().current(), 0);
            var corrections = player.bedrockState.movementCorrections;
            var self = player.compensatedEntities.getSelf();
            // Acknowledged between two inputs; a reset (an accepted teleport, a sleep change) drops the queue
            // before the next frame is prepared, and no later packet repeats either value.
            corrections.attributes(player, self, 1, movement(0.3F, true));
            corrections.attributes(player, self, 0, jumpStrength(0.42F));
            corrections.clear();
            assertEquals(0.3F, state(player).movementAttribute().current(), 0);
            assertEquals(0.42F, state(player).attributes().current("minecraft:horse.jump_strength", 0), 0);

            // Nothing is left to apply twice, and a reset with nothing queued changes nothing.
            var commit = player.checkManager.getSimulationProcessor().getCurrentPredictionCommit();
            corrections.clear();
            assertSame(
                    commit.carry(),
                    player.checkManager
                            .getSimulationProcessor()
                            .getCurrentPredictionCommit()
                            .carry());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void aSprintConfirmationDroppedWithTheQueueDoesNotOverwriteALaterState() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
            process(player, 1);
            var corrections = player.bedrockState.movementCorrections;
            var self = player.compensatedEntities.getSelf();
            corrections.attributes(player, self, 0, movement(0.13F, false));
            process(player, 2);
            assertEquals(0.13F, state(player).movementAttribute().current(), 0);
            // Stamped with tick 2 and equal to what tick 2 ended with: it changes nothing when applied
            // by a frame, so it must change nothing when the queue is dropped instead.
            corrections.attributes(player, self, 2, movement(0.13F, true));
            var commit = player.checkManager.getSimulationProcessor().getCurrentPredictionCommit();
            corrections.clear();
            assertSame(
                    commit.carry(),
                    player.checkManager
                            .getSimulationProcessor()
                            .getCurrentPredictionCommit()
                            .carry());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static ac.cult.cultac.checks.impl.prediction.PredictionResult process(CultPlayer player, long tick) {
        var position = new Vec3(2, 65, 3);
        var frame = BedrockAuthInputFrame.builder(player.user.getUUID())
                .protocolVersion(0)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(position)
                .packetPosition(position.add(0, 1.62, 0))
                .delta(Vec3.ZERO)
                .reportedEndOfTickVelocity(Vec3.ZERO)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .authorityMode("client-auth-input")
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull(result);
        return result;
    }

    private static BedrockMovementState state(CultPlayer player) {
        return BedrockProfileState.previousState(
                player.checkManager.getSimulationProcessor().getCurrentPredictionCommit().carry());
    }

    private static BedrockReplayAttributeEvent movement(float value, boolean stamped) {
        return new BedrockReplayAttributeEvent(
                Map.of("minecraft:movement", BedrockMovementAttributeState.serverValue(value, false)), stamped);
    }

    private static BedrockReplayAttributeEvent jumpStrength(float value) {
        return new BedrockReplayAttributeEvent(
                Map.of("minecraft:horse.jump_strength", BedrockMovementAttributeState.serverValue(value, false)), false);
    }
}
