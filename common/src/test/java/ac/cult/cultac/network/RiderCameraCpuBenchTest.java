package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedCameraEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU of isUntrackedRider over a tracker of cosmetic riders and of isSelf over many cameras, each against the
 * per-call form it replaces, interleaved; runs only with CULT_RIDER_BENCH set.
 */
class RiderCameraCpuBenchTest {
    private static final int ENTITIES = 120;
    private static final int CAMERAS = 20_000;
    private static final int WARMUP = 20_000;
    private static final int BATCH = 50;
    private static final long REP_CPU_NANOS = 200_000_000L;
    private static final int REPS = 15;

    private interface Run {
        long run();
    }

    @Test
    void riderAndCameraLookups() throws Exception {
        String out = System.getenv("CULT_RIDER_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            CompensatedEntities entities = player.compensatedEntities;
            String[] keys = {"player", "armor_stand", "text_display", "zombie", "pig"};
            Random random = new Random(7);
            List<PacketEntity> all = new ArrayList<>();
            for (int i = 0; i < ENTITIES; i++) {
                int id = 1000 + random.nextInt(1_000_000);
                while (entities.getEntity(id) != null) id++;
                int type = EntityTypes.defaults().byKey(keys[i % keys.length]).id();
                entities.addEntity(id, type, new Vec3(random.nextInt(31), JavaTickFixture.FLOOR_Y, 0.5), 0, 0, 0);
                all.add(entities.getEntity(id));
            }
            // Name tags and model parts: a third of the entities ride a player or a part riding one.
            for (int i = 0; i < ENTITIES; i++) {
                if (i % 3 != 1) continue;
                all.get(i).mount(all.get(i - 1 - (i % 2 == 0 ? 1 : 0)));
            }
            PacketEntity[] tracked = all.toArray(PacketEntity[]::new);

            Field dequeField = CompensatedCameraEntity.class.getDeclaredField("entities");
            dequeField.setAccessible(true);
            CompensatedCameraEntity[] cameras = new CompensatedCameraEntity[CAMERAS];
            @SuppressWarnings("unchecked")
            ArrayDeque<PacketEntity>[] deques = new ArrayDeque[CAMERAS];
            for (int i = 0; i < CAMERAS; i++) {
                cameras[i] = new CompensatedCameraEntity(player);
                deques[i] = (ArrayDeque<PacketEntity>) dequeField.get(cameras[i]);
            }

            Run riders = () -> {
                long untracked = 0;
                for (PacketEntity entity : tracked) if (entities.isUntrackedRider(entity)) untracked++;
                return untracked;
            };
            Run ridersWalk = () -> {
                long untracked = 0;
                for (PacketEntity entity : tracked) if (untrackedRiderWalk(entities, entity)) untracked++;
                return untracked;
            };
            int[] next = new int[1];
            Run camera = () -> {
                long self = 0;
                for (int i = 0; i < 100; i++) {
                    int c = next[0] = (next[0] + 7919) % CAMERAS;
                    if (cameras[c].isSelf()) self++;
                }
                return self;
            };
            Run cameraDeque = () -> {
                long self = 0;
                for (int i = 0; i < 100; i++) {
                    int c = next[0] = (next[0] + 7919) % CAMERAS;
                    if (isSelfDeque(entities.getSelf(), deques[c])) self++;
                }
                return self;
            };
            assertEquals(ridersWalk.run(), riders.run());
            assertEquals(cameraDeque.run(), camera.run());

            String[] names = {
                "isUntrackedRider x" + ENTITIES,
                "root walk x" + ENTITIES,
                "isSelf x100",
                "deque isSelf x100"
            };
            Run[] runs = {riders, ridersWalk, camera, cameraDeque};
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

    // The chain-walk form of isUntrackedRider.
    private static boolean untrackedRiderWalk(CompensatedEntities entities, PacketEntity entity) {
        if (entity.getRiding() == null) return false;
        PacketEntity root = entity;
        for (int hops = 0; root.getRiding() != null; hops++) {
            if (hops > entities.entityMap.size()) return true;
            root = root.getRiding();
        }
        return root != entities.selfRidingRoot() && root != entities.serverVehicleRoot();
    }

    // The deque-loop form of isSelf.
    private static boolean isSelfDeque(PacketEntity self, ArrayDeque<PacketEntity> deque) {
        for (PacketEntity entity : deque) if (entity != self) return false;
        return true;
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
