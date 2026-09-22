package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.MovementPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.Test;

import static org.junit.Assert.*;

public class BedrockBlockReconciliationTest {
    @Test
    public void localBreakPlacementAndInteractionRemainVisibleUntilServerReceipt() {
        OfflineCultTestBootstrap.installConfig();
        var door = Blocks.OAK_DOOR.defaultBlockState();
        assertBedrockReconciliation(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        assertBedrockReconciliation(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState());
        assertBedrockReconciliation(door, door.setValue(BlockStateProperties.OPEN, true));
    }

    private static void assertBedrockReconciliation(BlockState server, BlockState local) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var world = player.compensatedWorld;
            var pos = new BlockPos(2901, 69, -30);
            world.ensureValidationChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), server);
            for (int i = 0; i < 32; i++) world.advanceClientPredictionSequence();
            world.startPredicting();
            world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), local);
            world.stopPredicting(31);
            assertEquals(local, world.getBlockStateAt(pos));
            assertFalse(world.hasPendingBlockPrediction(pos));

            world.handlePredictionConfirmation(31, 10);
            player.latencyUtils.handleNettySyncTransaction(10);
            assertEquals(local, world.getBlockStateAt(pos));

            player.latencyUtils.addRealTimeTask(11,
                    () -> world.handleServerBlockUpdate(pos, server, 11));
            assertEquals(local, world.getBlockStateAt(pos));
            player.latencyUtils.handleNettySyncTransaction(11);
            assertEquals(server, world.getBlockStateAt(pos));
            assertFalse(world.hasPendingBlockPrediction(pos));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void javaStillRetainsLocalChangeUntilItsPredictionAcknowledgement() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        player.movementPlatform = MovementPlatform.JAVA;
        try {
            var world = player.compensatedWorld;
            var pos = new BlockPos(2901, 69, -30);
            var grass = Blocks.GRASS_BLOCK.defaultBlockState();
            world.ensureValidationChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), grass);
            world.advanceClientPredictionSequence();
            world.startPredicting();
            world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), Blocks.AIR.defaultBlockState());
            world.stopPredicting(31);
            assertTrue(world.hasPendingBlockPrediction(pos));
            world.handleServerBlockUpdate(pos, grass, 10);
            assertTrue(world.getBlockStateAt(pos).isAir());
            world.handlePredictionConfirmation(0, 10);
            player.latencyUtils.handleNettySyncTransaction(10);
            assertTrue(world.getBlockStateAt(pos).isAir());
            world.handlePredictionConfirmation(1, 11);
            player.latencyUtils.handleNettySyncTransaction(11);
            assertEquals(grass, world.getBlockStateAt(pos));
            assertFalse(world.hasPendingBlockPrediction(pos));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
