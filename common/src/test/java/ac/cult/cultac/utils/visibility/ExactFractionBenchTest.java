package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Allocated bytes and thread CPU of exact fraction arithmetic on eye and block coordinates; runs only with
 * CULT_FRACTION_BENCH set to an output file.
 */
class ExactFractionBenchTest {
    private static final int POINTS = 64;
    private static final int WARMUP_CYCLES = 20_000;
    private static final int BATCH = 20;
    private static final long REP_CPU_NANOS = 300_000_000L;
    private static final int REPS = 15;

    @Test
    void arithmetic() throws Exception {
        String out = System.getenv("CULT_FRACTION_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        Random random = new Random(5);
        ExactFraction[] eyes = new ExactFraction[POINTS];
        ExactFraction[] corners = new ExactFraction[POINTS];
        for (int i = 0; i < POINTS; i++) {
            eyes[i] = ExactFraction.of(random.nextInt(64) - 32 + random.nextDouble() + 1.62F);
            corners[i] = ExactFraction.of(random.nextInt(64) - 32 + random.nextInt(17) / 16.0D);
        }
        long[] sink = {0};
        Runnable cycle = () -> {
            for (int i = 0; i < POINTS; i++) {
                ExactFraction eye = eyes[i];
                ExactFraction near = corners[i].subtract(eye);
                ExactFraction far = corners[(i + 1) % POINTS].subtract(eye);
                ExactFraction other = corners[(i + 7) % POINTS].subtract(eye);
                if (near.signum() == 0) continue;
                ExactFraction scale = far.divide(near);
                ExactFraction shadow = other.multiply(scale).add(far);
                sink[0] += shadow.compareTo(near) + scale.compareTo(ExactFraction.ONE);
            }
        };
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < WARMUP_CYCLES; i++) cycle.run();
        double[] cpu = new double[REPS];
        double[] bytes = new double[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long beginBytes = bean.getThreadAllocatedBytes(thread);
            long begin = bean.getCurrentThreadCpuTime();
            long elapsed;
            long count = 0;
            do {
                for (int i = 0; i < BATCH; i++) cycle.run();
                count += BATCH;
                elapsed = bean.getCurrentThreadCpuTime() - begin;
            } while (elapsed < REP_CPU_NANOS);
            bytes[rep] = (double) (bean.getThreadAllocatedBytes(thread) - beginBytes) / count;
            cpu[rep] = (double) elapsed / count;
        }
        Arrays.sort(cpu);
        Arrays.sort(bytes);
        String report = String.format(
                "fraction cycle x%d: %.0f B/cycle (min %.0f, max %.0f), median %.0f ns/cycle (min %.0f, max %.0f)"
                        + " [%d]%n",
                POINTS,
                bytes[REPS / 2],
                bytes[0],
                bytes[REPS - 1],
                cpu[REPS / 2],
                cpu[0],
                cpu[REPS - 1],
                sink[0] & 1);
        System.out.print(report);
        Files.writeString(Path.of(out), report + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The same shadow-row cycle on this class and on the all-BigInteger parent, reps alternating. */
    @Test
    void againstParent() throws Exception {
        String out = System.getenv("CULT_FRACTION_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        Random random = new Random(5);
        double[] eyes = new double[POINTS], corners = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            eyes[i] = random.nextInt(4000) - 2000 + random.nextDouble() + 1.62F;
            corners[i] = Math.floor(eyes[i]) + random.nextInt(9) - 4 + random.nextInt(17) / 16.0D;
        }
        ExactFraction[] eye = new ExactFraction[POINTS], corner = new ExactFraction[POINTS];
        ParentExactFraction[] parentEye = new ParentExactFraction[POINTS], parentCorner = new ParentExactFraction[POINTS];
        for (int i = 0; i < POINTS; i++) {
            eye[i] = ExactFraction.of(eyes[i]);
            corner[i] = ExactFraction.of(corners[i]);
            parentEye[i] = ParentExactFraction.of(eyes[i]);
            parentCorner[i] = ParentExactFraction.of(corners[i]);
        }
        long[] sink = {0};
        Runnable current = () -> {
            for (int i = 0; i < POINTS; i++) {
                ExactFraction low = corner[i].subtract(eye[i]), high = corner[(i + 1) % POINTS].subtract(eye[i]);
                ExactFraction normal = high.negate().multiply(ExactFraction.MINUS_ONE).subtract(low);
                ExactFraction plane = normal.multiply(low).add(high);
                sink[0] += plane.compareTo(low) + normal.signum();
            }
        };
        Runnable parent = () -> {
            for (int i = 0; i < POINTS; i++) {
                ParentExactFraction low = parentCorner[i].subtract(parentEye[i]);
                ParentExactFraction high = parentCorner[(i + 1) % POINTS].subtract(parentEye[i]);
                ParentExactFraction normal =
                        high.negate().multiply(ParentExactFraction.ONE.negate()).subtract(low);
                ParentExactFraction plane = normal.multiply(low).add(high);
                sink[0] += plane.compareTo(low) + normal.signum();
            }
        };
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int i = 0; i < WARMUP_CYCLES; i++) {
            current.run();
            parent.run();
        }
        double[][] cpu = new double[2][REPS], bytes = new double[2][REPS];
        Runnable[] bodies = {parent, current};
        for (int rep = 0; rep < REPS; rep++) {
            for (int body = 0; body < 2; body++) {
                long beginBytes = bean.getCurrentThreadAllocatedBytes(), begin = bean.getCurrentThreadCpuTime();
                long elapsed, count = 0;
                do {
                    for (int i = 0; i < BATCH; i++) bodies[body].run();
                    count += BATCH;
                    elapsed = bean.getCurrentThreadCpuTime() - begin;
                } while (elapsed < REP_CPU_NANOS);
                bytes[body][rep] = (double) (bean.getCurrentThreadAllocatedBytes() - beginBytes) / count;
                cpu[body][rep] = (double) elapsed / count;
            }
        }
        StringBuilder report = new StringBuilder();
        String[] names = {"parent", "current"};
        for (int body = 0; body < 2; body++) {
            Arrays.sort(cpu[body]);
            Arrays.sort(bytes[body]);
            report.append(String.format(
                    "shadow cycle x%d %s: %.0f B/cycle (min %.0f, max %.0f), median %.0f ns/cycle (min %.0f, max %.0f)%n",
                    POINTS, names[body], bytes[body][REPS / 2], bytes[body][0], bytes[body][REPS - 1],
                    cpu[body][REPS / 2], cpu[body][0], cpu[body][REPS - 1]));
        }
        report.append("[").append(sink[0] & 1).append("]\n");
        System.out.print(report);
        Files.writeString(Path.of(out), report + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
