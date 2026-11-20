package ac.cult.cultac.network;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
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
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Thread-CPU microbenchmark of prediction-shaped collider gathering and clipping; CI skips it. */
@EnabledIfEnvironmentVariable(named = "CULT_COLLISION_BENCH", matches = "1")
class CollisionGatheringCpuBenchTest {
    private static final int QUERIES = 4000;

    @Test
    void measure() throws Exception {
        int warmup = Integer.getInteger("bench.warmup", 8);
        int reps = Integer.getInteger("bench.reps", 15);
        int inner = Integer.getInteger("bench.inner", 40);
        var report = new StringBuilder();
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            CollisionGatheringEquivalenceTest.CollisionWorlds.populate(player, 0x5eedL);
            Field version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            var queries = queries();
            var actor = new JavaCollisionState(0, false, false, false);
            var thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            String jfr = System.getenv("CULT_COLLISION_BENCH_JFR");
            Recording recording = null;
            if (jfr != null) {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.setDestination(Path.of(jfr));
                recording.start();
            }
            for (ClientVersion client : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
                version.set(player, client);
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
                report.append(String.format(
                        "%s median %.3f ms/pass MAD %.3f min %.3f max %.3f, alloc median %.1f KB/pass min %.1f max"
                                + " %.1f (%d queries x %d passes, sink %d)%n",
                        client,
                        median / 1e6 / inner,
                        deviations[reps / 2] / 1e6 / inner,
                        sorted[0] / 1e6 / inner,
                        sorted[reps - 1] / 1e6 / inner,
                        bytes[reps / 2] / 1e3 / inner,
                        bytes[0] / 1e3 / inner,
                        bytes[reps - 1] / 1e3 / inner,
                        QUERIES,
                        inner,
                        sink));
            }
            if (recording != null) {
                recording.stop();
                recording.close();
            }
        }
        System.out.print(report);
        Path out = Path.of("build", "cpu-bench", "collisions.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    private record Query(SimpleCollisionBox box, double dx, double dy, double dz) {}

    /** Player-sized boxes on the dense floor layers, swept by walking/falling-sized movements. */
    private static List<Query> queries() {
        var random = new SplittableRandom(0xBE7CL);
        var result = new ArrayList<Query>();
        for (int i = 0; i < QUERIES; i++) {
            double x = random.nextDouble(-30, 30);
            double z = random.nextDouble(-30, 30);
            double y = random.nextDouble(44, 70);
            var box = new SimpleCollisionBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3, false);
            result.add(new Query(
                    box,
                    random.nextDouble(-0.35, 0.35),
                    random.nextDouble(-0.6, 0.45),
                    random.nextDouble(-0.35, 0.35)));
        }
        return result;
    }

    private static long pass(CultPlayer player, List<Query> queries, JavaCollisionState actor) {
        long sink = 0;
        var boxes = new ArrayList<SimpleCollisionBox>();
        for (Query query : queries) {
            player.lastX = query.box.minX + 0.3;
            player.lastZ = query.box.minZ + 0.3;
            var swept = query.box.copy().expandToCoordinate(query.dx, query.dy, query.dz);
            boxes.clear();
            Collisions.getCollisionBoxes(player, swept, boxes, false, query.box.minY, actor);
            sink += boxes.size();
            if (!Collisions.isEmpty(player, query.box, query.box.minY)) sink++;
            Vec3 moved = Collisions.collide(player, query.box, query.dx, query.dy, query.dz);
            sink += Double.doubleToRawLongBits(moved.x + moved.y + moved.z) & 1;
        }
        return sink;
    }
}
