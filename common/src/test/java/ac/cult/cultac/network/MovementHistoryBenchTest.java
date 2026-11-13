package ac.cult.cultac.network;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Allocated bytes and thread CPU per completed Java prediction in the debug listener, which keeps the movement history.
 * Runs only when {@code CULTAC_HISTORY_BENCH} names a file the medians are appended to.
 */
@EnabledIfEnvironmentVariable(named = "CULTAC_HISTORY_BENCH", matches = ".+")
class MovementHistoryBenchTest {
    private static final Vec3 START = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 RESTING = new Vec3(0.0, -0.0784000015258789, 0.0);
    private static final int CALLS = 20_000_000;
    private static final int WARMUP_REPS = 4;
    private static final int REPS = 9;

    @Test
    void historyPerPrediction() throws Exception {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            PredictionResult result = f.tick(START, true);
            SuperDebug debug = f.player.checkManager.getListener(SuperDebug.class);
            PredictionComplete complete = new PredictionComplete(result);
            double[] bytes = new double[REPS];
            double[] nanos = new double[REPS];
            for (int rep = -WARMUP_REPS; rep < REPS; rep++) {
                long allocated = threads.getCurrentThreadAllocatedBytes();
                long cpu = threads.getCurrentThreadCpuTime();
                for (int i = 0; i < CALLS; i++) debug.onPredictionComplete(complete);
                if (rep < 0) continue;
                nanos[rep] = (threads.getCurrentThreadCpuTime() - cpu) / (double) CALLS;
                bytes[rep] = (threads.getCurrentThreadAllocatedBytes() - allocated) / (double) CALLS;
            }
            Arrays.sort(bytes);
            Arrays.sort(nanos);
            Files.writeString(
                    Path.of(System.getenv("CULTAC_HISTORY_BENCH")),
                    String.format(
                            "history per prediction: alloc %.1f B [%.1f %.1f], cpu %.1f ns [%.1f %.1f], %s%n",
                            bytes[REPS / 2],
                            bytes[0],
                            bytes[REPS - 1],
                            nanos[REPS / 2],
                            nanos[0],
                            nanos[REPS - 1],
                            debug.history().length()),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
    }
}
