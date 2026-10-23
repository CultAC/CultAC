package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.blockplace.GhostBlock;
import ac.cult.cultac.utils.inventory.ItemTypes;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

public class GhostBlockRetentionTest {
    @Test
    public void serverBlockUpdateRemovesThePseudoPlaceAtItsPosition() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        player.movementPlatform = MovementPlatform.JAVA;
        try {
            var world = player.compensatedWorld;
            var mitigator = player.getGhostBlockMitigator();
            var pos = new BlockPos(2901, 69, -30);
            var other = new BlockPos(2902, 69, -30);
            world.ensureValidationChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            mitigator.handlePseudoPlace(new GhostBlock(pos, null, true));
            mitigator.handlePseudoPlace(new GhostBlock(other, null, true));

            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), stone);

            Map<BlockPos, GhostBlock> places = pseudoPlaces(mitigator);
            assertFalse(places.containsKey(pos));
            assertTrue(places.containsKey(other));
            assertEquals(1, places.size());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void clickSpamStaysAtTheCapAndDropsTheOldest() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var mitigator = player.getGhostBlockMitigator();
            int cap = cap();
            for (int i = 0; i < cap * 4; i++) {
                mitigator.handlePseudoPlace(new GhostBlock(new BlockPos(i, 64, 0), null, true));
                mitigator.handlePseudoPlace(new GhostBlock(new BlockPos(i, 64, 0), null, true));
            }

            Map<BlockPos, GhostBlock> places = pseudoPlaces(mitigator);
            assertEquals(cap, places.size());
            assertFalse(places.containsKey(new BlockPos(0, 64, 0)));
            assertTrue(places.containsKey(new BlockPos(cap * 4 - 1, 64, 0)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void reClickedPositionIsNotEvictedAsTheOldest() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var mitigator = player.getGhostBlockMitigator();
            int cap = cap();
            for (int i = 0; i < cap; i++) {
                mitigator.handlePseudoPlace(new GhostBlock(new BlockPos(i, 64, 0), null, true));
            }
            mitigator.handlePseudoPlace(new GhostBlock(new BlockPos(0, 64, 0), null, true));
            mitigator.handlePseudoPlace(new GhostBlock(new BlockPos(cap, 64, 0), null, true));

            Map<BlockPos, GhostBlock> places = pseudoPlaces(mitigator);
            assertEquals(cap, places.size());
            assertTrue(places.containsKey(new BlockPos(0, 64, 0)));
            assertFalse(places.containsKey(new BlockPos(1, 64, 0)));
            assertTrue(places.containsKey(new BlockPos(cap, 64, 0)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void endCrystalClickKeepsTheBlockClickAtItsPosition() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var mitigator = player.getGhostBlockMitigator();
            var pos = new BlockPos(0, 64, 0);
            GhostBlock block = new GhostBlock(pos, null, true);
            mitigator.handlePseudoPlace(block);
            mitigator.handlePseudoPlace(
                    new GhostBlock(pos, new SimItemStack(ItemTypes.END_CRYSTAL, 1, Components.EMPTY), false));

            Map<BlockPos, GhostBlock> places = pseudoPlaces(mitigator);
            assertEquals(1, places.size());
            assertSame(block, places.get(pos));

            GhostBlock replacement = new GhostBlock(pos, null, true);
            mitigator.handlePseudoPlace(replacement);
            assertSame(replacement, places.get(pos));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void endCrystalClickReplacesAnEndCrystalClickAtItsPosition() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            var mitigator = player.getGhostBlockMitigator();
            var pos = new BlockPos(0, 64, 0);
            mitigator.handlePseudoPlace(
                    new GhostBlock(pos, new SimItemStack(ItemTypes.END_CRYSTAL, 1, Components.EMPTY), false));
            GhostBlock second =
                    new GhostBlock(pos, new SimItemStack(ItemTypes.END_CRYSTAL, 1, Components.EMPTY), false);
            mitigator.handlePseudoPlace(second);

            Map<BlockPos, GhostBlock> places = pseudoPlaces(mitigator);
            assertEquals(1, places.size());
            assertSame(second, places.get(pos));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, GhostBlock> pseudoPlaces(GhostBlockMitigator mitigator) throws Exception {
        Field field = GhostBlockMitigator.class.getDeclaredField("pseudoPlaces");
        field.setAccessible(true);
        return (Map<BlockPos, GhostBlock>) field.get(mitigator);
    }

    private static int cap() throws Exception {
        Field field = GhostBlockMitigator.class.getDeclaredField("MAX_PSEUDO_PLACES");
        field.setAccessible(true);
        return field.getInt(null);
    }
}
