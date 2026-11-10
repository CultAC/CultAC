package ac.cult.cultac.cloud.transport;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Allocation and CPU of the compression thread per message, messages written into arrays of their own against arrays
 * from the frame arrays the sender gives back once sent. Runs only when {@code CULTAC_MESSAGE_BENCH} names a file the
 * results are appended to.
 */
@EnabledIfEnvironmentVariable(named = "CULTAC_MESSAGE_BENCH", matches = ".+")
class MessageArrayBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int PLAYERS = 8;
    private static final int MESSAGES = 20_000;
    private static final int REPS = 7;

    @Test
    void bench() throws Exception {
        List<List<byte[]>> parts = parts(new Random(5));
        ExecutorService pool = CompressionPipeline.newPool(1, "message-bench");
        StringBuilder lines = new StringBuilder();
        try {
            long thread = pool.submit(() -> Thread.currentThread().threadId()).get();
            for (int warm = 0; warm < 2; warm++) {
                for (boolean pooled : new boolean[] {false, true}) run(pool, thread, parts, pooled);
            }
            for (boolean pooled : new boolean[] {false, true}) {
                long[] alloc = new long[REPS];
                long[] cpu = new long[REPS];
                for (int rep = 0; rep < REPS; rep++) {
                    long[] run = run(pool, thread, parts, pooled);
                    alloc[rep] = run[0];
                    cpu[rep] = run[1];
                }
                Arrays.sort(alloc);
                Arrays.sort(cpu);
                lines.append(String.format(
                        Locale.ROOT,
                        "messages %s: alloc median %.1f B/message (min %.1f, max %.1f), cpu median %.0f ns/message%n",
                        pooled ? "pooled" : "exact",
                        (double) alloc[REPS / 2] / MESSAGES,
                        (double) alloc[0] / MESSAGES,
                        (double) alloc[REPS - 1] / MESSAGES,
                        (double) cpu[REPS / 2] / MESSAGES));
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.print(lines);
        Files.writeString(
                Path.of(System.getenv("CULTAC_MESSAGE_BENCH")),
                lines,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Bytes allocated and CPU spent on the compression thread over {@link #MESSAGES} messages. */
    private static long[] run(ExecutorService pool, long thread, List<List<byte[]>> parts, boolean pooled)
            throws Exception {
        FrameBuffers frames = pooled ? new FrameBuffers() : null;
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        long alloc = THREADS.getThreadAllocatedBytes(thread);
        long cpu = THREADS.getThreadCpuTime(thread);
        try (CompressionPipeline pipeline = new CompressionPipeline(pool, 1, queued)) {
            pipeline.stream();
            for (int i = 0; i < MESSAGES; i++) {
                List<byte[]> part = parts.get(i % parts.size());
                List<CloudConnection.Queued> records = new ArrayList<>(part.size());
                int bytes = 0;
                for (byte[] record : part) {
                    records.add(new CloudConnection.Queued(0, record, 0));
                    bytes += record.length;
                }
                queued.addAndGet(bytes);
                pipeline.submitQueued(records, bytes, true, frames);
                ByteBuffer message = pipeline.take();
                pipeline.sent(message.remaining());
                if (frames != null) frames.give(message.array());
            }
        }
        return new long[] {
            THREADS.getThreadAllocatedBytes(thread) - alloc, THREADS.getThreadCpuTime(thread) - cpu
        };
    }

    /** One 50 ms part of {@link #PLAYERS} players' frames, of the sizes and repetition the capture sends. */
    private static List<List<byte[]>> parts(Random random) {
        List<List<byte[]>> parts = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            List<byte[]> part = new ArrayList<>();
            for (int p = 0; p < PLAYERS; p++) {
                byte[] frame = new byte[300 + random.nextInt(2400)];
                for (int b = 0; b < frame.length; b++)
                    frame[b] = (byte) (random.nextInt(4) == 0 ? random.nextInt() : (b + p) % 29);
                part.add(frame);
            }
            parts.add(part);
        }
        return parts;
    }
}
