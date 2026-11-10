package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code CULTAC_CPU_BENCH=predictscratch}: allocation and CPU per call, on the seeded scenarios' candidates, of ten
 * legacy-like vector reads per result (rebuilt on every read, then memoised) and of an air block change beside and far
 * from the last prediction.
 */
class PredictionScratchBenchTest {
    private static final int SEEDS = 66;
    private static final int ROUNDS = 30;
    private static final int WARMUP = 15;
    private static final int READS = 10;

    @Test
    void measure() throws Exception {
        assumeTrue("predictscratch".equals(System.getenv("CULTAC_CPU_BENCH")));
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        List<PredictionResult> candidates = new ArrayList<>();
        List<JavaTickFixture> fixtures = new ArrayList<>();
        try {
            for (long seed = 0; seed < SEEDS; seed++) {
                JavaTickFixture f = new JavaTickFixture();
                fixtures.add(f);
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    for (PredictionResult candidate : result.getAnyReality()) {
                        if (candidate.getValidMovements() != null && candidate.getSimulationContext() != null)
                            candidates.add(candidate);
                    }
                });
            }
            int n = candidates.size();
            Vec3[] sink = new Vec3[64];
            long[][] bytes = new long[3][ROUNDS], cpu = new long[3][ROUNDS];
            int blockCalls = fixtures.size() * 200;
            BlockPos[] beside = new BlockPos[fixtures.size()], far = new BlockPos[fixtures.size()];
            for (int i = 0; i < fixtures.size(); i++) {
                var player = fixtures.get(i).player;
                int x = (int) Math.floor(player.x), y = (int) Math.floor(player.y), z = (int) Math.floor(player.z);
                beside[i] = new BlockPos(x, y, z);
                far[i] = new BlockPos(x + 40, y, z);
            }
            for (int round = -WARMUP; round < ROUNDS; round++) {
                for (int path = 0; path < 2; path++) {
                    long b0 = threads.getCurrentThreadAllocatedBytes(), c0 = threads.getCurrentThreadCpuTime();
                    for (int i = 0; i < n; i++) {
                        PredictionResult candidate = candidates.get(i);
                        for (int r = 0; r < READS; r++) {
                            sink[(i + r) & 63] =
                                    path == 0 ? fresh(candidate) : candidate.getLegacyLikePredictionVector();
                        }
                    }
                    long b = threads.getCurrentThreadAllocatedBytes() - b0, c = threads.getCurrentThreadCpuTime() - c0;
                    if (round >= 0) {
                        bytes[path][round] = b;
                        cpu[path][round] = c;
                    }
                }
                long b0 = threads.getCurrentThreadAllocatedBytes(), c0 = threads.getCurrentThreadCpuTime();
                for (int i = 0; i < blockCalls; i++) {
                    int fixture = i % fixtures.size();
                    SimulationProcessor processor =
                            fixtures.get(fixture).player.checkManager.getSimulationProcessor();
                    processor.handleBlockChange((i & 1) == 0 ? beside[fixture] : far[fixture], 0);
                }
                long b = threads.getCurrentThreadAllocatedBytes() - b0, c = threads.getCurrentThreadCpuTime() - c0;
                if (round >= 0) {
                    bytes[2][round] = b;
                    cpu[2][round] = c;
                }
            }
            String[] names = {"legacy-like rebuilt", "legacy-like memo   ", "air block change   "};
            double[] calls = {n * (double) READS, n * (double) READS, blockCalls};
            System.out.printf("predictscratch: %d candidates, %d fixtures, sink %s%n", n, fixtures.size(), sink[0]);
            for (int s = 0; s < 3; s++) {
                long[] b = bytes[s].clone(), c = cpu[s].clone();
                Arrays.sort(b);
                Arrays.sort(c);
                System.out.printf(
                        "predictscratch %s: %.1f B/call (p10 %.1f, p90 %.1f), cpu %.1f ns/call (p10 %.1f, p90 %.1f)%n",
                        names[s],
                        b[ROUNDS / 2] / calls[s],
                        b[ROUNDS / 10] / calls[s],
                        b[ROUNDS * 9 / 10] / calls[s],
                        c[ROUNDS / 2] / calls[s],
                        c[ROUNDS / 10] / calls[s],
                        c[ROUNDS * 9 / 10] / calls[s]);
            }
        } finally {
            for (JavaTickFixture f : fixtures) f.close();
        }
    }

    private static Vec3 fresh(PredictionResult result) {
        if (result.getProfilePredictionVector() != null) return result.getProfilePredictionVector();
        return UncertaintyHelper.handleCircularInput(
                new PredVector(result.getAcceptedClosestToTarget()),
                result.getTarget(),
                result.getHorizontalInputRadius());
    }
}
