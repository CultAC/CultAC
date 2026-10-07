package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.protocol.value.BlockPos;
import org.junit.Test;

public class BedrockBlockReconciliationTest {
    @Test
    public void localBreakPlacementAndInteractionRemainVisibleUntilServerReceipt() {
        OfflineCultTestBootstrap.installConfig();
        var door = DataTables.defaults().registry().block("minecraft:oak_door").defaultState();
        assertBedrockReconciliation(
                DataTables.defaults().registry().block("minecraft:grass_block").defaultState(),
                DataTables.defaults().registry().block("minecraft:air").defaultState());
        assertBedrockReconciliation(
                DataTables.defaults().registry().block("minecraft:air").defaultState(),
                DataTables.defaults().registry().block("minecraft:stone").defaultState());
        assertBedrockReconciliation(door, DataTables.defaults().registry().with(door, "open", "true"));
    }

    private static void assertBedrockReconciliation(int server, int local) {
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
            assertEquals(local, world.getBlockStateIdAt(pos));
            assertFalse(world.hasPendingBlockPrediction(pos));

            world.handlePredictionConfirmation(31, 10);
            player.latencyUtils.handleNettySyncTransaction(10);
            assertEquals(local, world.getBlockStateIdAt(pos));

            player.latencyUtils.addRealTimeTask(11, () -> world.handleServerBlockUpdate(pos, server, 11));
            assertEquals(local, world.getBlockStateIdAt(pos));
            player.latencyUtils.handleNettySyncTransaction(11);
            assertEquals(server, world.getBlockStateIdAt(pos));
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
            var grass = DataTables.defaults()
                    .registry()
                    .block("minecraft:grass_block")
                    .defaultState();
            world.ensureValidationChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), grass);
            world.advanceClientPredictionSequence();
            world.startPredicting();
            world.updateBlock(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    DataTables.defaults().registry().block("minecraft:air").defaultState());
            world.stopPredicting(31);
            assertTrue(world.hasPendingBlockPrediction(pos));
            world.handleServerBlockUpdate(pos, grass, 10);
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(world.getBlockStateIdAt(pos))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            world.handlePredictionConfirmation(0, 10);
            player.latencyUtils.handleNettySyncTransaction(10);
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(world.getBlockStateIdAt(pos))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            world.handlePredictionConfirmation(1, 11);
            player.latencyUtils.handleNettySyncTransaction(11);
            assertEquals(grass, world.getBlockStateIdAt(pos));
            assertFalse(world.hasPendingBlockPrediction(pos));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
