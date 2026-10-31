package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocated bytes of the capture paths, each read the plain way and the reusing way in alternating reps
 * on one thread: frames written into arrays of their own or arrays given back once sent, and drawn packets stripped
 * afresh or with one stream's reader. Runs only when {@code CULTAC_CLOUD_ALLOC_BENCH} names a file the medians are
 * appended to.
 */
class CloudAllocBenchTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
    private static final int TICK_END = 1;
    private static final int MOVE = 3;
    private static final int ENTITY = 6;
    private static final int TICKS = 40_000;
    private static final int WARMUP_REPS = 4;
    private static final int REPS = 11;

    /** What the measured paths return, kept so none of them is optimized away. */
    private static long consumed;

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** A sink that keeps nothing it is sent; a pooling one reuses each frame's array once it is sent. */
    private static final class Discard implements CaptureStream.Sink {
        final boolean pooling;
        long bytes;
        byte[] spare;

        Discard(boolean pooling) {
            this.pooling = pooling;
        }

        @Override
        public boolean send(byte[] record) {
            bytes += record.length;
            return true;
        }

        @Override
        public byte[] frame(int length) {
            if (!pooling) return new byte[length];
            byte[] array = spare != null && spare.length >= length ? spare : new byte[2 * length];
            spare = null;
            return array;
        }

        @Override
        public boolean send(byte[] record, int length, long resolvedExtra) {
            if (!pooling) return send(record, resolvedExtra);
            bytes += length;
            spare = record;
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

    private static CaptureStream joined(Discard sink) {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.join(sink, IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        stream.capture(new int[] {TICK_END, MOVE}, new int[] {ENTITY}, new int[0]);
        return stream;
    }

    /** Medians and spreads, per unit, of {@code plain} and {@code reusing} run in alternating reps. */
    private static String compare(String what, long units, Runnable plain, Runnable reusing) {
        for (int i = 0; i < WARMUP_REPS; i++) {
            plain.run();
            reusing.run();
        }
        double[][] cpu = new double[2][REPS];
        double[][] alloc = new double[2][REPS];
        for (int rep = 0; rep < REPS; rep++) {
            for (int side = 0; side < 2; side++) {
                Runnable run = (rep + side) % 2 == 0 ? plain : reusing;
                int at = run == plain ? 0 : 1;
                long bytes = allocated();
                long time = THREADS.getCurrentThreadCpuTime();
                run.run();
                cpu[at][rep] = (THREADS.getCurrentThreadCpuTime() - time) / (double) units;
                alloc[at][rep] = (allocated() - bytes) / (double) units;
            }
        }
        StringBuilder line = new StringBuilder(what);
        String[] names = {"plain", "reusing"};
        for (int at = 0; at < 2; at++) {
            Arrays.sort(cpu[at]);
            Arrays.sort(alloc[at]);
            line.append(String.format(
                    " | %s: alloc median %.1f B (min %.1f, max %.1f), cpu median %.0f ns (min %.0f, max %.0f)",
                    names[at],
                    alloc[at][REPS / 2],
                    alloc[at][0],
                    alloc[at][REPS - 1],
                    cpu[at][REPS / 2],
                    cpu[at][0],
                    cpu[at][REPS - 1]));
        }
        return line.append(System.lineSeparator()).toString();
    }

    private static long allocated() {
        return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    @Test
    void cloudPaths() throws IOException {
        String out = System.getenv("CULTAC_CLOUD_ALLOC_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        Files.writeString(
                Path.of(out), frames() + strips() + teams(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String frames() {
        SplittableRandom random = new SplittableRandom(42);
        List<ByteBuf> packets = new ArrayList<>();
        for (int i = 0; i < 64; i++) packets.add(packet(ENTITY, 8 + random.nextInt(120), random));
        ByteBuf move = packet(MOVE, 25, random);
        ByteBuf end = packet(TICK_END, 0, random);
        CaptureStream plain = joined(new Discard(false));
        CaptureStream reusing = joined(new Discard(true));
        long[] nanos = new long[1];
        long[] ticks = new long[2];
        LongSupplier next = () -> nanos[0] += 600_000;
        return compare(
                "frame per tick",
                TICKS,
                () -> ticks[0] = run(plain, packets, move, end, ticks[0], next),
                () -> ticks[1] = run(reusing, packets, move, end, ticks[1], next));
    }

    private static long run(
            CaptureStream stream, List<ByteBuf> packets, ByteBuf move, ByteBuf end, long tick, LongSupplier nanos) {
        for (int t = 0; t < TICKS; t++) {
            tick++;
            for (int i = 0; i < packets.size(); i++) tap(stream, IntakeWire.CLIENTBOUND, ENTITY, packets.get(i), nanos);
            tap(stream, IntakeWire.SERVERBOUND, MOVE, move, nanos);
            tap(stream, IntakeWire.SERVERBOUND, TICK_END, end, nanos);
            long at = nanos.getAsLong();
            stream.cut(at);
            stream.verdict(tick, tick, 0, List.of(), null, List.of(), null);
        }
        return tick;
    }

    private static void tap(CaptureStream stream, int kind, int id, ByteBuf body, LongSupplier nanos) {
        int at = body.readerIndex();
        stream.tap(kind, id, body, nanos.getAsLong(), -64, 384, SESSION, 0);
        body.readerIndex(at);
    }

    private record Drawn(boolean clientbound, int id, byte[] packet) {}

    private static String strips() throws IOException {
        int protocol = 774;
        PlayerText text = PlayerText.of(protocol);
        List<Drawn> drawn = new ArrayList<>();
        try (InputStream resource =
                        CloudAllocBenchTest.class.getResourceAsStream("player-text/" + protocol + ".bin.gz");
                DataInputStream in =
                        new DataInputStream(new ByteArrayInputStream(new GZIPInputStream(resource).readAllBytes()))) {
            while (in.available() > 0) {
                boolean clientbound = in.readUnsignedByte() == 1;
                int id = varInt(in);
                byte[] packet = in.readNBytes(varInt(in));
                if (in.readUnsignedByte() == 2) in.skipNBytes(varInt(in));
                drawn.add(new Drawn(clientbound, id, packet));
            }
        }
        PlayerText.TeamNames teams = new PlayerText.TeamNames(new byte[32]);
        PlayerText.Reader reader = new PlayerText.Reader();
        int passes = 1500;
        StringBuilder lines = new StringBuilder();
        for (int limit : new int[] {PlayerText.Reader.MAX_KEPT_LENGTH, Integer.MAX_VALUE}) {
            List<Drawn> cases =
                    drawn.stream().filter(c -> c.packet().length <= limit).toList();
            lines.append(compare(
                    "strip per packet, " + cases.size() + " packets up to " + limit + " B",
                    (long) cases.size() * passes,
                    () -> strip(text, cases, teams, null, passes),
                    () -> strip(text, cases, teams, reader, passes)));
        }
        return lines.toString();
    }

    private static void strip(
            PlayerText text, List<Drawn> cases, PlayerText.TeamNames teams, PlayerText.Reader reader, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < cases.size(); i++) {
                Drawn c = cases.get(i);
                byte[] kept = c.clientbound()
                        ? text.clientbound(c.id(), c.packet(), true, teams, reader)
                        : text.serverbound(c.id(), c.packet(), true, reader);
                if (kept != null) consumed += kept.length;
            }
        }
    }

    private static String teams() {
        PlayerText.TeamNames teams = new PlayerText.TeamNames(new byte[32]);
        String[] names = new String[64];
        for (int i = 0; i < names.length; i++) names[i] = "team-" + i;
        int passes = 20_000;
        return compare(
                "team name",
                (long) names.length * passes,
                () -> {
                    for (int pass = 0; pass < passes; pass++) {
                        for (String name : names) {
                            ByteBuf out = Unpooled.buffer();
                            try {
                                Wire.writeString(out, teams.of(name), Wire.MAX_STRING_LENGTH);
                                consumed += ByteBufUtil.getBytes(out).length;
                            } finally {
                                out.release();
                            }
                        }
                    }
                },
                () -> {
                    for (int pass = 0; pass < passes; pass++) {
                        for (String name : names) consumed += teams.encoded(name).length;
                    }
                });
    }

    private static int varInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
        }
    }
}
