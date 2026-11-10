package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

/** A block selection snapshot keeps every original state, however many predicted changes arrive. */
public class RecentBlockChangeRetentionTest {
    // A bulk update larger than a 16x16x16 section.
    private static final int BULK = 4096 + 64;

    @Test
    public void selectionSnapshotKeepsEveryOriginalState() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = javaPlayer();
        try {
            var world = player.compensatedWorld;
            int air = DataTables.defaults().registry().block("minecraft:air").defaultState();
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            world.ensureValidationChunkLoaded(0, 0);
            for (int i = 0; i < BULK; i++) {
                BlockPos pos = position(i);
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), stone);
            }
            world.beginSelectionSnapshot();
            for (int i = 0; i < BULK; i++) {
                BlockPos pos = position(i);
                world.advanceClientPredictionSequence();
                world.startPredicting();
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), air);
                world.stopPredicting(0);
            }

            for (int i : new int[] {0, BULK / 2, BULK - 1}) {
                assertEquals(stone, (int) world.withSelectionSnapshot(() -> world.getBlockStateIdAt(position(i))));
                assertEquals(air, world.getBlockStateIdAt(position(i)));
            }

            world.endSelectionSnapshot();
            assertTrue(((Map<?, ?>) field(world, "selectionOriginalStates")).isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static CultPlayer javaPlayer() {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        player.movementPlatform = MovementPlatform.JAVA;
        return player;
    }

    private static BlockPos position(int i) {
        return new BlockPos(i & 15, 64 + (i >> 8), (i >> 4) & 15);
    }

    private static Object field(CompensatedWorld world, String name) throws Exception {
        Field field = CompensatedWorld.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(world);
    }
}
