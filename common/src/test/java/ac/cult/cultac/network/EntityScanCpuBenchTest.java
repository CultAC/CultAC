package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Thread CPU of the per-tick entity scans in a lobby-like world; runs only with CULT_ENTITY_BENCH set. */
class EntityScanCpuBenchTest {
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
    void lobbyEntityScans() throws Exception {
        String out = System.getenv("CULT_ENTITY_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            Vec3 start = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            PredictionResult prediction = f.tick(start, true);
            CompensatedEntities entities = player.compensatedEntities;
            int[] types = {
                EntityTypeIds.ZOMBIE, EntityTypeIds.ARMOR_STAND, EntityTypeIds.PLAYER, EntityTypeIds.PIG,
                EntityTypeIds.OAK_BOAT, EntityTypeIds.CHICKEN, EntityTypeIds.HAPPY_GHAST, EntityTypeIds.SPIDER
            };
            Random random = new Random(7);
            for (int i = 0; i < ENTITIES; i++) {
                double x = random.nextInt(31) - 15 + 0.5;
                double z = random.nextInt(31) - 15 + 0.5;
                if (i < 6) {
                    x = 0.5 + (i % 3) * 0.4 - 0.4;
                    z = 1.0 + (i / 3) * 0.4;
                }
                entities.addEntity(2000 + i, types[i % types.length], new Vec3(x, JavaTickFixture.FLOOR_Y, z), 0, 0, 0);
            }
            Method noEntityCollision =
                    CultPlayer.class.getDeclaredMethod("hasNoEntityCollision", SimpleCollisionBox.class);
            noEntityCollision.setAccessible(true);
            SimpleCollisionBox poseBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, start.y, 0.5, 0.6f, 1.5f);
            List<SimpleCollisionBox> collisions = new ArrayList<>();
            SimpleCollisionBox collideBox = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, start.y, 0.5, 0.6f, 1.8f)
                    .expand(0.3);

            Tick passengers = () -> {
                for (int i = 0; i < ENTITIES; i++) entities.updatePassengerPositions();
            };
            Tick pushers = () -> WorldStageBuilder.recountCollidingTickingAfterPlayer(player, prediction);
            Tick pose = () -> noEntityCollision.invoke(player, poseBox);
            Tick blocks = () -> {
                for (int i = 0; i < 20; i++) {
                    collisions.clear();
                    Collisions.getCollisionBoxes(player, collideBox, collisions, false, start.y);
                }
            };
            String[] names = {"passengers x80", "pusher recount", "pose entity check", "block scans x20"};
            Tick[] ticks = {passengers, pushers, pose, blocks};
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
