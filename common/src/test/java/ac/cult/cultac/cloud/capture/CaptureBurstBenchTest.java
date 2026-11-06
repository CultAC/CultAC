package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocated bytes of one player's capture per client tick when tick ends arrive in bursts, each burst's
 * verdicts after its last cut, with frames of varying sizes. Runs only when {@code CULTAC_CAPTURE_BURST_BENCH} names a
 * file the medians are appended to.
 */
class CaptureBurstBenchTest {
    private static final UUID PLAYER = new UUID(3, 4);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
    private static final int TICK_END = 1;
    private static final int MOVE = 3;
    private static final int ENTITY = 6;
    private static final int TICKS = 6_000;
    private static final int WARMUP_REPS = 5;
    private static final int REPS = 11;

    private static final class Discard implements CaptureStream.Sink {
        long bytes;

        @Override
        public boolean send(byte[] record) {
            bytes += record.length;
            return true;
        }

        @Override
        public boolean hold(long bytes) {
            return true;
        }

        @Override
        public void release(long bytes) {}

        @Override
        public int maxFrameBytes() {
            return 1 << 20;
        }

        @Override
        public CloudConnection.Terms terms() {
            return CloudConnection.Terms.sections(0, 0);
        }
    }

    private static ByteBuf packet(int id, int bodyBytes, SplittableRandom random) {
        byte[] bytes = new byte[1 + bodyBytes];
        bytes[0] = (byte) id;
        for (int i = 1; i < bytes.length; i++) bytes[i] = (byte) random.nextInt(256);
        return Unpooled.wrappedBuffer(bytes);
    }

    private static long run(CaptureStream stream, List<ByteBuf> packets, int[] sizes, ByteBuf end, long tick) {
        long nanos = tick * 50_000_000L;
        int t = 0;
        while (t < TICKS) {
            int burst = 1 + (t / 7) % 3;
            long first = tick + 1;
            for (int b = 0; b < burst; b++, t++) {
                int records = sizes[t % sizes.length];
                for (int i = 0; i < records; i++) {
                    ByteBuf body = packets.get(i % packets.size());
                    int at = body.readerIndex();
                    stream.tap(IntakeWire.CLIENTBOUND, ENTITY, body, nanos += 600_000, -64, 384, SESSION, 0);
                    body.readerIndex(at);
                }
                int at = end.readerIndex();
                stream.tap(IntakeWire.SERVERBOUND, TICK_END, end, nanos += 600_000, -64, 384, SESSION, 0);
                end.readerIndex(at);
                stream.cut(nanos);
                tick++;
            }
            for (long v = first; v <= tick; v++) stream.verdict(v, v, 0, List.of(), null, List.of(), null);
        }
        return tick;
    }

    @Test
    void capturePerBurstyTick() throws IOException {
        String out = System.getenv("CULTAC_CAPTURE_BURST_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        SplittableRandom random = new SplittableRandom(42);
        List<ByteBuf> packets = new ArrayList<>();
        for (int i = 0; i < 64; i++) packets.add(packet(ENTITY, 8 + random.nextInt(40), random));
        int[] sizes = new int[97];
        for (int i = 0; i < sizes.length; i++) sizes[i] = 20 + random.nextInt(100);
        ByteBuf end = packet(TICK_END, 0, random);
        Discard sink = new Discard();
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.join(sink, IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        stream.capture(new int[] {TICK_END, MOVE}, new int[] {ENTITY}, new int[0]);
        long tick = 0;
        for (int i = 0; i < WARMUP_REPS; i++) tick = run(stream, packets, sizes, end, tick);
        double[] cpu = new double[REPS];
        double[] alloc = new double[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long bytes = threads.getThreadAllocatedBytes(thread);
            long time = threads.getCurrentThreadCpuTime();
            tick = run(stream, packets, sizes, end, tick);
            cpu[rep] = (threads.getCurrentThreadCpuTime() - time) / (double) TICKS;
            alloc[rep] = (threads.getThreadAllocatedBytes(thread) - bytes) / (double) TICKS;
        }
        Arrays.sort(cpu);
        Arrays.sort(alloc);
        String line = String.format(
                "bursty capture per tick: cpu median %.0f ns (min %.0f, max %.0f), alloc median %.0f B (min %.0f,"
                        + " max %.0f), sent %d B%n",
                cpu[REPS / 2], cpu[0], cpu[REPS - 1], alloc[REPS / 2], alloc[0], alloc[REPS - 1], sink.bytes);
        Files.writeString(Path.of(out), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
