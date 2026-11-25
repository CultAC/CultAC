package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SimulationProcessor#powderSnowInReach's CompensatedWorld#containsBlock answers as the getBlockStateIdAt scan of every
 * cell did, selection snapshot included, while skipping sections and columns that cannot hold the block.
 */
class JavaPowderSnowReachScanTest {
    @Test
    void sectionsWithoutTheBlockAreSkippedWithTheSameAnswer() throws Exception {
        Random random = new Random(17);
        for (int world = 0; world < 5; world++) {
            try (var f = new JavaTickFixture()) {
                CompensatedWorld blocks = f.player.compensatedWorld;
                for (int i = 0; i < 40; i++) blocks.updateBlock(position(random), BlockIds.POWDER_SNOW.defaultState());
                for (int i = 0; i < 400; i++) blocks.updateBlock(position(random), BlockIds.STONE.defaultState());
                compare(f, random, 300);
                // Local block predictions under a selection snapshot read their originals there.
                blocks.beginSelectionSnapshot();
                blocks.startPredicting();
                for (int i = 0; i < 30; i++) {
                    BlockDefinition placed = random.nextBoolean() ? BlockIds.POWDER_SNOW : BlockIds.AIR;
                    blocks.updateBlock(position(random), placed.defaultState());
                }
                blocks.stopPredicting(0);
                compare(f, random, 150);
                blocks.withSelectionSnapshot(() -> {
                    compare(f, random, 150);
                    return null;
                });
                blocks.endSelectionSnapshot();
            }
        }
    }

    @Test
    void aHugeBoxVisitsOnlyTheStoredColumns() throws Exception {
        try (var f = new JavaTickFixture()) {
            CompensatedWorld blocks = f.player.compensatedWorld;
            int max = Integer.MAX_VALUE, min = Integer.MIN_VALUE;
            long start = System.nanoTime();
            assertFalse(blocks.containsBlock(min, min, min, max, max, max, BlockIds.POWDER_SNOW));
            blocks.updateBlock(new BlockPos(5, 70, -9), BlockIds.POWDER_SNOW.defaultState());
            assertTrue(blocks.containsBlock(min, min, min, max, max, max, BlockIds.POWDER_SNOW));
            // A cell-by-cell scan of this box never ends: its int loop wraps.
            assertTrue(System.nanoTime() - start < 1_000_000_000L);
        }
    }

    private static void compare(JavaTickFixture f, Random random, int samples) {
        CompensatedWorld blocks = f.player.compensatedWorld;
        for (int sample = 0; sample < samples; sample++) {
            BlockPos corner = position(random);
            int sizeX = random.nextInt(40), sizeY = random.nextInt(40), sizeZ = random.nextInt(40);
            int minX = corner.getX() - sizeX / 2, minY = corner.getY() - sizeY / 2, minZ = corner.getZ() - sizeZ / 2;
            int maxX = minX + sizeX, maxY = minY + sizeY, maxZ = minZ + sizeZ;
            for (BlockDefinition block : new BlockDefinition[] {BlockIds.POWDER_SNOW, BlockIds.STONE}) {
                boolean expected = false;
                for (int x = minX; x <= maxX && !expected; x++)
                    for (int y = minY; y <= maxY && !expected; y++)
                        for (int z = minZ; z <= maxZ && !expected; z++)
                            expected = BlockIds.is(blocks.getBlockStateIdAt(new BlockPos(x, y, z)), block);
                assertEquals(
                        expected,
                        blocks.containsBlock(minX, minY, minZ, maxX, maxY, maxZ, block),
                        block + " in " + minX + "," + minY + "," + minZ + " .. " + maxX + "," + maxY + "," + maxZ);
            }
        }
    }

    /** Across the fixture's loaded columns and past them, from below the world's floor to above its top. */
    private static BlockPos position(Random random) {
        return new BlockPos(random.nextInt(80) - 40, random.nextInt(420) - 80, random.nextInt(80) - 40);
    }
}
