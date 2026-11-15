package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A velocity packet can carry one tick's move several blocks past the region sampled around the resting player. A
 * capture of a 7.9 block/tick pulse along a wall shows the client still blocked by the wall where the move ends: its
 * Z stays put and it reports no Z velocity on the following tick.
 */
public class BedrockImpulseCollisionSampleTest {
    private static final BedrockCoordinateFrame ORIGIN = new BedrockCoordinateFrame(20016, 20064, 1);
    private static final float START_X = 17.440693F;
    private static final float START_Z = 0.7F;
    private static final float PULSE_X = 7.9F;
    private static final float PULSE_Y = 0.1F;
    private static final float GROUND_FRICTION = 0.6F * 0.91F;

    @Test
    public void wallBeyondTheRestingSampleStopsTheInputOfAKnockedBackPlayer() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 20030; x <= 20046; x++) {
                for (int z = 20062; z <= 20065; z++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                    player.compensatedWorld.updateBlock(x, 119, z, stone);
                }
                // The wall the player slides along. It runs the whole length of the pulse.
                player.compensatedWorld.updateBlock(x, 120, 20065, stone);
                player.compensatedWorld.updateBlock(x, 121, 20065, stone);
            }
            Vec3 start = ORIGIN.toWorld(new Vec3(START_X, 120, START_Z));
            player.x = player.lastX = start.x;
            player.y = player.lastY = start.y;
            player.z = player.lastZ = start.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);

            Vec3 rest = new Vec3(0, -0.0784F, 0);
            for (int tick = 0; tick < 2; tick++) {
                assertNotNull(process(player, frame(player, tick, START_X, 120, 0, rest, true)));
            }
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);

            OfflineBedrockReplayEvents.apply(
                    player,
                    JsonParser.parseString("{\"type\":\"velocity\",\"entityId\":" + player.entityID
                                    + ",\"velocity\":{\"x\":7.9,\"y\":0.1,\"z\":0}}")
                            .getAsJsonObject());

            // The player holds forward into the wall (yaw 0 faces +Z). The pulse replaces its velocity, the wall
            // eight blocks along still takes the whole Z input, and ground friction scales what X carries over.
            Vec3 carried = new Vec3(PULSE_X * GROUND_FRICTION, (PULSE_Y - 0.08F) * 0.98F, 0);
            BedrockMovementState next =
                    process(player, frame(player, 2, START_X + PULSE_X, 120 + PULSE_Y, 1, carried, false));

            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            assertEquals(start.x + PULSE_X, next.physicalFeetPosition().x(), 0.001);
            assertEquals(start.z, next.physicalFeetPosition().z(), 1.0E-6);
            assertEquals(carried.x, next.velocity().x(), 0.001);
            assertEquals(0.0, next.velocity().z(), 0.0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockMovementState process(CultPlayer player, BedrockAuthInputFrame frame) {
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull("tick " + frame.getClientTick(), result);
        return result.getProfileResult(BedrockPredictionResult.class).nextTickBaseState();
    }

    private static BedrockAuthInputFrame frame(
            CultPlayer player, int tick, float x, float y, float forward, Vec3 velocity, boolean grounded) {
        long flags = grounded
                ? 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal()
                : 1L << PlayerAuthInputData.HORIZONTAL_COLLISION.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(9000 + tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .coordinateFrame(ORIGIN)
                .position(ORIGIN.toWorld(new Vec3(x, y, START_Z)))
                .packetPosition(new Vec3(x, y + 1.62001F, START_Z))
                .rotation(0, 0, 0)
                .moveVector(0, forward)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(flags)
                .build();
    }
}
