package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.AutoJumpGeometryAccess;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@code CULTAC_CPU_BENCH=autojump}: thread CPU and allocation of the auto-jump landing and look-ahead scans, as the box
 * list the old tests walked and as the first-match scan, interleaved, on a stone floor and on a dense random world.
 */
class AutoJumpScanBenchTest {
    private static final int QUERIES = 2000;

    @Test
    void measure() throws Exception {
        assumeTrue("autojump".equals(System.getenv("CULTAC_CPU_BENCH")));
        int warmup = Integer.getInteger("bench.warmup", 20);
        int reps = Integer.getInteger("bench.reps", 15);
        int inner = Integer.getInteger("bench.inner", 20);
        var thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var report = new StringBuilder();
        for (boolean dense : new boolean[] {false, true}) {
            try (var services = new RecordConsumerServices();
                    var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
                CultPlayer player = fixture.player;
                double floor;
                if (dense) {
                    CollisionGatheringEquivalenceTest.CollisionWorlds.populate(player, 0x5eedL);
                    floor = 60;
                } else {
                    for (int x = -1; x <= 1; x++)
                        for (int z = -1; z <= 1; z++) player.compensatedWorld.ensureValidationChunkLoaded(x, z);
                    for (int x = -16; x < 32; x++)
                        for (int z = -16; z < 32; z++)
                            player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), BlockIds.STONE.defaultState());
                    floor = 64;
                }
                var regions = regions(floor, dense);
                long[][] nanos = new long[2][reps], bytes = new long[2][reps];
                long sink = 0;
                for (int i = 0; i < warmup; i++) sink += pass(player, regions, false) + pass(player, regions, true);
                for (int r = 0; r < reps; r++) {
                    for (int path = 0; path < 2; path++) {
                        long allocated = thread.getCurrentThreadAllocatedBytes();
                        long start = thread.getCurrentThreadCpuTime();
                        for (int j = 0; j < inner; j++) sink += pass(player, regions, path == 1);
                        nanos[path][r] = thread.getCurrentThreadCpuTime() - start;
                        bytes[path][r] = thread.getCurrentThreadAllocatedBytes() - allocated;
                    }
                }
                for (int path = 0; path < 2; path++) {
                    long[] n = nanos[path].clone(), b = bytes[path].clone();
                    Arrays.sort(n);
                    Arrays.sort(b);
                    report.append(String.format(
                            "%s %s: cpu median %.1f ns/scan (min %.1f max %.1f), alloc median %.1f B/scan (min %.1f"
                                    + " max %.1f), sink %d%n",
                            dense ? "dense" : "floor",
                            path == 0 ? "list " : "first",
                            n[reps / 2] / (double) inner / regions.size(),
                            n[0] / (double) inner / regions.size(),
                            n[reps - 1] / (double) inner / regions.size(),
                            b[reps / 2] / (double) inner / regions.size(),
                            b[0] / (double) inner / regions.size(),
                            b[reps - 1] / (double) inner / regions.size(),
                            sink));
                }
            }
        }
        System.out.print(report);
    }

    private record Region(
            SimpleCollisionBox box, double low, double high, double top, double margin, boolean landing) {}

    /** The landing region around a walking player's box, and the thin look-ahead band at its feet plus 0.51. */
    private static List<Region> regions(double floor, boolean dense) {
        var random = new SplittableRandom(0xA0705L);
        var result = new ArrayList<Region>();
        double margin = 2 * 2.0E-4 + 1.0E-6;
        for (int i = 0; i < QUERIES; i++) {
            double x = random.nextDouble(-8, 20), z = random.nextDouble(-8, 20);
            double y = dense ? floor + random.nextInt(20) : floor;
            if (random.nextBoolean()) {
                double reach = random.nextDouble(0.1, 0.35) + 1.0;
                double low = y - Math.sqrt(1.0E-7) - margin, high = y + margin;
                var box = new SimpleCollisionBox(
                        x - 0.3 - reach,
                        low - 1.0E-3,
                        z - 0.3 - reach,
                        x + 0.3 + reach,
                        y + 1.8 + margin + 1.0E-3,
                        z + 0.3 + reach);
                result.add(new Region(box, low, high, y + 1.8, margin, true));
            } else {
                double reach = random.nextDouble(0.05, 0.4) + 0.7 + 0.3 + margin + 1.0E-3;
                double height = y + 0.51F;
                var box = new SimpleCollisionBox(
                        x - reach, height - margin - 1.0E-3, z - reach, x + reach, height + margin + 1.0E-3, z + reach);
                result.add(new Region(box, height, 0, 0, margin, false));
            }
        }
        return result;
    }

    private static long pass(CultPlayer player, List<Region> regions, boolean first) {
        long sink = 0;
        for (Region region : regions) {
            boolean hit = region.landing
                    ? first ? landingScan(player, region) : landingList(player, region)
                    : first ? aheadScan(player, region) : aheadList(player, region);
            if (hit) sink++;
        }
        return sink;
    }

    private static boolean landingList(CultPlayer player, Region r) {
        for (SimpleCollisionBox box : AutoJumpGeometryAccess.blockCollisions(player, r.box)) {
            for (double y : new double[] {box.minY, box.maxY}) {
                if (y >= r.low && y <= r.high || Math.abs(y - r.top) <= r.margin) return true;
            }
        }
        return false;
    }

    private static boolean landingScan(CultPlayer player, Region r) {
        double low = r.low, high = r.high, top = r.top, margin = r.margin;
        return AutoJumpGeometryAccess.anyBlockCollision(
                player,
                r.box,
                (minY, maxY) -> minY >= low && minY <= high
                        || Math.abs(minY - top) <= margin
                        || maxY >= low && maxY <= high
                        || Math.abs(maxY - top) <= margin);
    }

    private static boolean aheadList(CultPlayer player, Region r) {
        for (SimpleCollisionBox box : AutoJumpGeometryAccess.blockCollisions(player, r.box)) {
            if (box.minY < r.low + r.margin && box.maxY > r.low - r.margin) return true;
        }
        return false;
    }

    private static boolean aheadScan(CultPlayer player, Region r) {
        double height = r.low, margin = r.margin;
        return AutoJumpGeometryAccess.anyBlockCollision(
                player, r.box, (minY, maxY) -> minY < height + margin && maxY > height - margin);
    }
}
