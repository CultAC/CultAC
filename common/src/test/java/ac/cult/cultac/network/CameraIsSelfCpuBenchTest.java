package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedCameraEntity;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU of isSelf on warm cameras (one camera, and one per player of a full proxy, each read eight times in a
 * row as a packet's checks do, with a fence between reads so none is hoisted) against the deque form it replaces,
 * interleaved; runs only with CULT_CAMERA_BENCH set.
 */
class CameraIsSelfCpuBenchTest {
    private static final int PLAYERS = 280;
    private static final int READS = 8;
    private static final int WARMUP = 50_000;
    private static final int BATCH = 50;
    private static final long REP_CPU_NANOS = 200_000_000L;
    private static final int REPS = 15;

    private interface Run {
        long run();
    }

    @Test
    void warmIsSelf() throws Exception {
        String out = System.getenv("CULT_CAMERA_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            Field dequeField = CompensatedCameraEntity.class.getDeclaredField("entities");
            dequeField.setAccessible(true);
            CompensatedCameraEntity[] cameras = new CompensatedCameraEntity[PLAYERS];
            @SuppressWarnings("unchecked")
            ArrayDeque<PacketEntity>[] deques = new ArrayDeque[PLAYERS];
            for (int i = 0; i < PLAYERS; i++) {
                cameras[i] = new CompensatedCameraEntity(player);
                deques[i] = (ArrayDeque<PacketEntity>) dequeField.get(cameras[i]);
            }

            Run one = () -> {
                long self = 0;
                for (int i = 0; i < 100; i++) {
                    VarHandle.acquireFence();
                    if (cameras[0].isSelf()) self++;
                }
                return self;
            };
            Run oneDeque = () -> {
                long self = 0;
                for (int i = 0; i < 100; i++) {
                    VarHandle.acquireFence();
                    if (isSelfDeque(player, deques[0])) self++;
                }
                return self;
            };
            Run proxy = () -> {
                long self = 0;
                for (CompensatedCameraEntity camera : cameras) {
                    for (int i = 0; i < READS; i++) {
                        VarHandle.acquireFence();
                        if (camera.isSelf()) self++;
                    }
                }
                return self;
            };
            Run proxyDeque = () -> {
                long self = 0;
                for (ArrayDeque<PacketEntity> deque : deques) {
                    for (int i = 0; i < READS; i++) {
                        VarHandle.acquireFence();
                        if (isSelfDeque(player, deque)) self++;
                    }
                }
                return self;
            };
            assertEquals(oneDeque.run(), one.run());
            assertEquals(proxyDeque.run(), proxy.run());

            String[] names = {
                "isSelf x100 one camera",
                "deque isSelf x100 one camera",
                "isSelf x" + READS + " x" + PLAYERS + " cameras",
                "deque isSelf x" + READS + " x" + PLAYERS + " cameras"
            };
            Run[] runs = {one, oneDeque, proxy, proxyDeque};
            long[] sink = new long[1];
            for (Run r : runs) for (int i = 0; i < WARMUP; i++) sink[0] += r.run();
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            double[][] samples = new double[runs.length][REPS];
            for (int rep = 0; rep < REPS; rep++) {
                for (int t = 0; t < runs.length; t++) {
                    long begin = bean.getCurrentThreadCpuTime();
                    long elapsed;
                    long count = 0;
                    do {
                        for (int i = 0; i < BATCH; i++) sink[0] += runs[t].run();
                        count += BATCH;
                        elapsed = bean.getCurrentThreadCpuTime() - begin;
                    } while (elapsed < REP_CPU_NANOS);
                    samples[t][rep] = (double) elapsed / count;
                }
            }
            StringBuilder report = new StringBuilder();
            for (int t = 0; t < runs.length; t++) {
                double[] sorted = samples[t].clone();
                Arrays.sort(sorted);
                report.append(String.format(
                        "%s: median %.1f ns, min %.1f, max %.1f%n",
                        names[t], sorted[REPS / 2], sorted[0], sorted[REPS - 1]));
            }
            report.append("sink ").append(sink[0] & 1).append('\n');
            System.out.print(report);
            write(Path.of(out), report.toString());
        }
    }

    // The single-possibility deque form of isSelf.
    private static boolean isSelfDeque(CultPlayer player, ArrayDeque<PacketEntity> deque) {
        PacketEntity self = player.compensatedEntities.getSelf();
        if (deque.size() == 1) return deque.peekFirst() == self;
        for (PacketEntity entity : deque) if (entity != self) return false;
        return true;
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
