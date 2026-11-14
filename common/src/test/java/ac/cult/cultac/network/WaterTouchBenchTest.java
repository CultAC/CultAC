package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@code CULTAC_CPU_BENCH=watertouch}: thread CPU and allocation of the water-contact scan for player-sized boxes, as
 * the per-cell position lookups and as the coordinate scan, interleaved, on a flooded random world.
 */
class WaterTouchBenchTest {
    private static final int QUERIES = 2000;

    @Test
    void measure() throws Exception {
        assumeTrue("watertouch".equals(System.getenv("CULTAC_CPU_BENCH")));
        int warmup = Integer.getInteger("bench.warmup", 20);
        int reps = Integer.getInteger("bench.reps", 15);
        int inner = Integer.getInteger("bench.inner", 200);
        var thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            CollisionGatheringEquivalenceTest.CollisionWorlds.populate(player, 0x5eedL);
            var random = new SplittableRandom(0xA0705L);
            WaterTouchEquivalenceTest.flood(player, random);
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            for (int i = 0; i < QUERIES; i++) {
                double x = random.nextDouble(-20, 36), z = random.nextDouble(-20, 36), y = random.nextDouble(44, 82);
                boxes.add(new SimpleCollisionBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
            }
            long[][] nanos = new long[2][reps], bytes = new long[2][reps];
            long sink = 0;
            for (int i = 0; i < warmup; i++) sink += pass(player, boxes, false) + pass(player, boxes, true);
            for (int r = 0; r < reps; r++) {
                for (int path = 0; path < 2; path++) {
                    long allocated = thread.getCurrentThreadAllocatedBytes();
                    long start = thread.getCurrentThreadCpuTime();
                    for (int j = 0; j < inner; j++) sink += pass(player, boxes, path == 1);
                    nanos[path][r] = thread.getCurrentThreadCpuTime() - start;
                    bytes[path][r] = thread.getCurrentThreadAllocatedBytes() - allocated;
                }
            }
            var report = new StringBuilder();
            for (int path = 0; path < 2; path++) {
                long[] n = nanos[path].clone(), b = bytes[path].clone();
                Arrays.sort(n);
                Arrays.sort(b);
                double calls = (double) inner * boxes.size();
                report.append(String.format(
                        "watertouch %s: cpu median %.1f ns/call (min %.1f max %.1f), alloc median %.1f B/call (min %.1f"
                                + " max %.1f), sink %d%n",
                        path == 0 ? "positions" : "coords   ",
                        n[reps / 2] / calls,
                        n[0] / calls,
                        n[reps - 1] / calls,
                        b[reps / 2] / calls,
                        b[0] / calls,
                        b[reps - 1] / calls,
                        sink));
            }
            System.out.print(report);
        }
    }

    private static long pass(CultPlayer player, List<SimpleCollisionBox> boxes, boolean coords) {
        long sink = 0;
        for (SimpleCollisionBox box : boxes) {
            boolean hit = coords
                    ? JavaFallDistance.touchesWater(player, box)
                    : WaterTouchEquivalenceTest.reference(player, box);
            if (hit) sink++;
        }
        return sink;
    }
}
