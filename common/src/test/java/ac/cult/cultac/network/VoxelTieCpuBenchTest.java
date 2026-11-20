package ac.cult.cultac.network;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread-CPU microbenchmark of epsilon-deflated standing boxes on shape faces; CI skips it. */
@EnabledIfEnvironmentVariable(named = "CULT_COLLISION_BENCH", matches = "1")
class VoxelTieCpuBenchTest {
    private static final int QUERIES = 4000;
    private static final double[] FACES = {
        0, 1 / 16.0, 2 / 16.0, 3 / 16.0, 0.375, 0.5, 0.5625, 0.625, 0.8125, 14 / 16.0, 15 / 16.0, 1
    };

    @Test
    void measure() throws Exception {
        var report = new StringBuilder();
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            CollisionGatheringEquivalenceTest.CollisionWorlds.populate(player, 0x5eedL);
            Field version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            var queries = queries();
            for (ClientVersion client : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
                version.set(player, client);
                report.append(client).append(' ').append(run(player, queries));
            }
        }
        System.out.print(report);
        Path out = Path.of("build", "cpu-bench", "voxel-ties.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    private static String run(CultPlayer player, List<SimpleCollisionBox> queries) {
        int warmup = Integer.getInteger("bench.warmup", 8);
        int reps = Integer.getInteger("bench.reps", 15);
        int inner = Integer.getInteger("bench.inner", 40);
        var actor = new JavaCollisionState(0, false, false, false);
        var thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0;
        for (int i = 0; i < warmup; i++) sink += pass(player, queries, actor);
        long[] nanos = new long[reps];
        long[] bytes = new long[reps];
        for (int i = 0; i < reps; i++) {
            long allocated = thread.getCurrentThreadAllocatedBytes();
            long start = thread.getCurrentThreadCpuTime();
            for (int j = 0; j < inner; j++) sink += pass(player, queries, actor);
            nanos[i] = thread.getCurrentThreadCpuTime() - start;
            bytes[i] = thread.getCurrentThreadAllocatedBytes() - allocated;
        }
        Arrays.sort(bytes);
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long median = sorted[reps / 2];
        long[] deviations = new long[reps];
        for (int i = 0; i < reps; i++) deviations[i] = Math.abs(nanos[i] - median);
        Arrays.sort(deviations);
        return String.format(
                "ties median %.3f ms/pass MAD %.3f min %.3f max %.3f, alloc median %.1f KB/pass min %.1f max %.1f"
                        + " (%d queries x %d passes, sink %d)%n",
                median / 1e6 / inner,
                deviations[reps / 2] / 1e6 / inner,
                sorted[0] / 1e6 / inner,
                sorted[reps - 1] / 1e6 / inner,
                bytes[reps / 2] / 1e3 / inner,
                bytes[0] / 1e3 / inner,
                bytes[reps - 1] / 1e3 / inner,
                queries.size(),
                inner,
                sink);
    }

    /** Standing boxes whose feet or sides sit on a shape face, deflated by the collision epsilon. */
    private static List<SimpleCollisionBox> queries() {
        var random = new SplittableRandom(0x71E5L);
        var result = new ArrayList<SimpleCollisionBox>();
        for (int i = 0; i < QUERIES; i++) {
            double x = random.nextInt(-30, 30) + random.nextDouble(0.3, 0.7);
            double z = random.nextInt(-30, 30) + random.nextDouble(0.3, 0.7);
            double y = random.nextInt(44, 70) + FACES[random.nextInt(FACES.length)];
            if (random.nextBoolean()) x = Math.floor(x) + FACES[random.nextInt(FACES.length)] - 0.3;
            if (random.nextBoolean()) z = Math.floor(z) + FACES[random.nextInt(FACES.length)] + 0.3;
            result.add(new SimpleCollisionBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3, false)
                    .expand(-SimpleCollisionBox.COLLISION_EPSILON));
        }
        return result;
    }

    private static long pass(CultPlayer player, List<SimpleCollisionBox> queries, JavaCollisionState actor) {
        long sink = 0;
        var boxes = new ArrayList<SimpleCollisionBox>();
        for (SimpleCollisionBox query : queries) {
            if (!Collisions.isEmpty(player, query, query.minY)) sink++;
            boxes.clear();
            Collisions.getCollisionBoxes(player, query, boxes, false, query.minY, actor);
            sink += boxes.size();
        }
        return sink;
    }
}
