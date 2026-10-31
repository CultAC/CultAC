package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Allocated bytes and thread CPU per client tick of the seeded {@link PredictionScenarios}, fixture setup excluded;
 * runs only with CULT_PREDICT_ALLOC_BENCH set to an output file.
 */
class PredictionAllocBenchTest {
    private static final int SCENARIOS = 66;
    private static final int WARMUP_SAMPLES = 5;
    private static final int REPS = 9;

    @Test
    void scenarioTicks() throws Exception {
        String out = System.getenv("CULT_PREDICT_ALLOC_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int warmup = 0; warmup < WARMUP_SAMPLES; warmup++) sample(bean);
        double[] bytes = new double[REPS];
        double[] cpu = new double[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            double[] sample = sample(bean);
            bytes[rep] = sample[0];
            cpu[rep] = sample[1];
        }
        double[] sortedBytes = bytes.clone();
        double[] sortedCpu = cpu.clone();
        Arrays.sort(sortedBytes);
        Arrays.sort(sortedCpu);
        String report = String.format(
                "scenario tick: %.0f B/tick (min %.0f, max %.0f), median %.0f ns/tick (min %.0f, max %.0f)%n",
                sortedBytes[REPS / 2],
                sortedBytes[0],
                sortedBytes[REPS - 1],
                sortedCpu[REPS / 2],
                sortedCpu[0],
                sortedCpu[REPS - 1]);
        System.out.print(report);
        Files.writeString(Path.of(out), report + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Every scenario once; allocated bytes and thread CPU per tick. */
    private static double[] sample(ThreadMXBean bean) throws Exception {
        long thread = Thread.currentThread().threadId();
        long bytes = 0;
        long cpu = 0;
        long ticks = 0;
        for (int seed = 0; seed < SCENARIOS; seed++) {
            try (var f = new JavaTickFixture()) {
                long beginBytes = bean.getThreadAllocatedBytes(thread);
                long begin = bean.getCurrentThreadCpuTime();
                PredictionScenarios.run(f, seed, result -> {});
                cpu += bean.getCurrentThreadCpuTime() - begin;
                bytes += bean.getThreadAllocatedBytes(thread) - beginBytes;
                ticks += PredictionScenarios.TICKS;
            }
        }
        return new double[] {(double) bytes / ticks, (double) cpu / ticks};
    }
}
