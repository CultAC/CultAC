package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.value.Direction;
import com.sun.management.ThreadMXBean;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonPushes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocation of one idle tick's piston bookkeeping (the push pass, the client tick end, the transaction
 * sweep and a chunk forget) with no piston near: the base sweeps versus the empty checks and the shared empty pushes,
 * interleaved per round. Runs only with {@code CULT_PISTON_BENCH} set.
 */
class PistonIdleBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long ROUND_NANOS = 600_000_000L;

    private final Set<Object> activePistons = new HashSet<>();
    private final Long2ObjectOpenHashMap<long[]> movingPistonStates = new Long2ObjectOpenHashMap<>();
    private final PistonPushes noPushes;

    PistonIdleBenchTest() {
        SimpleCollisionBox empty = new SimpleCollisionBox();
        noPushes = new PistonPushes(empty, empty, empty, Collections.emptySet());
    }

    @Test
    void idleTickCost() {
        Assumptions.assumeTrue(System.getenv("CULT_PISTON_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_PISTON_BENCH_REPS", "9"));
        double[][] cpu = new double[2][repetitions];
        double[][] bytes = new double[2][repetitions];
        long sink = 0;
        for (int r = -5; r < repetitions; r++) {
            for (int path = 0; path < 2; path++) {
                long[] round = round(path == 1);
                sink += round[2];
                if (r >= 0) {
                    cpu[path][r] = round[0];
                    bytes[path][r] = round[1];
                }
            }
        }
        if (sink == 0) throw new AssertionError();
        System.out.println(line("idle tick base cpu ns", cpu[0], 1000));
        System.out.println(line("idle tick new  cpu ns", cpu[1], 1000));
        System.out.println(line("idle tick base bytes", bytes[0], 1000));
        System.out.println(line("idle tick new  bytes", bytes[1], 1000));
    }

    /** CPU picoseconds and allocated millibytes per idle tick, and a sink. */
    private long[] round(boolean newPath) {
        long ticks = 0, sink = 0;
        long thread = Thread.currentThread().threadId();
        long wallStart = System.nanoTime();
        long start = THREADS.getCurrentThreadCpuTime();
        long allocStart = THREADS.getThreadAllocatedBytes(thread);
        while (System.nanoTime() - wallStart < ROUND_NANOS) {
            for (int i = 0; i < 100_000; i++) {
                PistonPushes pushes = newPath ? newTick(i) : baseTick(i);
                if (pushes.getSlimeBlockLaunches().isEmpty()) sink++;
            }
            ticks += 100_000;
        }
        long cpu = THREADS.getCurrentThreadCpuTime() - start;
        long alloc = THREADS.getThreadAllocatedBytes(thread) - allocStart;
        return new long[] {cpu * 1000 / ticks, alloc * 1000 / ticks, sink};
    }

    private PistonPushes baseTick(int transactionId) {
        SimpleCollisionBox empty = new SimpleCollisionBox();
        PistonPushes pushes = new PistonPushes(empty, empty, empty, Collections.<Direction>emptySet());
        for (Object data : activePistons) if (data.hashCode() == transactionId) sink(data);
        activePistons.removeIf(data -> data.hashCode() == transactionId);
        List<long[]> finished = new ArrayList<>();
        movingPistonStates.long2ObjectEntrySet().removeIf(entry -> entry.getValue().length == transactionId);
        for (long[] state : finished) sink(state);
        activePistons.removeIf(data -> data.hashCode() < transactionId);
        int x = transactionId & 31, z = transactionId >> 5;
        movingPistonStates.values().removeIf(state -> state[0] >> 4 == x && state[1] >> 4 == z);
        return pushes;
    }

    private PistonPushes newTick(int transactionId) {
        if (!activePistons.isEmpty()) {
            for (Object data : activePistons) if (data.hashCode() == transactionId) sink(data);
            activePistons.removeIf(data -> data.hashCode() == transactionId);
        }
        if (!movingPistonStates.isEmpty()) {
            List<long[]> finished = new ArrayList<>();
            movingPistonStates.long2ObjectEntrySet().removeIf(entry -> entry.getValue().length == transactionId);
            for (long[] state : finished) sink(state);
        }
        if (!activePistons.isEmpty()) activePistons.removeIf(data -> data.hashCode() < transactionId);
        if (!movingPistonStates.isEmpty()) {
            int x = transactionId & 31, z = transactionId >> 5;
            movingPistonStates.values().removeIf(state -> state[0] >> 4 == x && state[1] >> 4 == z);
        }
        return noPushes;
    }

    private static void sink(Object value) {
        if (value == null) throw new AssertionError();
    }

    private static String line(String name, double[] values, double scale) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format(
                Locale.ROOT,
                "%-24s median %8.2f  min %8.2f  max %8.2f  (%d reps)",
                name,
                sorted[sorted.length / 2] / scale,
                sorted[0] / scale,
                sorted[sorted.length - 1] / scale,
                sorted.length);
    }
}
