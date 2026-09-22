package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.prediction.FlagCaller;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;

public class BedrockMovementFlaggingTest {
    @Test public void flagsResumeExactlyFiveClientTicksAfterEachVehicleTransition() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var check = player.checkManager.getListener(BedrockMovement.class);
            assertTrue(check.canFlagMovement());
            player.packetStateData.acceptedClientTick = 10;
            check.onVehicleTeleport();
            for (int tick = 10; tick < 15; tick++) {
                player.packetStateData.acceptedClientTick = tick;
                assertFalse(check.canFlagMovement());
            }
            player.packetStateData.acceptedClientTick = 15;
            assertTrue(check.canFlagMovement());
            check.onVehicleMountSwitch();
            for (int tick = 15; tick < 20; tick++) {
                player.packetStateData.acceptedClientTick = tick;
                assertFalse(check.canFlagMovement());
            }
            player.packetStateData.acceptedClientTick = 20;
            assertTrue(check.canFlagMovement());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @Test public void dispatchesMovementViolationsButNotWhileARewindIsPending() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var check = player.checkManager.getListener(BedrockMovement.class);
            var result = new PredictionResult(player, null, null, Vec3.ZERO, null, null, null);
            result.addFlag(check, () -> "offset=1", 1);
            var caller = new FlagCaller(player);
            caller.onPredictionComplete(new PredictionComplete(result));
            assertEquals(1, check.violations, 0);

            var corrections = player.bedrockState.movementCorrections;
            var runtimeId = corrections.getClass().getDeclaredField("runtimeId");
            runtimeId.setAccessible(true);
            runtimeId.setLong(corrections, -1);
            corrections.observe(player, new BedrockMovementCorrection(1, corrections.generation(), -1, -1,
                    1, Vec3.ZERO, Vec3.ZERO, 0, 0, false, BedrockCoordinateFrame.IDENTITY, -1, null, false));
            assertTrue(corrections.hasPendingCorrection());
            caller.onPredictionComplete(new PredictionComplete(result));
            assertEquals(1, check.violations, 0);
            corrections.clear();
            caller.onPredictionComplete(new PredictionComplete(result));
            assertEquals(2, check.violations, 0);
            result.exempt();
            caller.onPredictionComplete(new PredictionComplete(result));
            assertEquals(2, check.violations, 0);

            var teleports = player.getSetbackTeleportUtil();
            teleports.addSentTeleport(Vec3.ZERO, 10,
                    new ac.cult.cultac.network.protocol.teleport.RelativeFlag(0), false, 1);
            teleports.addImmediateBedrockTransportTeleport(Vec3.ZERO, false, BedrockCoordinateFrame.IDENTITY,
                    null, new ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation(1,
                            ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance.CULT_SETBACK, 10), 10);
            assertTrue(teleports.isPendingSetback());
            assertFalse(check.canFlagMovement());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }
}
