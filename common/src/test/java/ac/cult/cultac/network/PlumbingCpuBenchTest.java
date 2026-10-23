package ac.cult.cultac.network;

import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.lists.EvictingQueue;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU of the per-player plumbing: one idle setback watchdog fire and {@link EvictingQueue#add} on a full queue.
 * Runs only with {@code CULT_PLUMB_BENCH} set; results go to stdout and {@code build/plumbing-cpu-bench.txt}.
 */
class PlumbingCpuBenchTest {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    /** Long enough that Windows' 15.6 ms thread CPU granularity stays under 3% of a round. */
    private static final long ROUND_NANOS = 600_000_000L;

    @Test
    void plumbingCpu() throws Exception {
        Assumptions.assumeTrue(System.getenv("CULT_PLUMB_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_PLUMB_BENCH_REPS", "9"));
        var lines = new StringBuilder();
        lines.append(watchdog(repetitions)).append('\n');
        for (int size : new int[] {3, 40, 60})
            lines.append(evictingAdd(size, repetitions)).append('\n');
        System.out.print(lines);
        write(lines.toString());
    }

    private static String watchdog(int repetitions) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.nettyScheduler.removeScheduler();
            // The fixture's platform is a Mockito mock; production's runInModel runs the task directly.
            Field platform = CultConnection.class.getDeclaredField("platform");
            platform.setAccessible(true);
            platform.set(f.fixture.connection, null);
            Runnable fire = fire(f);
            var movementTimes = f.player.checkManager.getSimulationProcessor().getLastMovementTime();
            double[] perFire = new double[repetitions];
            for (int r = -3; r < repetitions; r++) {
                long fires = 0;
                long wallStart = System.nanoTime();
                long start = THREADS.getCurrentThreadCpuTime();
                while (System.nanoTime() - wallStart < ROUND_NANOS) {
                    movementTimes.add(System.nanoTime());
                    for (int i = 0; i < 1_000; i++) fire.run();
                    fires += 1_000;
                }
                long cpu = THREADS.getCurrentThreadCpuTime() - start;
                f.fixture.channel.runPendingTasks();
                f.fixture.channel.releaseOutbound();
                if (r >= 0) perFire[r] = cpu / (double) fires;
            }
            if (f.player.getSetbackTeleportUtil().isPendingSetback()) throw new AssertionError("the watchdog acted");
            return line("watchdog idle fire", perFire);
        }
    }

    /** The scheduled watchdog body: {@code watchdog()} where it exists, else the task it replaced. */
    private static Runnable fire(JavaTickFixture f) throws ReflectiveOperationException {
        NettyScheduler scheduler = f.player.nettyScheduler;
        try {
            Method watchdog = NettyScheduler.class.getDeclaredMethod("watchdog");
            watchdog.setAccessible(true);
            return () -> invoke(watchdog, scheduler);
        } catch (NoSuchMethodException base) {
            Method tick = NettyScheduler.class.getDeclaredMethod("tick");
            tick.setAccessible(true);
            return () -> f.player.user.executeAfterWrites(() -> invoke(tick, scheduler));
        }
    }

    private static void invoke(Method method, Object target) {
        try {
            method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String evictingAdd(int size, int repetitions) {
        var queue = new EvictingQueue<Long>(size);
        Long value = 7L;
        double[] perAdd = new double[repetitions];
        long sink = 0;
        for (int r = -3; r < repetitions; r++) {
            long adds = 0;
            long wallStart = System.nanoTime();
            long start = THREADS.getCurrentThreadCpuTime();
            while (System.nanoTime() - wallStart < ROUND_NANOS) {
                for (int i = 0; i < 100_000; i++) queue.add(value);
                adds += 100_000;
            }
            long cpu = THREADS.getCurrentThreadCpuTime() - start;
            sink += queue.getYoungest();
            if (r >= 0) perAdd[r] = cpu / (double) adds;
        }
        if (sink == 0) throw new AssertionError();
        return line("EvictingQueue.add full " + size, perAdd);
    }

    private static String line(String name, double[] nanos) {
        double[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format(
                Locale.ROOT,
                "%-28s median %8.1f ns  min %8.1f  max %8.1f  (%d reps)",
                name,
                sorted[sorted.length / 2],
                sorted[0],
                sorted[sorted.length - 1],
                sorted.length);
    }

    private static void write(String text) throws IOException {
        Path out = Path.of("build", "plumbing-cpu-bench.txt");
        Files.createDirectories(out.getParent());
        try (var stream = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
            stream.print(text);
        }
    }
}
