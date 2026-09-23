package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedrockSandPushTest {
    @Test public void capturedSandPushIsValidatedAndCarriedToFollowingMovement() {
        runCapture(true);
    }

    @Test public void sameVelocityWithoutOverlappingSandIsRejected() {
        runCapture(false);
    }

    private static void runCapture(boolean sand) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var origin = new BedrockCoordinateFrame(0, -3456, 1);
            var start = new Vec3(2375.369873046875, 78, -3505.2573432922363);
            for (int x = 2373; x <= 2377; x++) for (int z = -3507; z <= -3503; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                player.compensatedWorld.updateBlock(x, 77, z, Blocks.STONE.defaultBlockState());
            }
            player.x = player.lastX = start.x;
            player.y = player.lastY = start.y;
            player.z = player.lastZ = start.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
            // Packet log 1790128900986, ticks 2076-2081. Reconstruct the local floor
            // and acknowledged sand at (2375,78,-3506); omit subsequent server rewinds.
            float[][] frames = {
                {2375.3699F, -49.257343F, 0, 0},
                {2375.3699F, -49.257343F, -0.047259465F, 0.088127986F},
                {2375.3225F, -49.169216F, -0.047281038F, 0.08811642F},
                {2375.2751F, -49.0811F, -0.047294535F, 0.08810918F},
                {2375.2278F, -48.992992F, -0.04730389F, 0.08810415F},
                {2375.1804F, -48.90489F, -0.04731071F, 0.0881005F}
            };
            for (int i = 0; i < (sand ? frames.length : 2); i++) {
                if (i == 1 && sand) player.compensatedWorld.updateBlock(2375, 78, -3506, Blocks.SAND.defaultBlockState());
                var row = frames[i];
                var feet = origin.toWorld(new Vec3(row[0], 78, row[1]));
                var velocity = new Vec3(row[2], -0.0784F, row[3]);
                var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                        .protocolVersion(2193).clientTick(2076 + i).inputMode(1).playMode(2).deviceId(3)
                        .coordinateFrame(origin).position(feet)
                        .packetPosition(new Vec3(row[0], 79.62001037597656, row[1]))
                        .rotation(-95.201935F, -78.8564F, -95.201935F).moveVector(0, 0)
                        .delta(velocity).reportedEndOfTickVelocity(velocity)
                        .rawInputFlags(1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal()).build();
                player.bedrockState.offerAuthInputFrame(frame);
                var result = player.checkManager.getSimulationProcessor()
                        .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
                assertNotNull("tick " + (2076 + i), result);
                var state = result.getProfileResult(BedrockPredictionResult.class).nextTickBaseState();
                assertNotNull(state);
                if (sand) {
                    assertEquals("tick " + (2076 + i), 0,
                            player.checkManager.getListener(BedrockMovement.class).violations, 0);
                    assertTrue(state.physicalFeetPosition().subtract(
                            new ac.cult.cultac.bedrock.prediction.geometry.Vec3d(feet.x, feet.y, feet.z)).length() <= 0.001);
                    assertTrue(state.velocity().subtract(
                            new ac.cult.cultac.bedrock.prediction.geometry.Vec3d(velocity.x, velocity.y, velocity.z)).length() <= 0.001);
                }
            }
            if (!sand) assertTrue(player.checkManager.getListener(BedrockMovement.class).violations > 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
