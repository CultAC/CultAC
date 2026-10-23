package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Thread CPU per client tick of the seeded {@link PredictionScenarios}; runs only with CULT_PREDICT_BENCH set. */
class PredictionCpuBenchTest {
    private static final int SCENARIOS = 66;
    private static final int ROUNDS = 1;
    private static final int WARMUP_SAMPLES = 3;
    private static final int REPS = 9;

    @Test
    void scenarioTicks() throws Exception {
        String out = System.getenv("CULT_PREDICT_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        for (int warmup = 0; warmup < WARMUP_SAMPLES; warmup++) sample(bean);
        double[] samples = new double[REPS];
        for (int rep = 0; rep < REPS; rep++) samples[rep] = sample(bean);
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        String report = String.format(
                "scenario tick: median %.0f ns/tick, min %.0f, max %.0f, samples %s%n",
                sorted[REPS / 2], sorted[0], sorted[REPS - 1], Arrays.toString(samples));
        System.out.print(report);
        Files.writeString(Path.of(out), report + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Every scenario ROUNDS times, fixture setup excluded; thread CPU per tick. */
    private static double sample(ThreadMXBean bean) throws Exception {
        long cpu = 0;
        long ticks = 0;
        for (int round = 0; round < ROUNDS; round++) {
            for (int seed = 0; seed < SCENARIOS; seed++) {
                try (var f = new JavaTickFixture()) {
                    long begin = edge(bean);
                    PredictionScenarios.run(f, seed, result -> {});
                    long wait = System.nanoTime();
                    long end = edge(bean);
                    cpu += end - begin - (System.nanoTime() - wait);
                    ticks += PredictionScenarios.TICKS;
                }
            }
        }
        return (double) cpu / ticks;
    }

    /**
     * Thread CPU time is coarse on Windows: busy-wait to the counter's next step, so an interval runs edge to edge and
     * the wait at its end, timed by the wall clock, comes off it.
     */
    private static long edge(ThreadMXBean bean) {
        long start = bean.getCurrentThreadCpuTime();
        long now;
        do {
            now = bean.getCurrentThreadCpuTime();
        } while (now == start);
        return now;
    }
}
