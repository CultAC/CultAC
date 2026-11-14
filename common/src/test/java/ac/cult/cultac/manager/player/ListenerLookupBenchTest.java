package ac.cult.cultac.manager.player;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Speed;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU of one Java tick's hot listener lookups (auto-jump, two item-use thresholds, NoFall, Speed or Strafe):
 * the map lookups versus the held listeners, interleaved per round. Runs only with {@code CULT_LOOKUP_BENCH} set.
 */
class ListenerLookupBenchTest {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final long ROUND_NANOS = 600_000_000L;

    @Test
    void lookupCpu() {
        Assumptions.assumeTrue(System.getenv("CULT_LOOKUP_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_LOOKUP_BENCH_REPS", "9"));
        OfflineCultTestBootstrap.installConfig();
        CultPlayer[] players = {player(), player()};
        CheckManager[] managers = {players[0].checkManager, players[1].checkManager};
        double[] base = new double[repetitions];
        double[] held = new double[repetitions];
        long sink = 0;
        for (int r = -5; r < repetitions; r++) {
            long[] baseRound = round(managers, false);
            long[] heldRound = round(managers, true);
            sink += baseRound[1] + heldRound[1];
            if (r >= 0) {
                base[r] = baseRound[0];
                held[r] = heldRound[0];
            }
        }
        if (sink == 0) throw new AssertionError();
        System.out.println(line("tick lookups map", base));
        System.out.println(line("tick lookups held", held));
        for (CultPlayer player : players) player.onRemove();
    }

    /** CPU nanoseconds per tick's lookups, and a sink. */
    private static long[] round(CheckManager[] managers, boolean heldPath) {
        long ticks = 0, sink = 0;
        long wallStart = System.nanoTime();
        long start = THREADS.getCurrentThreadCpuTime();
        while (System.nanoTime() - wallStart < ROUND_NANOS) {
            for (int i = 0; i < 100_000; i++) {
                CheckManager manager = managers[i & 1];
                boolean onGround = (i & 2) == 0;
                Object a, b, c, d, e;
                if (heldPath) {
                    a = manager.getJavaAutoJump();
                    b = manager.getNoSlow();
                    c = manager.getNoSlow();
                    d = manager.getNoFallPseudo();
                    e = onGround ? manager.getSpeed() : manager.getStrafe();
                } else {
                    a = manager.getListener(JavaAutoJump.class);
                    b = manager.getListener(NoSlow.class);
                    c = manager.getListener(NoSlow.class);
                    d = manager.getListener(NoFallPseudo.class);
                    Class<? extends Check> pseudo = onGround ? Speed.class : Strafe.class;
                    e = manager.getCheck(pseudo);
                }
                if (a != null && b == c && d != null && e != null) sink++;
            }
            ticks += 100_000;
        }
        long cpu = THREADS.getCurrentThreadCpuTime() - start;
        return new long[] {cpu * 1000 / ticks, sink};
    }

    private static CultPlayer player() {
        return new CultPlayer(
                TestUsers.create(new User.Profile(UUID.randomUUID(), ".Lookup_Bench"), new EmbeddedChannel()));
    }

    private static String line(String name, double[] picos) {
        double[] sorted = picos.clone();
        Arrays.sort(sorted);
        return String.format(
                Locale.ROOT,
                "%-20s median %7.2f ns  min %7.2f  max %7.2f  (%d reps)",
                name,
                sorted[sorted.length / 2] / 1000,
                sorted[0] / 1000,
                sorted[sorted.length - 1] / 1000,
                sorted.length);
    }
}
