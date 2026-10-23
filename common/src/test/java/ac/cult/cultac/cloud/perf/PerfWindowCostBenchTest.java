package ac.cult.cultac.cloud.perf;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Nanoseconds per {@link PerfRecorder.Window#alloc} call by path, over {@code CULTAC_PERF_COST_BENCH} reps of 200k
 * samples on one 24-frame stack, interleaved, with every bench thread id in the start snapshot: a sample without a
 * thread, a later sample of a held thread, a thread's first (copied) sample, a carrier-weighed sample under the bound so
 * far, and one live-thread read with idle threads started up to {@value #LIVE_THREADS} live.
 */
@EnabledIfEnvironmentVariable(named = "CULTAC_PERF_COST_BENCH", matches = "\\d+")
class PerfWindowCostBenchTest {
    private static final int SAMPLES = 200_000;
    private static final int LIVE_THREADS = 150;
    private static final String[] PATHS = {"no thread", "held thread", "first sample", "carrier", "live read"};

    @Test
    void bench() {
        CountDownLatch done = new CountDownLatch(1);
        while (ManagementFactory.getThreadMXBean().getThreadCount() < LIVE_THREADS) {
            Thread.ofPlatform().daemon().start(() -> awaitQuietly(done));
        }
        try {
            bench(Integer.parseInt(System.getenv("CULTAC_PERF_COST_BENCH")));
        } finally {
            done.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch done) {
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void bench(int reps) {
        String[] frames = new String[24];
        for (int i = 0; i < frames.length; i++) frames[i] = "ac.cult.cultac.bench.Frame" + i + ".m" + i;
        StackTrie.StackTraceView stack = StackTrieTest.stack(frames);
        Map<Long, PerfRecorder.ThreadBytes> live = PerfRecorder.threadAllocatedBytes();
        Map<Long, PerfRecorder.ThreadBytes> start = new HashMap<>(live);
        for (long id = 0; id < SAMPLES; id++) start.putIfAbsent(id, new PerfRecorder.ThreadBytes("bench", 0));
        List<double[]> runs = new ArrayList<>();
        for (int rep = -3; rep < reps; rep++) {
            double[] nanos = new double[PATHS.length];
            for (int path = 0; path < PATHS.length; path++) nanos[path] = run(path, stack, start);
            if (rep >= 0) runs.add(nanos);
        }
        StringBuilder out = new StringBuilder();
        for (int path = 0; path < PATHS.length; path++) {
            int p = path;
            double[] sorted = runs.stream().mapToDouble(run -> run[p]).sorted().toArray();
            out.append(String.format(
                    Locale.ROOT,
                    "%-13s median %8.1f ns  min %8.1f  max %8.1f%n",
                    PATHS[path],
                    sorted[sorted.length / 2],
                    sorted[0],
                    sorted[sorted.length - 1]));
        }
        out.append("threads ").append(live.size()).append(", reps ").append(reps);
        System.out.println(out);
    }

    private static double run(int path, StackTrie.StackTraceView stack, Map<Long, PerfRecorder.ThreadBytes> start) {
        PerfRecorder.Window window = new PerfRecorder.Window(-1, start, PerfRecorder::threadAllocatedBytes);
        int samples = path == 4 ? SAMPLES / 1_000 : SAMPLES;
        long begin = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            switch (path) {
                case 0 -> window.alloc(stack, Attribution.OTHER, -1, false, i, "[B", 100);
                case 1 -> window.alloc(stack, Attribution.OTHER, i & 63, false, i, "[B", 100);
                case 2 -> window.alloc(stack, Attribution.OTHER, i, false, i, "[B", 100);
                case 3 -> window.alloc(stack, Attribution.OTHER, 1_000 + (i & 63), true, i, "[B", 100);
                default -> PerfRecorder.threadAllocatedBytes();
            }
        }
        return (System.nanoTime() - begin) / (double) samples;
    }
}
