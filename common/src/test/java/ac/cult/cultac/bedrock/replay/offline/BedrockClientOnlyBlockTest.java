package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A block only the Bedrock client holds is part of the world its movement collides with.
 *
 * <p>When the server opens a container while the player jumps, the translator puts the container's holder one
 * block above the eye position, at y 123 for a jump from y 120. The 1.8 tall player stops rising at y 121.2 instead
 * of 121.24918, then falls from rest.
 */
public class BedrockClientOnlyBlockTest {
    private static final BedrockCoordinateFrame ORIGIN = new BedrockCoordinateFrame(20064, 20080, 9);
    private static final BlockPos HOLDER = new BlockPos(20102, 123, 20080);
    /** The player's feet after each tick of the jump, and its velocity for the next one. */
    private static final float[][] JUMP = {
        {120.42F, 0.3332F},
        {120.7532F, 0.24813599F},
        {121.001335F, 0.16477327F},
        {121.16611F, 0.0830778F},
        {121.2F, -0.0784F},
        {121.1216F, -0.155232F},
        {120.96637F, -0.23052737F}
    };

    @Test
    public void theHolderAboveTheHeadStopsTheJumpAtItsBottomFace() {
        assertEquals(0, run(true), 0);
    }

    @Test
    public void theSameJumpWithoutTheHolderIsRejected() {
        assertTrue(run(false) > 0);
    }

    @Test
    public void theServerBlockUnderTheHolderReturnsWhenTheClientReceivesIt() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var world = player.compensatedWorld;
            int stone = state("minecraft:stone");
            int chest = state("minecraft:chest");
            world.ensureValidationChunkLoaded(HOLDER.getX() >> 4, HOLDER.getZ() >> 4);
            world.handleBedrockClientBlockUpdate(HOLDER, chest, false);
            assertEquals(chest, world.getBlockStateIdAt(HOLDER));
            assertEquals(BlockIds.AIR.defaultState(), world.getServerSentBlockStateIdAt(HOLDER));

            // The client does not receive a server update the translator keeps from it.
            world.handleServerBlockUpdate(HOLDER, stone, 0);
            assertEquals(chest, world.getBlockStateIdAt(HOLDER));
            assertEquals(stone, world.getServerSentBlockStateIdAt(HOLDER));

            world.handleBedrockClientBlockUpdate(HOLDER, stone, true);
            assertEquals(stone, world.getBlockStateIdAt(HOLDER));
            world.handleServerBlockUpdate(HOLDER, BlockIds.AIR.defaultState(), 0);
            assertEquals(BlockIds.AIR.defaultState(), world.getBlockStateIdAt(HOLDER));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static double run(boolean holder) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            double x = 20102.5;
            double z = 20080.5;
            for (int blockX = 20100; blockX <= 20104; blockX++)
                for (int blockZ = 20078; blockZ <= 20082; blockZ++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(blockX >> 4, blockZ >> 4);
                    player.compensatedWorld.updateBlock(blockX, 119, blockZ, state("minecraft:stone"));
                }
            player.x = player.lastX = x;
            player.y = player.lastY = 120;
            player.z = player.lastZ = z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, 120, z);
            frame(player, 0, 120, new Vec3(0, -0.0784F, 0), 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal());
            for (int tick = 0; tick < JUMP.length; tick++) {
                // The client has the holder before the tick that reaches it.
                if (holder && tick == 4)
                    player.compensatedWorld.handleBedrockClientBlockUpdate(HOLDER, state("minecraft:chest"), false);
                long flags = tick == 4 ? 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal() : 0;
                var state = frame(player, 1 + tick, JUMP[tick][0], new Vec3(0, JUMP[tick][1], 0), flags);
                if (holder) {
                    assertEquals("tick " + tick, JUMP[tick][0], state.physicalFeetPosition().y(), 1.0E-4);
                    assertEquals("tick " + tick, JUMP[tick][1], state.velocity().y(), 1.0E-4);
                }
            }
            return player.checkManager.getListener(BedrockMovement.class).violations;
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static ac.cult.cultac.bedrock.prediction.state.BedrockMovementState frame(
            CultPlayer player, int tick, float feetY, Vec3 velocity, long flags) {
        var local = new Vec3(38.5, feetY, 0.5);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(207523 + tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .coordinateFrame(ORIGIN)
                .position(ORIGIN.toWorld(local))
                .packetPosition(new Vec3(local.x, feetY + 1.62F, local.z))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(flags)
                .jumping(tick == 1)
                .jumpStarted(tick == 1)
                .jumpPressedRaw(tick == 1)
                .jumpCurrentRaw(tick == 1)
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull("tick " + tick, result);
        var state = result.getProfileResult(BedrockPredictionResult.class).nextTickBaseState();
        assertNotNull(state);
        return state;
    }

    private static int state(String block) {
        return DataTables.defaults().registry().block(block).defaultState();
    }
}
