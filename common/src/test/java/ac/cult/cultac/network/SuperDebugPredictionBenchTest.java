package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Wall time, thread CPU and allocated bytes of SuperDebug's prediction listener for a Java player nothing watches,
 * per call, and of a whole standing client tick around it. Runs only when {@code CULTAC_SUPERDEBUG_BENCH} names a
 * file the medians are appended to, under the label {@code CULTAC_SUPERDEBUG_BENCH_LABEL}.
 */
class SuperDebugPredictionBenchTest {
    private static final Vec3 START = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 RESTING = new Vec3(0.0, -0.0784000015258789, 0.0);
    private static final int CALLS = 2_000_000;
    /** Ticks between timer resets, inside the fixture's ten seconds of timer slack. */
    private static final int TICKS_PER_BLOCK = 100;

    private static final int BLOCKS = 20;
    private static final int WARMUP_REPS = 5;
    private static final int REPS = 15;

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void unwatchedPrediction() throws Exception {
        String out = System.getenv("CULTAC_SUPERDEBUG_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        String label = System.getenv().getOrDefault("CULTAC_SUPERDEBUG_BENCH_LABEL", "?");
        StringBuilder line = new StringBuilder(label)
                .append(" compactHeaders=")
                .append(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                        .getVMOption("UseCompactObjectHeaders")
                        .getValue());
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            f.tick(START, true);
            PredictionResult result = f.tick(START, true);
            assertEquals(0, result.getIdentifier());
            SuperDebug debug = f.player.checkManager.getListener(SuperDebug.class);
            assertNull(debug.watch());

            PlatformPlayer original = f.player.platformPlayer;
            f.player.platformPlayer = platformPlayer();
            PredictionComplete complete = new PredictionComplete(result);
            line.append(" | listener ")
                    .append(measure(CALLS, () -> {
                        for (int i = 0; i < CALLS; i++) debug.onPredictionComplete(complete);
                    }));
            f.player.platformPlayer = original;

            line.append(" | tick ").append(measure(BLOCKS * TICKS_PER_BLOCK, () -> {
                for (int block = 0; block < BLOCKS; block++) {
                    resetTimers(f);
                    for (int t = 0; t < TICKS_PER_BLOCK; t++) f.tick(START, true);
                }
            }));
        }
        Files.writeString(
                Path.of(out),
                line.append(System.lineSeparator()).toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void resetTimers(JavaTickFixture f) {
        try {
            f.resetTimerBalances();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Medians and spreads per unit of wall time, thread CPU and allocated bytes over {@link #REPS} runs. */
    private static String measure(long units, Runnable run) {
        for (int i = 0; i < WARMUP_REPS; i++) run.run();
        double[] wall = new double[REPS];
        double[] cpu = new double[REPS];
        double[] bytes = new double[REPS];
        long thread = Thread.currentThread().threadId();
        for (int rep = 0; rep < REPS; rep++) {
            long b0 = THREADS.getThreadAllocatedBytes(thread);
            long c0 = THREADS.getCurrentThreadCpuTime();
            long w0 = System.nanoTime();
            run.run();
            wall[rep] = (System.nanoTime() - w0) / (double) units;
            cpu[rep] = (THREADS.getCurrentThreadCpuTime() - c0) / (double) units;
            bytes[rep] = (THREADS.getThreadAllocatedBytes(thread) - b0) / (double) units;
        }
        return String.format(
                "wall %s ns, cpu %s ns, alloc %s B per unit (%d units x %d reps)",
                stat(wall), stat(cpu), stat(bytes), units, REPS);
    }

    private static String stat(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return String.format("%.1f [%.1f..%.1f]", sorted[n / 2], sorted[n / 4], sorted[3 * n / 4]);
    }

    /** A platform player in a world named "world"; its calls without arguments allocate nothing. */
    private static PlatformPlayer platformPlayer() {
        PlatformWorld world = proxy(PlatformWorld.class, "getName", "world");
        return proxy(PlatformPlayer.class, "getWorld", world);
    }

    private static <T> T proxy(Class<T> type, String method, Object value) {
        InvocationHandler handler = (self, called, args) -> {
            if (called.getName().equals(method)) return value;
            Class<?> returned = called.getReturnType();
            if (returned == boolean.class) return Boolean.FALSE;
            if (returned.isPrimitive() && returned != void.class) {
                throw new UnsupportedOperationException(called.getName());
            }
            return null;
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
