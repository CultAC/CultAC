package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedrockMovementExemptionTest {
    @Test public void flyingExemptionFollowsObservationAndNormalChecksResume() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.canFly = true;
            player.isFlying = true;
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            var processor = player.checkManager.getSimulationProcessor();
            var observed = new Vec3(2, 65, 3);
            var velocity = new Vec3(0.17, 0.06, 0.03);
            var frame = frame(player, 1, observed, velocity);
            player.bedrockState.offerAuthInputFrame(frame);
            var result = processor.processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
            assertNotNull(result);
            assertTrue(result.isExempt());
            var state = BedrockProfileState.previousState(processor.getCurrentPredictionCommit().carry());
            assertEquals(observed.x, state.physicalFeetPosition().x(), 0.001);
            assertEquals(observed.y, state.physicalFeetPosition().y(), 0.001);
            assertEquals(observed.z, state.physicalFeetPosition().z(), 0.001);
            assertEquals(velocity.x, state.velocity().x(), 0.000001);
            assertEquals(velocity.y, state.velocity().y(), 0.000001);
            var corrections = player.bedrockState.movementCorrections;
            var pending = corrections.getClass().getDeclaredField("pending");
            pending.setAccessible(true);
            assertNull(pending.get(corrections));
            var rewindField = corrections.getClass().getDeclaredField("rewind");
            rewindField.setAccessible(true);
            var rewind = rewindField.get(corrections);
            var historyField = rewind.getClass().getDeclaredField("authoritative");
            historyField.setAccessible(true);
            var history = (ac.cult.cultac.bedrock.prediction.integration.BedrockActorHistory) historyField.get(rewind);
            assertTrue(history.frames().isEmpty());

            player.canFly = false;
            player.isFlying = false;
            var next = frame(player, 2, observed.add(5, 0, 0), Vec3.ZERO);
            player.bedrockState.offerAuthInputFrame(next);
            var checked = processor.processBedrockAuthInputFrame(next, BedrockPredictionTrigger.OFFLINE_REPLAY);
            assertNotNull(checked);
            assertFalse(checked.isExempt());
            assertTrue(checked.getFlagSeverity() > 0);
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick, Vec3 position, Vec3 velocity) {
        return BedrockAuthInputFrame.builder(player.user.getUUID()).protocolVersion(0).clientTick(tick)
            .inputMode(1).playMode(2).deviceId(3).position(position)
            .packetPosition(position.add(0, 1.62, 0)).delta(Vec3.ZERO)
            .reportedEndOfTickVelocity(velocity).rotation(0, 0, 0).moveVector(0, 0)
            .authorityMode("client-auth-input").build();
    }
}
