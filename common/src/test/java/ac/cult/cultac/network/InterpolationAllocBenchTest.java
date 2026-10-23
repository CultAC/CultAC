package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Allocated bytes and thread CPU per movement cycle of tracked entities (each moves once, the second transaction
 * arrives, three client ticks run); runs only with CULT_INTERP_BENCH set to an output file.
 */
class InterpolationAllocBenchTest {
    private static final int ENTITIES = 40;
    private static final int WARMUP_CYCLES = 20_000;
    private static final int BATCH = 50;
    private static final long REP_CPU_NANOS = 300_000_000L;
    private static final int REPS = 15;

    private interface Cycle {
        void run(int n);
    }

    @Test
    void movementCycles() throws Exception {
        String out = System.getenv("CULT_INTERP_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        String[] names = {"legacy move", "legacy bundle", "legacy sync", "1.8 move", "stepped move", "stepped bundle"};
        InterpolationTraceTest.Workload[] workloads = {
            new InterpolationTraceTest.Workload(ClientVersion.V_1_21_4, 1L, ENTITIES),
            new InterpolationTraceTest.Workload(ClientVersion.V_1_21_4, 2L, ENTITIES),
            new InterpolationTraceTest.Workload(ClientVersion.V_1_21_4, 3L, ENTITIES),
            new InterpolationTraceTest.Workload(ClientVersion.V_1_8, 4L, ENTITIES),
            new InterpolationTraceTest.Workload(ClientVersion.V_26_3, 5L, ENTITIES),
            new InterpolationTraceTest.Workload(ClientVersion.V_26_3, 6L, ENTITIES)
        };
        Cycle[] cycles = new Cycle[names.length];
        for (int c = 0; c < cycles.length; c++) {
            InterpolationTraceTest.Workload w = workloads[c];
            int kind = c;
            cycles[c] = n -> {
                double d = (n & 1) == 0 ? 0.25 : -0.25;
                float yaw = (n & 2) == 0 ? 10.0F : -10.0F;
                for (PacketEntity entity : w.entities) {
                    switch (kind) {
                        case 0, 3 -> entity.onFirstTransaction(true, true, d, 0, d, yaw, 0.0F, w.player);
                        case 1 -> entity.onBundleFirstTransaction(true, true, d, 0, d, yaw, 0.0F, w.player);
                        case 2 ->
                            entity.onBundledPositionSyncTransaction(
                                    entity.desyncClientPos.x + d,
                                    entity.desyncClientPos.y,
                                    entity.desyncClientPos.z + d,
                                    yaw,
                                    0.0F,
                                    w.player);
                        default -> {
                            Vec3 base = entity.desyncClientPos;
                            entity.onPositionPath(
                                    EntityPositionPath.linear(new Vec3(base.x + d, base.y, base.z + d)),
                                    true,
                                    yaw,
                                    0.0F,
                                    w.player,
                                    kind == 5);
                        }
                    }
                }
                w.tick(true);
                for (PacketEntity entity : w.entities) entity.onSecondTransaction();
                w.tick(true);
                w.tick(true);
            };
        }
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int c = 0; c < cycles.length; c++) {
            for (int i = 0; i < WARMUP_CYCLES; i++) cycles[c].run(i);
        }
        double[][] cpu = new double[cycles.length][REPS];
        double[][] bytes = new double[cycles.length][REPS];
        int[] counters = new int[cycles.length];
        for (int rep = 0; rep < REPS; rep++) {
            for (int c = 0; c < cycles.length; c++) {
                long beginBytes = bean.getThreadAllocatedBytes(thread);
                long begin = bean.getCurrentThreadCpuTime();
                long elapsed;
                long count = 0;
                do {
                    for (int i = 0; i < BATCH; i++) cycles[c].run(counters[c]++);
                    count += BATCH;
                    elapsed = bean.getCurrentThreadCpuTime() - begin;
                } while (elapsed < REP_CPU_NANOS);
                bytes[c][rep] = (double) (bean.getThreadAllocatedBytes(thread) - beginBytes) / count;
                cpu[c][rep] = (double) elapsed / count;
            }
        }
        StringBuilder report = new StringBuilder();
        for (int c = 0; c < cycles.length; c++) {
            double[] sortedCpu = cpu[c].clone();
            double[] sortedBytes = bytes[c].clone();
            Arrays.sort(sortedCpu);
            Arrays.sort(sortedBytes);
            report.append(String.format(
                    "%s x%d: %.0f B/cycle (min %.0f, max %.0f), median %.0f ns/cycle (min %.0f, max %.0f)%n",
                    names[c],
                    ENTITIES,
                    sortedBytes[REPS / 2],
                    sortedBytes[0],
                    sortedBytes[REPS - 1],
                    sortedCpu[REPS / 2],
                    sortedCpu[0],
                    sortedCpu[REPS - 1]));
        }
        for (InterpolationTraceTest.Workload w : workloads) w.close();
        System.out.print(report);
        write(Path.of(out), report.toString());
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
