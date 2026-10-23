package ac.cult.cultac.player;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread CPU per foreign-ping lookup and per send+answer, scanned ring vs counted ring; CULT_BENCH=1 enables it. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class ForeignPingsBenchTest {
    private static final int REPS = 9;
    private static final int[] SIZES = {0, 16, 256, 1024};
    static int sink;

    @Test
    void foreignPings() throws Exception {
        var bean = ManagementFactory.getThreadMXBean();
        var out = new StringBuilder();
        for (int size : SIZES) {
            var scanned = new ForeignPingsTest.ScannedRing();
            var counted = new ForeignPings();
            var random = new SplittableRandom(size);
            for (int i = 0; i < size; i++) {
                int id = random.nextInt() & Integer.MAX_VALUE;
                scanned.add(id);
                counted.add(id);
            }
            int[] probes = new int[4096];
            for (int i = 0; i < probes.length; i++) probes[i] = random.nextInt() | Integer.MIN_VALUE;
            int lookups = size >= 1024 ? 2_000_000 : size >= 256 ? 8_000_000 : 100_000_000;
            IntPredicate base = scanned::contains;
            IntPredicate branch = counted::contains;
            run(base, probes, lookups);
            run(branch, probes, lookups);
            long[][] ps = new long[2][REPS];
            for (int rep = 0; rep < REPS; rep++) {
                for (int side = 0; side < 2; side++) {
                    long start = bean.getCurrentThreadCpuTime();
                    run(side == 0 ? base : branch, probes, lookups);
                    ps[side][rep] = (bean.getCurrentThreadCpuTime() - start) * 1000 / lookups;
                }
            }
            report(out, "lookup", size, ps);
        }
        for (int size : SIZES) {
            var scanned = new ForeignPingsTest.ScannedRing();
            var counted = new ForeignPings();
            for (int i = 0; i < size; i++) {
                scanned.add(Short.MIN_VALUE - i);
                counted.add(Short.MIN_VALUE - i);
            }
            int churns = 20_000_000;
            // A ping sent and the oldest one answered, as pongs arrive in ping order.
            IntConsumer base = k -> {
                scanned.add(Short.MIN_VALUE - ((k + size) & 0xFFFFF));
                scanned.remove(Short.MIN_VALUE - (k & 0xFFFFF));
            };
            IntConsumer branch = k -> {
                counted.add(Short.MIN_VALUE - ((k + size) & 0xFFFFF));
                counted.remove(Short.MIN_VALUE - (k & 0xFFFFF));
            };
            int[] next = new int[2];
            long[][] ps = new long[2][REPS + 1];
            for (int rep = 0; rep <= REPS; rep++) {
                for (int side = 0; side < 2; side++) {
                    IntConsumer churn = side == 0 ? base : branch;
                    long start = bean.getCurrentThreadCpuTime();
                    for (int i = 0; i < churns; i++) churn.accept(next[side]++);
                    ps[side][rep] = (bean.getCurrentThreadCpuTime() - start) * 1000 / churns;
                }
            }
            report(out, "send+answer", size, new long[][] {
                Arrays.copyOfRange(ps[0], 1, REPS + 1), Arrays.copyOfRange(ps[1], 1, REPS + 1)
            });
        }
        System.out.print(out);
        String file = System.getenv("CULT_BENCH_OUT");
        if (file != null)
            Files.writeString(
                    Path.of(file), out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void report(StringBuilder out, String operation, int size, long[][] ps) {
        for (int side = 0; side < 2; side++) {
            long[] sorted = ps[side].clone();
            Arrays.sort(sorted);
            out.append("foreign-pings ").append(operation).append(" size=").append(size)
                    .append(side == 0 ? " scanned" : " counted").append(" ps/op median=")
                    .append(sorted[sorted.length / 2]).append(" min=").append(sorted[0]).append(" max=")
                    .append(sorted[sorted.length - 1]).append(System.lineSeparator());
        }
    }

    private static void run(IntPredicate lookup, int[] probes, int lookups) {
        int hits = 0;
        for (int i = 0; i < lookups; i++) {
            if (lookup.test(probes[i & (probes.length - 1)])) hits++;
        }
        sink += hits;
    }
}
