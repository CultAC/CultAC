package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Thread CPU of entity tracking and interpolation in a lobby-like world; runs only with CULT_TRACKING_BENCH set. */
class EntityTrackingCpuBenchTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int ENTITIES = 80;
    private static final int WARMUP_TICKS = 20_000;
    private static final int BATCH = 200;
    private static final long REP_CPU_NANOS = 400_000_000L;
    private static final int REPS = 15;

    private interface Tick {
        void run() throws Exception;
    }

    @Test
    void lobbyEntityTracking() throws Exception {
        String out = System.getenv("CULT_TRACKING_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            PredictionResult prediction = f.tick(start, true);
            CompensatedEntities entities = player.compensatedEntities;
            int[] types = {
                EntityTypeIds.ZOMBIE, EntityTypeIds.ARMOR_STAND, EntityTypeIds.PIG, EntityTypeIds.OAK_BOAT,
                EntityTypeIds.CHICKEN, EntityTypeIds.HAPPY_GHAST, EntityTypeIds.SPIDER, EntityTypeIds.HORSE
            };
            Random random = new Random(7);
            for (int i = 0; i < ENTITIES; i++) {
                double x = random.nextInt(31) - 15 + 0.5;
                double z = random.nextInt(31) - 15 + 0.5;
                if (i < 6) {
                    x = 0.5 + (i % 3) * 0.4 - 0.4;
                    z = 1.0 + (i / 3) * 0.4;
                }
                // Server ids out of spawn order, as on a server with despawns.
                int id = 1000 + random.nextInt(1_000_000);
                while (entities.getEntity(id) != null) id++;
                entities.addEntity(id, types[i % types.length], new Vec3(x, JavaTickFixture.FLOOR_Y, z), 0, 0, 0);
            }
            List<PacketEntity> tracked = new ArrayList<>(entities.entityMap.values());
            PacketEntity zombie = null;
            for (PacketEntity entity : tracked) if (entity.type == EntityTypeIds.ZOMBIE) zombie = entity;
            ReachInterpolationData stepped = zombie.newPacketLocation;
            List<PositionPath.Step> steps = List.of(
                    new PositionPath.Step(new Vec3d(1.25, 64, 2.5), 1),
                    new PositionPath.Step(new Vec3d(1.5, 64.25, 2.75), 2),
                    new PositionPath.Step(new Vec3d(1.75, 64.5, 3.0), 1));
            PositionPath protocolPath = new PositionPath.Stepped(steps);
            EntityPositionPath path = EntityPositionPath.fromProtocol(protocolPath);
            Object[] sink = new Object[1];

            Tick order = () -> entities.tickClientEntities(entity -> sink[0] = entity);
            Tick interpolation = () -> entities.tickClientEntities(entity -> entity.onMovement(player, true));
            Tick pushers = () -> WorldStageBuilder.recountCollidingTickingAfterPlayer(player, prediction);
            Tick paths = () -> {
                for (int i = 0; i < 10; i++) sink[0] = EntityPositionPath.fromProtocol(protocolPath);
            };
            Tick copies = () -> {
                for (int i = 0; i < 10; i++) {
                    ReachInterpolationData copy = stepped.copy();
                    copy.appendPath(path, 10, 0);
                    sink[0] = copy;
                }
            };
            Tick boxes = () -> {
                for (PacketEntity entity : tracked) sink[0] = entity.getPossibleMovementCollisionBoxes();
            };
            String[] names = {
                "tick order (tickClientEntities, no-op tick)",
                "interpolation tick (tickClientEntities + onMovement)",
                "pusher recount",
                "movement boxes x80",
                "fromProtocol stepped x10",
                "copy + appendPath x10"
            };
            Tick[] ticks = {order, interpolation, pushers, boxes, paths, copies};
            StringBuilder report = new StringBuilder();
            for (int t = 0; t < ticks.length; t++) {
                for (int i = 0; i < WARMUP_TICKS; i++) ticks[t].run();
            }
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            double[][] samples = new double[ticks.length][REPS];
            for (int rep = 0; rep < REPS; rep++) {
                for (int t = 0; t < ticks.length; t++) {
                    // Thread CPU time is coarse on Windows, so each sample spans REP_CPU_NANOS of CPU.
                    long begin = bean.getCurrentThreadCpuTime();
                    long elapsed;
                    long count = 0;
                    do {
                        for (int i = 0; i < BATCH; i++) ticks[t].run();
                        count += BATCH;
                        elapsed = bean.getCurrentThreadCpuTime() - begin;
                    } while (elapsed < REP_CPU_NANOS);
                    samples[t][rep] = (double) elapsed / count;
                }
            }
            for (int t = 0; t < ticks.length; t++) {
                double[] sorted = samples[t].clone();
                Arrays.sort(sorted);
                report.append(String.format(
                        "%s: median %.1f ns/tick, min %.1f, max %.1f%n",
                        names[t], sorted[REPS / 2], sorted[0], sorted[REPS - 1]));
            }
            System.out.print(report);
            write(Path.of(out), report.toString());
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
