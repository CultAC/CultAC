package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocation of the ticking-root collection and of a type-view rebuild, each against a test copy of the
 * form it replaces, interleaved in one JVM over entity mixes; runs only with CULT_BOOKKEEPING_BENCH set.
 */
class EntityBookkeepingCpuBenchTest {
    private static final int WARMUP = 20_000;
    private static final int BATCH = 100;
    private static final long REP_CPU_NANOS = 200_000_000L;
    private static final int REPS = 15;

    private interface Run {
        Object run() throws Throwable;
    }

    private record Mix(String name, String[] keys, int[] counts) {}

    private static final Mix[] MIXES = {
        new Mix(
                "lobby",
                new String[] {"player", "arrow", "armor_stand", "tnt_minecart", "text_display", "pig"},
                new int[] {26, 20, 6, 3, 1, 1}),
        new Mix("mob farm", new String[] {"player", "sheep", "arrow", "chicken", "cow", "oak_boat"}, new int[] {
            4, 80, 20, 60, 30, 2
        }),
    };

    @Test
    void rootsAndViews() throws Throwable {
        String out = System.getenv("CULT_BOOKKEEPING_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        StringBuilder report = new StringBuilder();
        for (Mix mix : MIXES) report.append(runMix(mix));
        System.out.print(report);
        write(Path.of(out), report.toString());
    }

    @SuppressWarnings("unchecked")
    private static String runMix(Mix mix) throws Throwable {
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            Random random = new Random(7);
            int id = 2000;
            for (int k = 0; k < mix.keys().length; k++) {
                int type = EntityTypes.defaults().byKey(mix.keys()[k]).id();
                for (int i = 0; i < mix.counts()[k]; i++) {
                    double x = random.nextInt(31) - 15 + 0.5;
                    double z = random.nextInt(31) - 15 + 0.5;
                    entities.addEntity(id++, type, new Vec3(x, JavaTickFixture.FLOOR_Y, z), 0, 0, 0);
                }
            }
            MethodHandle roots = MethodHandles.privateLookupIn(CompensatedEntities.class, MethodHandles.lookup())
                    .findVirtual(CompensatedEntities.class, "rootsInAddOrder", MethodType.methodType(List.class));
            Field addOrderField = CompensatedEntities.class.getDeclaredField("addOrder");
            addOrderField.setAccessible(true);
            List<PacketEntity> addOrder = (List<PacketEntity>) addOrderField.get(entities);
            Field passengersField = CompensatedEntities.class.getDeclaredField("trackedPassengers");
            passengersField.setAccessible(true);
            int touchedId = 2000;
            PacketEntity touched = entities.getEntity(touchedId);

            Run rootsRun = () -> (List<PacketEntity>) roots.invokeExact(entities);
            Run rootsCopy = () -> rootsInAddOrderCopy(entities, addOrder, passengersField.getInt(entities), true);
            Run rootsSkipCopy = () -> rootsInAddOrderCopy(entities, addOrder, passengersField.getInt(entities), false);
            Run views = () -> {
                entities.entityMap.put(touchedId, touched);
                return entities.pushOrCollideCandidates();
            };
            Run viewsCopy = () -> {
                entities.entityMap.put(touchedId, touched);
                return rebuildViewsDefault(entities);
            };
            List<PacketEntity> expected = (List<PacketEntity>) rootsCopy.run();
            assertNotNull(expected);
            assertEquals(expected, rootsRun.run());
            assertEquals(expected, rootsRun.run());
            assertEquals(expected, rootsSkipCopy.run());

            String[] names = {
                "roots", "roots lookup copy", "roots no-lookup copy", "view rebuild", "view rebuild default copy"
            };
            Run[] runs = {rootsRun, rootsCopy, rootsSkipCopy, views, viewsCopy};
            Object[] sink = new Object[1];
            for (Run r : runs) for (int i = 0; i < WARMUP; i++) sink[0] = r.run();
            ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            double[][] cpu = new double[runs.length][REPS];
            double[][] bytes = new double[runs.length][REPS];
            for (int rep = 0; rep < REPS; rep++) {
                for (int t = 0; t < runs.length; t++) {
                    long begin = bean.getCurrentThreadCpuTime();
                    long allocBegin = bean.getCurrentThreadAllocatedBytes();
                    long elapsed;
                    long count = 0;
                    do {
                        for (int i = 0; i < BATCH; i++) sink[0] = runs[t].run();
                        count += BATCH;
                        elapsed = bean.getCurrentThreadCpuTime() - begin;
                    } while (elapsed < REP_CPU_NANOS);
                    cpu[t][rep] = (double) elapsed / count;
                    bytes[t][rep] = (double) (bean.getCurrentThreadAllocatedBytes() - allocBegin) / count;
                }
            }
            StringBuilder report = new StringBuilder(String.format(
                    "== %s: %d entities, %d roots%n", mix.name(), entities.entityMap.size(), expected.size()));
            for (int t = 0; t < runs.length; t++) {
                double[] c = cpu[t].clone();
                double[] b = bytes[t].clone();
                Arrays.sort(c);
                Arrays.sort(b);
                report.append(String.format(
                        "%s: median %.1f ns, min %.1f, max %.1f, %.1f B%n",
                        names[t], c[REPS / 2], c[0], c[REPS - 1], b[REPS / 2]));
            }
            report.append("sink ").append(sink[0] == null ? 0 : 1).append('\n');
            return report.toString();
        }
    }

    // rootsInAddOrder, looking every add order entry up in entityMap when lookup is set.
    private static List<PacketEntity> rootsInAddOrderCopy(
            CompensatedEntities entities, List<PacketEntity> addOrder, int trackedPassengers, boolean lookup) {
        int size = addOrder.size();
        if (size != entities.entityMap.size()) return null;
        PacketEntity self = entities.getSelf();
        long selfOrder = self.getClientTickOrder();
        boolean selfPending = self.getRiding() == null && entities.isTicking(self);
        List<PacketEntity> roots = new ArrayList<>(Math.max(1, size + 1 - trackedPassengers));
        long previous = 0L;
        for (int i = 0; i < size; i++) {
            PacketEntity entity = addOrder.get(i);
            long order = entity.getClientTickOrder();
            if (i > 0 && order <= previous) return null;
            if (lookup && entities.entityMap.get(entity.getEntityId()) != entity) return null;
            previous = order;
            if (selfPending && selfOrder < order) {
                roots.add(self);
                selfPending = false;
            }
            if (entity.getRiding() == null && entities.isTicking(entity)) roots.add(entity);
        }
        if (selfPending) roots.add(self);
        return roots;
    }

    // The four type views built into default-capacity lists.
    private static List<PacketEntity> rebuildViewsDefault(CompensatedEntities entities) {
        List<PacketEntity> pushOrCollide = new ArrayList<>();
        List<PacketEntity> boatList = new ArrayList<>();
        List<PacketEntity> pose = new ArrayList<>();
        List<PacketEntity> unknown = new ArrayList<>();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity.mayPushOrCollide()) pushOrCollide.add(entity);
            boolean boat = entity.isBoat();
            boolean shulker = entity.type == EntityTypeIds.SHULKER;
            if (boat) boatList.add(entity);
            if (boat || entity.isMinecart() || shulker || entity.isHappyGhast()) pose.add(entity);
            if (boat || shulker || entity instanceof PacketEntityHappyGhast) unknown.add(entity);
        }
        return boatList.size() + pose.size() + unknown.size() < 0 ? null : pushOrCollide;
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
