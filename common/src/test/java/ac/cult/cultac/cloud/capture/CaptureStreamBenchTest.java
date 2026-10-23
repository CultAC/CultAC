package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
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
 * Thread CPU and allocated bytes of one player's capture per client tick: taps, cut, verdict and the frame written,
 * over the prod mix of about 72 records a tick, a quarter of them bundle delimiters. Runs only when
 * {@code CULTAC_CAPTURE_BENCH} names a file the medians are appended to.
 */
class CaptureStreamBenchTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
    private static final int TICK_END = 1;
    private static final int PONG = 2;
    private static final int MOVE = 3;
    private static final int PING = 4;
    private static final int BUNDLE = 5;
    private static final int ENTITY = 6;
    private static final int TICKS = 20_000;
    /** Passes over the ticks per rep, so a rep spans many of the thread clock's steps. */
    private static final int PASSES = 10;
    private static final int WARMUP_REPS = 5;
    private static final int REPS = 11;

    /** A sink that keeps nothing it is sent. */
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

    /** One tick's packets: kind, id, body and ping kind, in tap order. */
    private record Packet(int kind, int id, ByteBuf body, int pingKind) {}

    private static ByteBuf packet(int id, int bodyBytes, SplittableRandom random) {
        byte[] bytes = new byte[1 + bodyBytes];
        bytes[0] = (byte) id;
        for (int i = 1; i < bytes.length; i++) bytes[i] = (byte) random.nextInt(256);
        return Unpooled.wrappedBuffer(bytes);
    }

    private static ByteBuf probe(int id, int wireId) {
        return Unpooled.wrappedBuffer(ByteBuffer.allocate(5).put((byte) id).putInt(wireId).array());
    }

    private static List<List<Packet>> ticks(int count) {
        SplittableRandom random = new SplittableRandom(42);
        List<List<Packet>> ticks = new ArrayList<>();
        int wireId = 1;
        for (int t = 0; t < count; t++) {
            List<Packet> tick = new ArrayList<>();
            for (int bundle = 0; bundle < 9; bundle++) {
                tick.add(new Packet(IntakeWire.CLIENTBOUND, BUNDLE, packet(BUNDLE, 0, random), 0));
                for (int i = 0; i < 4; i++) {
                    tick.add(new Packet(
                            IntakeWire.CLIENTBOUND, ENTITY, packet(ENTITY, 8 + random.nextInt(40), random), 0));
                }
                tick.add(new Packet(IntakeWire.CLIENTBOUND, BUNDLE, packet(BUNDLE, 0, random), 0));
            }
            for (int i = 0; i < 12; i++) {
                tick.add(new Packet(
                        IntakeWire.CLIENTBOUND, ENTITY, packet(ENTITY, 8 + random.nextInt(60), random), 0));
            }
            tick.add(new Packet(IntakeWire.CLIENTBOUND, PING, probe(PING, wireId), IntakeWire.PING_FOREIGN));
            tick.add(new Packet(IntakeWire.CLIENTBOUND, PING, probe(PING, wireId + 1), IntakeWire.PING_FOREIGN));
            tick.add(new Packet(IntakeWire.SERVERBOUND, PONG, probe(PONG, wireId - 2), 0));
            tick.add(new Packet(IntakeWire.SERVERBOUND, PONG, probe(PONG, wireId - 1), 0));
            wireId += 2;
            tick.add(new Packet(IntakeWire.SERVERBOUND, MOVE, packet(MOVE, 25, random), 0));
            tick.add(new Packet(IntakeWire.SERVERBOUND, TICK_END, packet(TICK_END, 0, random), 0));
            ticks.add(tick);
        }
        return ticks;
    }

    private static CaptureStream joined(Discard sink) {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.pingPacket(PING);
        stream.pongPacket(PONG);
        stream.join(sink, IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        stream.capture(new int[] {TICK_END, PONG, MOVE}, new int[] {PING, BUNDLE, ENTITY}, new int[0]);
        return stream;
    }

    private static void run(CaptureStream stream, List<List<Packet>> ticks, long tickBase) {
        long nanos = tickBase * 50_000_000L;
        for (int t = 0; t < ticks.size(); t++) {
            long index = tickBase + t + 1;
            for (Packet packet : ticks.get(t)) {
                nanos += 600_000;
                ByteBuf body = packet.body();
                int at = body.readerIndex();
                if (stream.wants(packet.kind(), packet.id())) {
                    stream.tap(packet.kind(), packet.id(), body, nanos, -64, 384, SESSION, packet.pingKind());
                }
                body.readerIndex(at);
            }
            stream.cut(nanos);
            stream.verdict(index, index, 0, List.of(), null, List.of(), null);
        }
    }

    @Test
    void capturePerTick() throws IOException {
        String out = System.getenv("CULTAC_CAPTURE_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        List<List<Packet>> ticks = ticks(TICKS);
        Discard sink = new Discard();
        CaptureStream stream = joined(sink);
        long tickBase = 0;
        for (int i = 0; i < WARMUP_REPS; i++) {
            run(stream, ticks, tickBase);
            tickBase += TICKS;
        }
        double[] cpu = new double[REPS];
        double[] alloc = new double[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            long bytes = threads.getThreadAllocatedBytes(thread);
            long time = threads.getCurrentThreadCpuTime();
            for (int pass = 0; pass < PASSES; pass++) {
                run(stream, ticks, tickBase);
                tickBase += TICKS;
            }
            cpu[rep] = (threads.getCurrentThreadCpuTime() - time) / (double) (TICKS * PASSES);
            alloc[rep] = (threads.getThreadAllocatedBytes(thread) - bytes) / (double) (TICKS * PASSES);
        }
        Arrays.sort(cpu);
        Arrays.sort(alloc);
        String line = String.format(
                "capture per tick: cpu median %.0f ns (min %.0f, max %.0f), alloc median %.0f B (min %.0f, max %.0f),"
                        + " sent %d B%n",
                cpu[REPS / 2], cpu[0], cpu[REPS - 1], alloc[REPS / 2], alloc[0], alloc[REPS - 1], sink.bytes);
        Files.writeString(Path.of(out), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
