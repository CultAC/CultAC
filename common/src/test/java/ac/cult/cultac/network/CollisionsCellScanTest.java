package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The coordinate and BlockPos forms of a cell scan visit the same cells in the same order and stop at the same one. */
class CollisionsCellScanTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final String[] PALETTE = {
        "minecraft:air",
        "minecraft:stone",
        "minecraft:water",
        "minecraft:lava",
        "minecraft:stone_slab",
        "minecraft:oak_fence",
        "minecraft:moving_piston",
        "minecraft:kelp",
        "minecraft:soul_sand",
    };

    private record Cell(int state, int x, int y, int z, ClientFluidQueries.Sample fluid) {}

    @Test
    void bothFormsVisitTheSameCells() throws Exception {
        Random random = new Random(7);
        try (var f = new JavaTickFixture()) {
            for (int x = -3; x <= 3; x++) {
                for (int y = FLOOR_Y - 2; y <= FLOOR_Y + 3; y++) {
                    for (int z = -3; z <= 3; z++) {
                        String block = PALETTE[random.nextInt(PALETTE.length)];
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, y, z), BLOCKS.block(block).defaultState());
                    }
                }
            }
            for (int i = 0; i < 500; i++) {
                double minX = random.nextDouble() * 6 - 3, minY = FLOOR_Y - 2 + random.nextDouble() * 5;
                double minZ = random.nextDouble() * 6 - 3;
                SimpleCollisionBox box = new SimpleCollisionBox(
                        minX,
                        minY,
                        minZ,
                        minX + random.nextDouble() * 2,
                        minY + random.nextDouble() * 2,
                        minZ + random.nextDouble() * 2);
                int stopAt = random.nextInt(30) - 5;

                List<Cell> positioned = new ArrayList<>();
                boolean positionedResult = Collisions.hasState(f.player, box, (state, pos) -> {
                    positioned.add(new Cell(
                            state,
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            ClientFluidQueries.sample(f.player, state, pos)));
                    return positioned.size() == stopAt;
                });
                List<Cell> at = new ArrayList<>();
                boolean atResult = Collisions.hasState(f.player, box, (state, x, y, z) -> {
                    at.add(new Cell(state, x, y, z, ClientFluidQueries.sample(f.player, state, x, y, z)));
                    return at.size() == stopAt;
                });

                assertEquals(positioned, at, box.toString());
                assertEquals(positionedResult, atResult, box.toString());
            }
        }
    }

    /** {@code CULTAC_CPU_BENCH=scan}: allocation and CPU per player-sized scan, both forms interleaved. */
    @Test
    void benchBothForms() throws Exception {
        assumeTrue("scan".equals(System.getenv("CULTAC_CPU_BENCH")));
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Random random = new Random(11);
        try (var f = new JavaTickFixture()) {
            for (int x = -8; x <= 8; x++) {
                for (int y = FLOOR_Y - 2; y <= FLOOR_Y + 3; y++) {
                    for (int z = -8; z <= 8; z++) {
                        String block = PALETTE[random.nextInt(PALETTE.length)];
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, y, z), BLOCKS.block(block).defaultState());
                    }
                }
            }
            SimpleCollisionBox[] boxes = new SimpleCollisionBox[1024];
            for (int i = 0; i < boxes.length; i++) {
                double minX = random.nextDouble() * 14 - 7, minY = FLOOR_Y - 1 + random.nextDouble() * 2;
                double minZ = random.nextDouble() * 14 - 7;
                boxes[i] = new SimpleCollisionBox(minX, minY, minZ, minX + 0.6, minY + 1.8, minZ + 0.6);
            }
            int[] sink = new int[1];
            Collisions.StatePredicate[] positioned = {
                (state, pos) -> (sink[0] += pos.getX()) == Integer.MIN_VALUE,
                (state, pos) -> (sink[0] ^= pos.getY()) == Integer.MIN_VALUE,
                (state, pos) -> (sink[0] += state + pos.getZ()) == Integer.MIN_VALUE,
            };
            Collisions.StateAtPredicate[] at = {
                (state, x, y, z) -> (sink[0] += x) == Integer.MIN_VALUE,
                (state, x, y, z) -> (sink[0] ^= y) == Integer.MIN_VALUE,
                (state, x, y, z) -> (sink[0] += state + z) == Integer.MIN_VALUE,
            };
            int rounds = 40, scans = 500_000;
            long[][] bytes = new long[2][rounds], cpu = new long[2][rounds];
            for (int round = -10; round < rounds; round++) {
                for (int form = 0; form < 2; form++) {
                    long b0 = threads.getCurrentThreadAllocatedBytes(), c0 = threads.getCurrentThreadCpuTime();
                    for (int i = 0; i < scans; i++) {
                        SimpleCollisionBox box = boxes[i & (boxes.length - 1)];
                        if (form == 0) Collisions.hasState(f.player, box, positioned[i % 3]);
                        else Collisions.hasState(f.player, box, at[i % 3]);
                    }
                    if (round < 0) continue;
                    bytes[form][round] = threads.getCurrentThreadAllocatedBytes() - b0;
                    cpu[form][round] = threads.getCurrentThreadCpuTime() - c0;
                }
            }
            String[] names = {"BlockPos", "coords"};
            for (int form = 0; form < 2; form++) {
                Arrays.sort(bytes[form]);
                Arrays.sort(cpu[form]);
                System.out.printf(
                        "scan %s: %.1f B/scan, cpu %.1f ns/scan (p10 %.1f, p90 %.1f), sink %d%n",
                        names[form],
                        bytes[form][rounds / 2] / (double) scans,
                        cpu[form][rounds / 2] / (double) scans,
                        cpu[form][rounds / 10] / (double) scans,
                        cpu[form][rounds * 9 / 10] / (double) scans,
                        sink[0]);
            }
        }
    }
}
