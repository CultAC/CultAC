package ac.cult.cultac.platform.velocity;

import static ac.cult.cultac.platform.velocity.CompressedFrames.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.platform.velocity.VelocityCompressedForwardTest.Fixture;
import ac.cult.cultac.platform.velocity.VelocityCompressedForwardTest.Options;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU and heap allocation per frame through the whole stand-in pipeline, forwarding off and on interleaved.
 * Both sides include the stand-ins' own inflate and copies, and the stand-ins compress with java.util.zip, so only the
 * difference is meaningful and it is indicative. CULT_FORWARD_BENCH=1 enables it.
 */
@EnabledIfEnvironmentVariable(named = "CULT_FORWARD_BENCH", matches = "1")
class VelocityCompressedForwardBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int REPS = 7;
    private static final int READS = 500;
    private static final int[] SIZES = {300, 700, 2_000, 9_000, 28_000};

    @Test
    void forwardingAgainstToday() {
        assumeTrue(
                THREADS.isThreadCpuTimeSupported()
                        && THREADS.isThreadCpuTimeEnabled()
                        && THREADS.isThreadAllocatedMemorySupported()
                        && THREADS.isThreadAllocatedMemoryEnabled(),
                "thread CPU time and allocation counters are available");
        byte[][] reads = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) reads[i] = frame(body(packet(SIZES[i], i), 256));
        for (int rep = 0; rep < REPS; rep++) {
            for (boolean forward : new boolean[] {false, true}) {
                long[] cost = run(forward ? Options.forwarding() : Options.today(), reads);
                System.out.println(String.format(
                        Locale.ROOT,
                        "rep %d %-5s cpu %.2f us/frame, alloc %.0f B/frame",
                        rep,
                        forward ? "on" : "off",
                        cost[0] / 1_000.0 / (READS * SIZES.length),
                        (double) cost[1] / (READS * SIZES.length)));
            }
        }
    }

    private static long[] run(Options options, byte[][] reads) {
        try (var f = new Fixture(options)) {
            long thread = Thread.currentThread().threadId();
            long cpu = THREADS.getThreadCpuTime(thread);
            long allocated = THREADS.getThreadAllocatedBytes(thread);
            for (int i = 0; i < READS; i++) {
                for (byte[] read : reads) {
                    f.read(read);
                    assertTrue(f.client.releaseOutbound(), "every read reaches the client");
                }
            }
            return new long[] {
                THREADS.getThreadCpuTime(thread) - cpu, THREADS.getThreadAllocatedBytes(thread) - allocated
            };
        }
    }
}
