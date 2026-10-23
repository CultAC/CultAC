package ac.cult.cultac.player;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread CPU of the round-4 ping map and entries view, old form against new, interleaved; CULT_BENCH=1 enables it. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class CpuRound4BenchTest {
    private static final int REPS = 9;
    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    static long sink;

    @Test
    void pendingPingMap() throws Exception {
        Random random = new Random(7);
        int[] ids = new int[1 << 16];
        for (int i = 0; i < ids.length; i++) ids[i] = random.nextInt() | Integer.MIN_VALUE;
        Map<Integer, Object> boxed = new HashMap<>(4);
        Int2ObjectOpenHashMap<Object> primitive = new Int2ObjectOpenHashMap<>(4);
        Object value = new Object();
        compare(
                "pending-ping-map",
                100_000_000,
                ops -> {
                    long hits = 0;
                    for (long i = 0; i < ops; i++) {
                        int id = ids[(int) (i & (ids.length - 1))];
                        if (!boxed.containsKey(id)) boxed.put(id, value);
                        if (boxed.remove(id) != null) hits++;
                        if (boxed.remove(id) != null) hits++;
                    }
                    return ops + hits;
                },
                ops -> {
                    long hits = 0;
                    for (long i = 0; i < ops; i++) {
                        int id = ids[(int) (i & (ids.length - 1))];
                        if (!primitive.containsKey(id)) primitive.put(id, value);
                        if (primitive.remove(id) != null) hits++;
                        if (primitive.remove(id) != null) hits++;
                    }
                    return ops + hits;
                });
    }

    @Test
    void entriesView() throws Exception {
        Set<Object> active = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 5; i++) active.add(new Object());
        Set<Object> cached = Collections.unmodifiableSet(active);
        compare(
                "entries-view",
                20_000_000,
                calls -> {
                    long seen = 0;
                    for (long i = 0; i < calls; i++) for (Object ignored : Collections.unmodifiableSet(active)) seen++;
                    return seen;
                },
                calls -> {
                    long seen = 0;
                    for (long i = 0; i < calls; i++) for (Object ignored : cached) seen++;
                    return seen;
                });
    }

    private static void compare(String name, long units, LongUnaryOperator old, LongUnaryOperator current)
            throws Exception {
        for (int warm = 0; warm < 3; warm++) {
            sink += old.applyAsLong(units);
            sink += current.applyAsLong(units);
        }
        long[] oldPs = new long[REPS], newPs = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            oldPs[rep] = time(old, units);
            newPs[rep] = time(current, units);
        }
        String line = name + " ps/unit old " + summary(oldPs) + " new " + summary(newPs) + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static long time(LongUnaryOperator body, long units) {
        long start = BEAN.getCurrentThreadCpuTime();
        sink += body.applyAsLong(units);
        return (BEAN.getCurrentThreadCpuTime() - start) * 1000 / units;
    }

    private static String summary(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return "median=" + sorted[sorted.length / 2] + " min=" + sorted[0] + " max=" + sorted[sorted.length - 1];
    }
}
