package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * How often a socket's frames are written into arrays handed back by earlier writes: players on a few threads each
 * send a frame every tick to one socket, whose sender and compression threads give the arrays back as they write them.
 * Each player keeps a typical frame size, and each frame varies around it with an occasional large one. Reports the
 * bytes each frame's array allocates on the sending thread, writing into fresh exact arrays and into handed-out ones.
 * Runs only when {@code CULTAC_FRAME_POOL_BENCH} names a file the results are appended to.
 */
class FramePoolBenchTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static final int LOOPS = 4;
    private static final long TICK_MILLIS = 50;
    private static final long WARMUP_MILLIS = 6_000;
    private static final long MEASURE_MILLIS = 20_000;
    private static final byte[] PAYLOAD = new byte[64 * 1024];

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** One sending thread's counts; written only on that thread while {@code measuring}. */
    private static final class Counts {
        long frames;
        long misses;
        long allocated;
        long recordBytes;
        long arrayBytes;
        long refused;
    }

    @Test
    void framePool() throws Exception {
        String out = System.getenv("CULTAC_FRAME_POOL_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        StringBuilder lines = new StringBuilder();
        for (int players : new int[] {50, 150, 300}) {
            for (boolean pooling : new boolean[] {false, true}) lines.append(run(players, pooling));
        }
        Files.writeString(Path.of(out), lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String run(int players, boolean pooling) throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.unread = true;
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            SplittableRandom sizes = new SplittableRandom(players);
            double[] typical = new double[players];
            for (int p = 0; p < players; p++) typical[p] = Math.exp(Math.log(200) + sizes.nextDouble() * Math.log(15));
            long[] seqs = new long[players];
            Counts[] counts = new Counts[LOOPS];
            boolean[] measuring = new boolean[1];
            ScheduledExecutorService loops = Executors.newScheduledThreadPool(LOOPS);
            for (int l = 0; l < LOOPS; l++) {
                int loop = l;
                Counts mine = counts[l] = new Counts();
                SplittableRandom random = new SplittableRandom(31L * players + l);
                loops.scheduleAtFixedRate(
                        () -> {
                            boolean measured;
                            synchronized (measuring) {
                                measured = measuring[0];
                            }
                            for (int p = loop; p < players; p += LOOPS) {
                                double size = typical[p] * Math.exp((random.nextDouble() - 0.5) * Math.log(8.0 / 3));
                                if (random.nextInt(40) == 0) size *= 8;
                                IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
                                builder.add(
                                        IntakeWire.CLIENTBOUND,
                                        1,
                                        PAYLOAD,
                                        (int) Math.min(size, PAYLOAD.length - 64),
                                        1,
                                        0,
                                        0);
                                long seq = seqs[p]++;
                                int length = builder.length(seq);
                                long before = THREADS.getCurrentThreadAllocatedBytes();
                                byte[] array = pooling ? connection.frame(length) : new byte[length];
                                long allocated = THREADS.getCurrentThreadAllocatedBytes() - before;
                                builder.build(PLAYER, seq, array);
                                boolean sent = pooling
                                        ? connection.send(generation.get(), array, length, 0)
                                        : connection.send(generation.get(), array, 0);
                                if (!measured) continue;
                                if (!sent) mine.refused++;
                                mine.frames++;
                                if (allocated > 0) mine.misses++;
                                mine.allocated += allocated;
                                mine.recordBytes += length;
                                mine.arrayBytes += array.length;
                            }
                        },
                        0,
                        TICK_MILLIS,
                        TimeUnit.MILLISECONDS);
            }
            Thread.sleep(WARMUP_MILLIS);
            synchronized (measuring) {
                measuring[0] = true;
            }
            Thread.sleep(MEASURE_MILLIS);
            loops.shutdown();
            loops.awaitTermination(5, TimeUnit.SECONDS);
            Counts all = new Counts();
            for (Counts c : counts) {
                all.frames += c.frames;
                all.misses += c.misses;
                all.allocated += c.allocated;
                all.recordBytes += c.recordBytes;
                all.arrayBytes += c.arrayBytes;
                all.refused += c.refused;
            }
            return String.format(
                    "players %d %s: frames %d, hit rate %.1f %%, array alloc %.1f B/frame, record %.1f B/frame,"
                            + " array/record %.3f, refused %d%n",
                    players,
                    pooling ? "pooled" : "exact",
                    all.frames,
                    100.0 * (all.frames - all.misses) / all.frames,
                    all.allocated / (double) all.frames,
                    all.recordBytes / (double) all.frames,
                    all.arrayBytes / (double) all.recordBytes,
                    all.refused);
        }
    }

    private static CloudConnection connect(Receiver receiver, AtomicLong generation) throws Exception {
        receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
        CloudConnection connection = receiver.connections.forServer("proxy.a");
        assertNotNull(connection);
        connection.register(PLAYER, new CloudConnection.Stream() {
            public void connected(long connected) {
                generation.set(connected);
            }

            public void disconnected(long ended) {}

            public void reply(long replied, IntakeWire.Reply reply) {}
        });
        receiver.allowConnect("\"protocol\":5");
        await(() -> generation.get() > 0, "connected");
        return connection;
    }
}
