package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UnknownCollisionProvider;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocation of the prediction entity scans over entity mixes measured in mob-dense replays; runs only
 * with CULT_VIEW_BENCH set.
 */
class EntityViewScanCpuBenchTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int WARMUP_TICKS = 20_000;
    private static final int BATCH = 100;
    private static final long REP_CPU_NANOS = 200_000_000L;
    private static final int REPS = 11;

    private interface Tick {
        void run() throws Exception;
    }

    private record Mount(String rider, String vehicle, int count) {}

    private record Mix(
            String name, String[] keys, int[] counts, int unknownPerTick, Mount[] mounts, String selfVehicle) {}

    private static final Mix[] MIXES = {
        new Mix(
                "lobby",
                new String[] {"player", "arrow", "armor_stand", "tnt_minecart", "text_display", "pig"},
                new int[] {26, 20, 6, 3, 1, 1},
                6,
                new Mount[0],
                null),
        new Mix(
                "mob farm",
                new String[] {"player", "sheep", "arrow", "chicken", "cow"},
                new int[] {1, 8, 3, 2, 1},
                6,
                new Mount[0],
                null),
        new Mix(
                "arrows",
                new String[] {"arrow", "ender_pearl", "falling_block"},
                new int[] {10, 1, 1},
                9,
                new Mount[0],
                null),
        new Mix(
                "riders",
                new String[] {"player", "horse", "armor_stand", "zombie", "chicken", "arrow"},
                new int[] {12, 6, 6, 4, 4, 10},
                6,
                new Mount[] {
                    new Mount("player", "horse", 6),
                    new Mount("player", "armor_stand", 6),
                    new Mount("zombie", "chicken", 4)
                },
                null),
        new Mix(
                "in boat",
                new String[] {"player", "horse", "zombie", "chicken", "oak_boat", "arrow"},
                new int[] {12, 6, 4, 4, 2, 10},
                6,
                new Mount[] {new Mount("player", "horse", 6), new Mount("zombie", "chicken", 4)},
                "oak_boat"),
    };

    @Test
    void entityViewScans() throws Exception {
        String out = System.getenv("CULT_VIEW_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        StringBuilder report = new StringBuilder();
        for (Mix mix : MIXES) report.append(runMix(mix));
        System.out.print(report);
        write(Path.of(out), report.toString());
    }

    private static String runMix(Mix mix) throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            f.tick(start, true);
            PredictionResult prediction = f.tick(start, true);
            SimulationContext context = prediction.getSimulationContext();
            CompensatedEntities entities = player.compensatedEntities;
            Random random = new Random(7);
            int id = 2000;
            int churnType = EntityTypes.defaults().byKey("arrow").id();
            Map<String, List<PacketEntity>> byKey = new HashMap<>();
            for (int k = 0; k < mix.keys().length; k++) {
                int type = EntityTypes.defaults().byKey(mix.keys()[k]).id();
                List<PacketEntity> ofKey = byKey.computeIfAbsent(mix.keys()[k], key -> new ArrayList<>());
                for (int i = 0; i < mix.counts()[k]; i++) {
                    double x = random.nextInt(31) - 15 + 0.5;
                    double z = random.nextInt(31) - 15 + 0.5;
                    entities.addEntity(id, type, new Vec3(x, JavaTickFixture.FLOOR_Y, z), 0, 0, 0);
                    ofKey.add(entities.getEntity(id++));
                }
            }
            Map<String, Integer> nextRider = new HashMap<>();
            for (Mount mount : mix.mounts()) {
                for (int i = 0; i < mount.count(); i++) {
                    int rider = nextRider.merge(mount.rider(), 1, Integer::sum) - 1;
                    byKey.get(mount.rider())
                            .get(rider)
                            .mount(byKey.get(mount.vehicle()).get(i));
                }
            }
            if (mix.selfVehicle() != null)
                player.compensatedEntities
                        .getSelf()
                        .mount(byKey.get(mix.selfVehicle()).get(0));
            int churnId = id;
            Vec3 churnPos = new Vec3(9.5, JavaTickFixture.FLOOR_Y + 3, 9.5);

            Method unknownMethod = UnknownCollisionProvider.class.getDeclaredMethod(
                    "addEntityUnknownCollisions",
                    CultPlayer.class,
                    Vec3.class,
                    SimpleCollisionBox.class,
                    List.class,
                    SimpleCollisionBox.class,
                    SimpleCollisionBox.class);
            unknownMethod.setAccessible(true);
            Method numCollidingMethod = WorldStageBuilder.class.getDeclaredMethod(
                    "getNumEntitiesCollidingWith", CultPlayer.class, SimulationContext.class);
            numCollidingMethod.setAccessible(true);
            Method poseMethod = CultPlayer.class.getDeclaredMethod("hasNoEntityCollision", SimpleCollisionBox.class);
            poseMethod.setAccessible(true);
            WorldStageBuilder builder = new WorldStageBuilder();
            SimpleCollisionBox queryBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, start.y, 0.5, 0.6f, 1.8f)
                    .expand(0.3);
            SimpleCollisionBox poseBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, start.y, 0.5, 0.6f, 1.5f);
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            SimpleCollisionBox entityBox = new SimpleCollisionBox();
            SimpleCollisionBox scratch = new SimpleCollisionBox();
            List<PredVector> pushStart = new ArrayList<>();
            pushStart.add(new PredVector(Vec3.ZERO));

            Tick unknown = () -> {
                boxes.clear();
                unknownMethod.invoke(null, player, start, queryBox, boxes, entityBox, scratch);
            };
            Tick numColliding = () -> numCollidingMethod.invoke(builder, player, context);
            Tick recount = () -> WorldStageBuilder.recountCollidingTickingAfterPlayer(player, prediction);
            Tick pose = () -> poseMethod.invoke(player, poseBox);
            Tick vehiclePush = () -> EntityPush.addVehiclePushCandidates(player, pushStart, context, prediction);
            Tick tick = () -> {
                for (int i = 0; i < mix.unknownPerTick(); i++) unknown.run();
                numColliding.run();
                recount.run();
                pose.run();
                pose.run();
                vehiclePush.run();
                vehiclePush.run();
            };
            Tick churnTick = () -> {
                entities.addEntity(churnId, churnType, churnPos, 0, 0, 0);
                tick.run();
                entities.removeEntity(churnId);
                tick.run();
            };
            int itemId = churnId + 1;
            int itemType = EntityTypes.defaults().byKey("item").id();
            // Items are never tracked, so their spawn and despawn leave entityMap as it was.
            Tick itemTick = () -> {
                entities.addEntity(itemId, itemType, churnPos, 0, 0, 0);
                entities.removeEntity(itemId);
                tick.run();
            };
            Object[] sink = new Object[1];
            Tick trackedWalk = () -> entities.forEachTrackedEntity(entity -> sink[0] = entity);
            String[] names = {
                "unknown",
                "numColliding",
                "recount",
                "pose",
                "vehiclePush",
                "tick",
                "tick+churn/2",
                "tick+item",
                "trackedWalk"
            };
            Tick[] ticks = {unknown, numColliding, recount, pose, vehiclePush, tick, churnTick, itemTick, trackedWalk};
            int[] perRun = {1, 1, 1, 1, 1, 1, 2, 1, 1};
            for (Tick t : ticks) for (int i = 0; i < WARMUP_TICKS; i++) t.run();
            ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            double[][] cpu = new double[ticks.length][REPS];
            double[][] bytes = new double[ticks.length][REPS];
            for (int rep = 0; rep < REPS; rep++) {
                for (int t = 0; t < ticks.length; t++) {
                    long begin = bean.getCurrentThreadCpuTime();
                    long allocBegin = bean.getCurrentThreadAllocatedBytes();
                    long elapsed;
                    long count = 0;
                    do {
                        for (int i = 0; i < BATCH; i++) ticks[t].run();
                        count += (long) BATCH * perRun[t];
                        elapsed = bean.getCurrentThreadCpuTime() - begin;
                    } while (elapsed < REP_CPU_NANOS);
                    cpu[t][rep] = (double) elapsed / count;
                    bytes[t][rep] = (double) (bean.getCurrentThreadAllocatedBytes() - allocBegin) / count;
                }
            }
            StringBuilder report =
                    new StringBuilder(String.format("== %s: %d entities%n", mix.name(), entities.entityMap.size()));
            for (int t = 0; t < ticks.length; t++) {
                double[] c = cpu[t].clone();
                double[] b = bytes[t].clone();
                Arrays.sort(c);
                Arrays.sort(b);
                report.append(String.format(
                        "%s: median %.1f ns, min %.1f, max %.1f, %.1f B%n",
                        names[t], c[REPS / 2], c[0], c[REPS - 1], b[REPS / 2]));
            }
            return report.toString();
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
