package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocation of one entityMap change followed by the tick-end type view reads (camels, push
 * candidates, boats) and zero to two playerCollisionBoxes calls, and of one call alone, each against a test copy of
 * the rebuild-every-view and full-scan forms, interleaved in one JVM over entity mixes; runs only with
 * CULT_VIEW_COUNT_BENCH set.
 */
class EntityViewCountCpuBenchTest {
    private static final double Y = JavaTickFixture.FLOOR_Y;
    private static final int WARMUP = 20_000;
    private static final int BATCH = 50;
    private static final long REP_CPU_NANOS = 200_000_000L;
    private static final int REPS = 15;

    private interface Run {
        Object run();
    }

    private record Mix(String name, String[] keys, int[] counts) {}

    private static final Mix[] MIXES = {
        new Mix(
                "lobby",
                new String[] {"player", "arrow", "armor_stand", "tnt_minecart", "text_display", "pig"},
                new int[] {26, 20, 6, 3, 1, 1}),
        new Mix("duels", new String[] {"player", "arrow", "ender_pearl", "text_display"}, new int[] {2, 4, 1, 3}),
        new Mix("mob farm", new String[] {"player", "sheep", "arrow", "chicken", "cow", "oak_boat"}, new int[] {
            4, 80, 20, 60, 30, 2
        }),
    };

    @Test
    void viewsAndCollisionBoxes() throws Exception {
        String out = System.getenv("CULT_VIEW_COUNT_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        StringBuilder report = new StringBuilder();
        for (Mix mix : MIXES) report.append(runMix(mix));
        System.out.print(report);
        Files.writeString(Path.of(out), report + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String runMix(Mix mix) throws Exception {
        try (var f = ClientEntityQueryCandidateTest.fixture(ClientVersion.V_1_21_11)) {
            CompensatedEntities entities = f.player.compensatedEntities;
            Random random = new Random(7);
            int id = 2000;
            for (int k = 0; k < mix.keys().length; k++) {
                int type = EntityTypes.defaults().byKey(mix.keys()[k]).id();
                for (int i = 0; i < mix.counts()[k]; i++) {
                    var at = new Vec3(random.nextInt(31) - 15 + 0.5, Y, random.nextInt(31) - 15 + 0.5);
                    if (type == EntityTypeIds.PLAYER) f.addRemotePlayer(id++, at, GameMode.SURVIVAL);
                    else ClientEntityQueryCandidateTest.moving(f, id++, type, at, random.nextInt(2));
                }
            }
            var queries = new ClientEntityQueries(f.player);
            int touchedId = 2000 + mix.counts()[0];
            PacketEntity touched = entities.getEntity(touchedId);
            BaseViews base = new BaseViews();
            Box near = ClientEntityQueryCandidateTest.NEAR;

            Run[] runs = new Run[8];
            String[] names = new String[8];
            for (int calls = 0; calls < 3; calls++) {
                int k = calls;
                names[2 * k] = "change + views + " + k + " playerCollisionBoxes";
                names[2 * k + 1] = names[2 * k] + " base copy";
                runs[2 * k] = () -> {
                    entities.entityMap.put(touchedId, touched);
                    int size = entities.camels().size()
                            + entities.pushOrCollideCandidates().size()
                            + entities.boats().size();
                    for (int i = 0; i < k; i++)
                        size += queries.playerCollisionBoxes(near).size();
                    return size;
                };
                runs[2 * k + 1] = () -> {
                    entities.entityMap.put(touchedId, touched);
                    base.refresh(entities);
                    int size = base.camels.size() + base.pushOrCollide.size() + base.boats.size();
                    for (int i = 0; i < k; i++) size += fullScan(f.player, near).size();
                    return size;
                };
            }
            names[6] = "playerCollisionBoxes";
            names[7] = "playerCollisionBoxes base copy";
            runs[6] = () -> queries.playerCollisionBoxes(near);
            runs[7] = () -> fullScan(f.player, near);
            for (int t = 0; t < runs.length; t += 2) assertEquals(runs[t + 1].run(), runs[t].run(), names[t]);

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
            StringBuilder report =
                    new StringBuilder(String.format("== %s: %d entities%n", mix.name(), entities.entityMap.size()));
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

    // The five type views rebuilt together on every change, presized from the previous build.
    private static final class BaseViews {
        List<PacketEntity> pushOrCollide = List.of();
        List<PacketEntity> boats = List.of();
        List<PacketEntityCamel> camels = List.of();
        List<PacketEntity> pose = List.of();
        List<PacketEntity> unknown = List.of();

        void refresh(CompensatedEntities entities) {
            List<PacketEntity> pushOrCollideList = presized(entities, pushOrCollide);
            List<PacketEntity> boatList = presized(entities, boats);
            List<PacketEntityCamel> camelList = new ArrayList<>(camels.size());
            List<PacketEntity> poseList = presized(entities, pose);
            List<PacketEntity> unknownList = presized(entities, unknown);
            for (PacketEntity entity : entities.entityMap.values()) {
                if (entity.mayPushOrCollide()) pushOrCollideList.add(entity);
                boolean boat = entity.isBoat();
                boolean shulker = entity.type == EntityTypeIds.SHULKER;
                if (boat) boatList.add(entity);
                if (entity instanceof PacketEntityCamel camel) camelList.add(camel);
                if (boat || shulker || entity.isHappyGhast()) poseList.add(entity);
                if (boat || shulker || entity instanceof PacketEntityHappyGhast) unknownList.add(entity);
            }
            pushOrCollide = pushOrCollideList;
            boats = boatList;
            camels = camelList;
            pose = poseList;
            unknown = unknownList;
        }

        private static List<PacketEntity> presized(CompensatedEntities entities, List<PacketEntity> previous) {
            if (previous.isEmpty() || entities.entityMap.isEmpty()) return new ArrayList<>();
            return new ArrayList<>(Math.max(10, previous.size() + 1));
        }
    }

    // ClientEntityQueries#playerCollisionBoxes over every entityMap value.
    private static List<Box> fullScan(CultPlayer player, Box query) {
        var result = new ArrayList<Box>();
        var search = query.inflate(1.0E-7);
        SimpleCollisionBox candidate = null;
        for (var entity : player.compensatedEntities.entityMap.values()) {
            if (!present(player, entity) || spectator(player, entity) || !collidable(entity)) continue;
            if (candidate == null) candidate = new SimpleCollisionBox();
            for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
                var bounds = entity.toBoundingBox(player, entity.possibleMovementCollisionBoxCandidate(i, candidate));
                Box.validateBounds(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
                if (!(search.minX() < bounds.maxX
                        && search.maxX() > bounds.minX
                        && search.minY() < bounds.maxY
                        && search.maxY() > bounds.minY
                        && search.minZ() < bounds.maxZ
                        && search.maxZ() > bounds.minZ)) continue;
                var box = new Box(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
                if (!result.contains(box)) result.add(box);
            }
        }
        return List.copyOf(result);
    }

    private static boolean collidable(PacketEntity entity) {
        if (entity.isBoat()) return true;
        if (entity.type == EntityTypeIds.SHULKER) return entity.actionAlive;
        return entity instanceof PacketEntityHappyGhast ghast && !ghast.isBaby && ghast.actionAlive && ghast.staysStill;
    }

    private static boolean present(CultPlayer player, PacketEntity entity) {
        return (!(entity instanceof PacketEntityPlayer remote) || remote.actionPresent)
                && !player.compensatedEntities.isUntrackedRider(entity);
    }

    private static boolean spectator(CultPlayer player, PacketEntity entity) {
        return entity instanceof PacketEntityPlayer remote
                && remote.actionSpectator(player.compensatedEntities.clientPlayerModes);
    }
}
