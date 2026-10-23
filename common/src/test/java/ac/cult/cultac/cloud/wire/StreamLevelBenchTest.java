package ac.cult.cultac.cloud.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.sun.management.ThreadMXBean;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU, heap allocation and compressed bytes of the intake stream at zstd levels 3 and 1, over real intake
 * records: each file in {@code CULTAC_ZSTD_BENCH_INPUT} is one player's records in send order, each a 4-byte big-endian
 * length and an {@code Intake}. A quiet socket sends one player's record a message; a busy one sends a record of every
 * player a message. Each level writes through {@link Writer}, which {@link IntakeWire.StreamWriter} at its own level
 * must match byte for byte. Runs only when {@code CULTAC_ZSTD_BENCH} names a file the medians are appended to.
 */
class StreamLevelBenchTest {
    private static final int[] LEVELS = {3, 1};
    private static final int WARMUP_REPS = 2;
    private static final int REPS = 7;

    /** The socket's writer as {@link IntakeWire.StreamWriter} builds it, at {@code level}, without its buffer shrinking. */
    private static final class Writer implements AutoCloseable {
        private final ZstdCompressCtx stream;
        private ByteBuffer input = ByteBuffer.allocateDirect(64 * 1024);
        private ByteBuffer output = ByteBuffer.allocateDirect(64 * 1024);
        private byte[] buffer = new byte[64 * 1024];

        Writer(int level) {
            stream = new ZstdCompressCtx()
                    .setLevel(level)
                    .setWindowLog(IntakeWire.StreamWriter.WINDOW_LOG)
                    .setChecksum(false)
                    .setContentSize(false);
        }

        ByteBuffer write(List<byte[]> records, int bytes) {
            if (input.capacity() < bytes) input = ByteBuffer.allocateDirect(bytes);
            input.clear();
            for (byte[] record : records) input.put(record);
            input.flip();
            int bound = (int) Zstd.compressBound(bytes);
            if (output.capacity() < bound) output = ByteBuffer.allocateDirect(bound);
            output.clear();
            while (!stream.compressDirectByteBufferStream(output, input, EndDirective.FLUSH)) {
                if (!output.hasRemaining())
                    output = ByteBuffer.allocateDirect(output.capacity() * 2).put(output.flip());
            }
            output.flip();
            int length = 1 + output.remaining();
            if (buffer.length < length) buffer = new byte[Math.max(length, buffer.length * 2)];
            buffer[0] = IntakeWire.COMPRESSED;
            output.get(buffer, 1, length - 1);
            return ByteBuffer.wrap(buffer, 0, length);
        }

        @Override
        public void close() {
            stream.close();
        }
    }

    /** One message: its records and their bytes. */
    private record Message(List<byte[]> records, int bytes) {}

    private static List<byte[]> read(Path file) throws IOException {
        List<byte[]> records = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(file);
                DataInputStream in = new DataInputStream(raw)) {
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException end) {
                    return records;
                }
                byte[] record = new byte[length];
                in.readFully(record);
                records.add(record);
            }
        }
    }

    /** Each player's records, once each: a player replayed twice is the same records twice. */
    private static List<List<byte[]>> players(Path dir) throws IOException {
        List<List<byte[]>> players = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.sorted().toList();
        }
        for (Path file : files) {
            List<byte[]> records = read(file);
            int hash = 1;
            for (byte[] record : records) hash = 31 * hash + Arrays.hashCode(record);
            if (!records.isEmpty() && seen.add(hash)) players.add(records);
        }
        return players;
    }

    private static Message message(List<byte[]> records) {
        int bytes = 0;
        for (byte[] record : records) bytes += record.length;
        return new Message(records, bytes);
    }

    /** Sockets of one player each: a message per record. */
    private static List<List<Message>> quiet(List<List<byte[]>> players) {
        List<List<Message>> sockets = new ArrayList<>();
        for (List<byte[]> records : players) {
            List<Message> messages = new ArrayList<>();
            for (byte[] record : records) messages.add(message(List.of(record)));
            sockets.add(messages);
        }
        return sockets;
    }

    /** One socket of every player: the n-th message holds each player's n-th record. */
    private static List<List<Message>> busy(List<List<byte[]>> players) {
        List<Message> messages = new ArrayList<>();
        for (int n = 0; ; n++) {
            List<byte[]> records = new ArrayList<>();
            for (List<byte[]> player : players) if (n < player.size()) records.add(player.get(n));
            if (records.isEmpty()) return List.of(messages);
            messages.add(message(records));
        }
    }

    /** Compressed bytes of every socket's messages at {@code level}, a fresh writer a socket. */
    private static long compress(List<List<Message>> sockets, int level) {
        long out = 0;
        for (List<Message> socket : sockets) {
            try (Writer writer = new Writer(level)) {
                for (Message message : socket) out += writer.write(message.records(), message.bytes()).remaining();
            }
        }
        return out;
    }

    private static void matchesTheStreamWriter(List<List<Message>> sockets) {
        for (List<Message> socket : sockets) {
            try (Writer writer = new Writer(IntakeWire.StreamWriter.LEVEL);
                    IntakeWire.StreamWriter real = new IntakeWire.StreamWriter()) {
                for (Message message : socket) {
                    assertArrayEquals(
                            bytes(real.write(message.records(), message.bytes(), true)),
                            bytes(writer.write(message.records(), message.bytes())));
                }
            }
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    private static String run(String name, List<List<Message>> sockets) {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long in = 0;
        int count = 0;
        for (List<Message> socket : sockets) {
            for (Message message : socket) in += message.bytes();
            count += socket.size();
        }
        double mb = in / 1_000_000.0;
        double[][] cpu = new double[LEVELS.length][REPS];
        double[][] alloc = new double[LEVELS.length][REPS];
        long[] out = new long[LEVELS.length];
        for (int rep = -WARMUP_REPS; rep < REPS; rep++) {
            for (int l = 0; l < LEVELS.length; l++) {
                long bytes = threads.getThreadAllocatedBytes(thread);
                long time = threads.getCurrentThreadCpuTime();
                out[l] = compress(sockets, LEVELS[l]);
                if (rep < 0) continue;
                cpu[l][rep] = (threads.getCurrentThreadCpuTime() - time) / 1e6 / mb;
                alloc[l][rep] = (threads.getThreadAllocatedBytes(thread) - bytes) / mb;
            }
        }
        StringBuilder line = new StringBuilder(String.format(
                "%s: %d sockets, %d messages, %.1f MB in%n", name, sockets.size(), count, mb));
        for (int l = 0; l < LEVELS.length; l++) {
            Arrays.sort(cpu[l]);
            Arrays.sort(alloc[l]);
            line.append(String.format(
                    "  level %d: cpu median %.2f ms/MB (min %.2f, max %.2f), out %d B (ratio %.3f),"
                            + " heap alloc median %.0f B/MB (min %.0f, max %.0f)%n",
                    LEVELS[l],
                    cpu[l][REPS / 2],
                    cpu[l][0],
                    cpu[l][REPS - 1],
                    out[l],
                    out[l] / (double) in,
                    alloc[l][REPS / 2],
                    alloc[l][0],
                    alloc[l][REPS - 1]));
        }
        return line.toString();
    }

    @Test
    void levels() throws IOException {
        String report = System.getenv("CULTAC_ZSTD_BENCH");
        String input = System.getenv("CULTAC_ZSTD_BENCH_INPUT");
        assumeTrue(report != null && !report.isEmpty() && input != null && !input.isEmpty());
        List<List<byte[]>> players = players(Path.of(input));
        List<List<Message>> quiet = quiet(players);
        List<List<Message>> busy = busy(players);
        matchesTheStreamWriter(quiet);
        matchesTheStreamWriter(busy);
        String lines = "StreamWriter.LEVEL " + IntakeWire.StreamWriter.LEVEL + " matches the bench's writer; "
                + players.size() + " players" + System.lineSeparator() + run("quiet", quiet) + run("busy", busy);
        Files.writeString(Path.of(report), lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
