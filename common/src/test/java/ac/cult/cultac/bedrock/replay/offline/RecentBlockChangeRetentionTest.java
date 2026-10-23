package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

/** Block-change state keeps every entry for its exact client-tick lifetime, however many changes arrive. */
public class RecentBlockChangeRetentionTest {
    // A bulk update larger than a 16x16x16 section.
    private static final int BULK = 4096 + 64;

    @Test
    public void bulkServerUpdatesKeepEveryChangeForThreeTickEnds() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = javaPlayer();
        try {
            var world = player.compensatedWorld;
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            int water =
                    DataTables.defaults().registry().block("minecraft:water").defaultState();
            world.ensureValidationChunkLoaded(0, 0);
            // An explosion or fill sends this many updates within one client tick; stone changes only collision
            // and water only fluid.
            for (int i = 0; i < BULK; i++) {
                BlockPos pos = position(i);
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), water);
                pos = position(BULK + i);
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), stone);
            }

            for (int tick = 0; tick < 2; tick++) {
                assertTrue(world.hasRecentClientWaterChanges(box(position(0))));
                assertTrue(world.hasRecentClientCollisionChanges(box(position(BULK))));
                world.removeInvalidPistonLikeStuff();
            }
            assertTrue(world.hasRecentClientWaterChanges(box(position(0))));
            assertTrue(world.hasRecentClientCollisionChanges(box(position(BULK))));
            assertTrue(world.hasRecentClientWaterChanges(box(position(BULK - 1))));

            world.removeInvalidPistonLikeStuff();
            assertFalse(world.hasRecentClientWaterChanges(box(position(0))));
            assertTrue(((Map<?, ?>) field(world, "recentClientCollisionChanges")).isEmpty());
            assertTrue(((Map<?, ?>) field(world, "recentClientFluidChanges")).isEmpty());
            assertTrue(((Map<?, ?>) field(world, "recentClientFluidChangeKinds")).isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

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

    private static SimpleCollisionBox box(BlockPos pos) {
        return new SimpleCollisionBox(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    private static Object field(CompensatedWorld world, String name) throws Exception {
        Field field = CompensatedWorld.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(world);
    }
}
