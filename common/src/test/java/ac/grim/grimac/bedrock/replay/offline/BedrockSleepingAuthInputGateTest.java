package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.bedrock.prediction.BedrockPredictionTrigger;
import ac.grim.grimac.bedrock.protocol.BedrockAuthInputFrame;
import ac.grim.grimac.checks.impl.prediction.runner.SimulationProcessor;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BedrockSleepingAuthInputGateTest {
    @Test
    public void sleepingAuthInputCannotCommitItsAuthoredEndpoint() {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        player.x = 3.0D;
        player.y = 64.0D;
        player.z = -2.0D;
        SimulationProcessor simulation = player.checkManager.getSimulationProcessor();
        simulation.handleBedrockSleepingStateChange(true);

        BedrockAuthInputFrame frame = BedrockAuthInputFrame.builder(player.getUniqueId())
                .clientTick(17L)
                .position(new Vec3(3.25D, 64.5D, -1.75D))
                .packetPosition(new Vec3(3.25D, 64.5D, -1.75D))
                .delta(new Vec3(0.25D, 0.5D, 0.25D))
                .rotation(35.0F, 10.0F, 35.0F)
                .moveVector(1.0F, 1.0F)
                .build();

        assertNull(simulation.processBedrockAuthInputFrame(
                frame,
                BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE));
        assertEquals(3.0D, player.x, 0.0D);
        assertEquals(64.0D, player.y, 0.0D);
        assertEquals(-2.0D, player.z, 0.0D);
        assertTrue(simulation.isBedrockSleepingStateObserved());

        OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
    }
}
