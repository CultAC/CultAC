package ac.cult.cultac.cloud.transport;

import com.sun.management.ThreadMXBean;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * CPU and allocation of the upload's compression path, a message every 50 ms against one a second, over captured
 * upload records: {@code CULTAC_CLOUD_BENCH} names a directory of {@code .rec} files, one per player, each a run of
 * length-prefixed records as the capture hands them to the socket, one player tick each. Sockets of
 * {@code CULTAC_CLOUD_BENCH_PLAYERS} players (8) take one tick of each player per 50 ms part. Writes
 * {@code build/cloud-bench/batched.txt}.
 */
@EnabledIfEnvironmentVariable(named = "CULTAC_CLOUD_BENCH", matches = ".+")
class BatchedUploadBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int TICKS_PER_SECOND = 20;

    private record Run(long compressCpu, long compressAlloc, long senderCpu, long senderAlloc, long messages,
            long compressed) {}

    @Test
    void bench() throws Exception {
        List<List<byte[]>> players = load(Path.of(System.getenv("CULTAC_CLOUD_BENCH")));
        int perSocket = Integer.parseInt(envOr("CULTAC_CLOUD_BENCH_PLAYERS", "8"));
        int reps = Integer.parseInt(envOr("CULTAC_CLOUD_BENCH_REPS", "15"));
        int[] modes = Arrays.stream(envOr("CULTAC_CLOUD_BENCH_PARTS", "1,5,10,20,100").split(","))
                .mapToInt(Integer::parseInt)
                .toArray();
        List<List<List<byte[]>>> sockets = sockets(players, perSocket);
        List<List<byte[]>> socketed = players.subList(0, sockets.size() * perSocket);
        long ticks = socketed.stream().mapToLong(List::size).sum();
        long raw = socketed.stream().flatMap(List::stream).mapToLong(record -> record.length).sum();
        double playerSeconds = (double) ticks / TICKS_PER_SECOND;

        ExecutorService pool = CompressionPipeline.newPool(1, "cloud-bench");
        try {
            long compressThread = pool.submit(() -> Thread.currentThread().threadId()).get();
            for (int warm = 0; warm < 3; warm++) for (int parts : modes) run(pool, compressThread, sockets, parts);
            Run[][] runs = new Run[modes.length][reps];
            for (int rep = 0; rep < reps; rep++) {
                for (int m = 0; m < modes.length; m++) runs[m][rep] = run(pool, compressThread, sockets, modes[m]);
            }
            Path out = Path.of("build", "cloud-bench");
            Files.createDirectories(out);
            try (PrintStream report = new PrintStream(
                    Files.newOutputStream(out.resolve("batched.txt")), true, StandardCharsets.UTF_8)) {
                String head = String.format(
                        Locale.ROOT,
                        "players=%d perSocket=%d sockets=%d ticks=%d playerSeconds=%.1f raw=%d reps=%d",
                        socketed.size(),
                        perSocket,
                        sockets.size(),
                        ticks,
                        playerSeconds,
                        raw,
                        reps);
                System.out.println(head);
                report.println(head);
                for (int m = 0; m < modes.length; m++) {
                    String line = line(modes[m], runs[m], playerSeconds);
                    System.out.println(line);
                    report.println(line);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String line(int parts, Run[] runs, double playerSeconds) {
        long[] compressCpu = Arrays.stream(runs).mapToLong(Run::compressCpu).sorted().toArray();
        long[] senderCpu = Arrays.stream(runs).mapToLong(Run::senderCpu).sorted().toArray();
        long[] total = Arrays.stream(runs)
                .mapToLong(run -> run.compressCpu() + run.senderCpu())
                .sorted()
                .toArray();
        long[] alloc = Arrays.stream(runs)
                .mapToLong(run -> run.compressAlloc() + run.senderAlloc())
                .sorted()
                .toArray();
        Run last = runs[runs.length - 1];
        int n = runs.length;
        return String.format(
                Locale.ROOT,
                "parts/message=%d (%d ms) messages=%d compressed=%d | cpu us/player-s: compress %.2f sender %.2f total"
                        + " %.2f (q1 %.2f q3 %.2f) | alloc B/player-s %.0f (q1 %.0f q3 %.0f) | messages/player-s %.3f"
                        + " compressed B/player-s %.0f",
                parts,
                parts * 1000 / TICKS_PER_SECOND,
                last.messages(),
                last.compressed(),
                compressCpu[n / 2] / 1e3 / playerSeconds,
                senderCpu[n / 2] / 1e3 / playerSeconds,
                total[n / 2] / 1e3 / playerSeconds,
                total[n / 4] / 1e3 / playerSeconds,
                total[3 * n / 4] / 1e3 / playerSeconds,
                alloc[n / 2] / playerSeconds,
                alloc[n / 4] / playerSeconds,
                alloc[3 * n / 4] / playerSeconds,
                last.messages() / playerSeconds,
                last.compressed() / playerSeconds);
    }

    /** Every socket through one pipeline on {@code pool}, {@code parts} 50 ms parts to a message. */
    private static Run run(ExecutorService pool, long compressThread, List<List<List<byte[]>>> sockets, int parts)
            throws Exception {
        long sender = Thread.currentThread().threadId();
        long compressCpu = THREADS.getThreadCpuTime(compressThread);
        long compressAlloc = THREADS.getThreadAllocatedBytes(compressThread);
        long senderCpu = THREADS.getThreadCpuTime(sender);
        long senderAlloc = THREADS.getThreadAllocatedBytes(sender);
        long messages = 0;
        long compressed = 0;
        for (List<List<byte[]>> socket : sockets) {
            QueuedBytes queued = new QueuedBytes(new AtomicLong());
            try (CompressionPipeline pipeline = new CompressionPipeline(pool, 1, queued)) {
                pipeline.stream();
                for (int i = 0; i < socket.size(); i++) {
                    List<byte[]> part = socket.get(i);
                    int bytes = 0;
                    for (byte[] record : part) bytes += record.length;
                    boolean end = (i + 1) % parts == 0 || i == socket.size() - 1;
                    queued.addAndGet(bytes);
                    pipeline.submit(part, bytes, end);
                    if (!end) continue;
                    byte[] message = pipeline.take();
                    messages++;
                    compressed += message.length;
                    pipeline.sent(message);
                }
            }
        }
        return new Run(
                THREADS.getThreadCpuTime(compressThread) - compressCpu,
                THREADS.getThreadAllocatedBytes(compressThread) - compressAlloc,
                THREADS.getThreadCpuTime(sender) - senderCpu,
                THREADS.getThreadAllocatedBytes(sender) - senderAlloc,
                messages,
                compressed);
    }

    /** Sockets of {@code perSocket} players each; each 50 ms part is one tick of every player still playing. */
    private static List<List<List<byte[]>>> sockets(List<List<byte[]>> players, int perSocket) {
        List<List<List<byte[]>>> sockets = new ArrayList<>();
        for (int first = 0; first + perSocket <= players.size(); first += perSocket) {
            List<List<byte[]>> group = players.subList(first, first + perSocket);
            int ticks = group.stream().mapToInt(List::size).max().orElse(0);
            List<List<byte[]>> socket = new ArrayList<>();
            for (int tick = 0; tick < ticks; tick++) {
                List<byte[]> part = new ArrayList<>();
                for (List<byte[]> player : group) if (tick < player.size()) part.add(player.get(tick));
                socket.add(part);
            }
            sockets.add(socket);
        }
        return sockets;
    }

    private static List<List<byte[]>> load(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(directory)) {
            files = listed.filter(path -> path.toString().endsWith(".rec")).sorted().toList();
        }
        List<List<byte[]>> players = new ArrayList<>();
        for (Path file : files) {
            List<byte[]> records = new ArrayList<>();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (EOFException end) {
                        break;
                    }
                    byte[] record = new byte[length];
                    in.readFully(record);
                    records.add(record);
                }
            }
            players.add(records);
        }
        return players;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
