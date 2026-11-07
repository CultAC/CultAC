package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The bound always holds where a client playing an actor back draws it. The client below follows 1.26.40.5's
 * PredictedMovementSystem: moves stamped when their datagram is pulled and handled when its packet budget allows,
 * playback at max(prev time, now - D) with D from the ramped gap between the newest two moves, and a third of the way
 * toward the previous tick's target each tick. The server sees only when it sent each move and the order of the
 * client's inputs and its answers to the markers around each move.
 */
class BedrockEntityPlaybackTest {
    private static final float WIDTH = 0.6F;
    private static final float HEIGHT = 1.95F;

    @Test
    void theBoundHoldsAMobPlayedBackUnderAnyTiming() {
        for (int seed = 0; seed < 200; seed++) run(new Random(seed), BedrockEntityPlayback.Window.MOB, false);
    }

    @Test
    void theBoundHoldsAProjectilePlayedBackUnderAnyTiming() {
        for (int seed = 0; seed < 200; seed++) {
            run(new Random(seed), BedrockEntityPlayback.Window.MOB_OR_PROJECTILE, true);
        }
    }

    private static void run(Random random, BedrockEntityPlayback.Window window, boolean projectile) {
        // Server ticks at which the actor moves: a walk, a stop, and a second walk.
        List<double[]> moves = new ArrayList<>();
        double x = 0.0D;
        for (int tick = 1; tick <= 120; tick++) {
            if (tick > 40 && tick <= 75) continue;
            x += 0.15D + random.nextDouble() * 0.2D;
            moves.add(new double[] {tick, x});
        }
        double latency = 20.0D + random.nextDouble() * 80.0D;
        double frame = 1000.0D / (20 + random.nextInt(125));
        double spawnRead = Math.ceil((latency + random.nextDouble() * 30.0D) / frame) * frame;

        // Client times, frame aligned: each move's datagram is pulled in some frame's network step, in order. The
        // packet budget can leave the move, or the marker after it, for a later frame; packets are handled in order.
        double[] pulled = new double[moves.size()];
        double[] handled = new double[moves.size()];
        double[] answered = new double[moves.size()];
        double lastPulled = spawnRead;
        double lastHandled = spawnRead;
        for (int i = 0; i < moves.size(); i++) {
            double arrival = moves.get(i)[0] * 50.0D + latency + random.nextDouble() * 40.0D;
            lastPulled = Math.max(lastPulled, Math.ceil(arrival / frame) * frame);
            pulled[i] = lastPulled;
            handled[i] = Math.max(lastHandled, pulled[i] + frame * backlog(random));
            answered[i] = handled[i] + frame * backlog(random);
            lastHandled = answered[i];
        }
        int ticks = 200;
        double[] tickTime = new double[ticks + 1];
        for (int k = 0; k <= ticks; k++) tickTime[k] = Math.ceil((k * 50.0D + 1.0E-9D) / frame) * frame;
        double[] shown = new ReferenceClient(projectile).play(moves, pulled, handled, spawnRead, tickTime);

        // The server: inputs, the answers to the markers after each move, and when each move is sent.
        record Event(double time, int kind, int index) {}
        // At one time: an input before the answers handled after its tick, and answers in the order they were sent.
        PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> a.time != b.time
                ? Double.compare(a.time, b.time)
                : a.kind != b.kind ? a.kind - b.kind : a.index - b.index);
        double upload = latency * (0.5D + random.nextDouble());
        for (int k = 1; k <= ticks; k++) events.add(new Event(tickTime[k] + upload, 0, k));
        for (int i = 0; i < moves.size(); i++) {
            events.add(new Event(answered[i] + upload, 1, i));
            events.add(new Event(moves.get(i)[0] * 50.0D, 2, i));
        }
        events.add(new Event(spawnRead + upload, 1, -1));
        BedrockEntityPlayback playback = null;
        var samples = new BedrockEntityPlayback.Sample[moves.size()];
        boolean[] leadingAnswered = new boolean[moves.size()];
        long processed = 0;
        while (!events.isEmpty()) {
            Event event = events.poll();
            switch (event.kind) {
                case 0 -> {
                    processed = event.index;
                    if (playback == null) continue;
                    playback.tick(processed, WIDTH, HEIGHT);
                    SimpleCollisionBox box = playback.box();
                    double feetMin = box.minX + WIDTH / 2.0D;
                    double feetMax = box.maxX - WIDTH / 2.0D;
                    double at = shown[event.index];
                    assertTrue(
                            at >= feetMin - 1.0E-9D && at <= feetMax + 1.0E-9D,
                            "tick " + event.index + ": client at " + at + ", bound " + feetMin + ".." + feetMax);
                }
                case 1 -> {
                    if (event.index < 0) {
                        // The spawn's marker answer: the actor exists, and the first move's leading marker is answered.
                        playback = new BedrockEntityPlayback(new Vec3(0, 64, 0), window, processed, WIDTH, HEIGHT);
                        leadingAnswered[0] = true;
                        if (samples[0] != null) playback.begin(samples[0]);
                        continue;
                    }
                    playback.settle(samples[event.index], processed);
                    if (event.index + 1 < moves.size()) {
                        leadingAnswered[event.index + 1] = true;
                        if (samples[event.index + 1] != null) playback.begin(samples[event.index + 1]);
                    }
                }
                default -> {
                    samples[event.index] =
                            new BedrockEntityPlayback.Sample(new Vec3(moves.get(event.index)[1], 64, 0), processed);
                    if (playback != null && leadingAnswered[event.index]) playback.begin(samples[event.index]);
                }
            }
        }
    }

    // Frames the packet budget defers the next packet by: mostly none.
    private static int backlog(Random random) {
        int roll = random.nextInt(20);
        return roll < 16 ? 0 : roll < 19 ? 1 : 2;
    }

    /** 1.26.40.5's playback of one actor along x, in client time. */
    private static final class ReferenceClient {
        private final boolean projectile;

        ReferenceClient(boolean projectile) {
            this.projectile = projectile;
        }

        double[] play(List<double[]> moves, double[] pulled, double[] handled, double spawnRead, double[] tickTime) {
            List<double[]> history = new ArrayList<>(); // {time, x}
            history.add(new double[] {spawnRead, 0.0D});
            double[] shown = new double[tickTime.length];
            double position = 0.0D;
            double target = Double.NaN;
            double gapFrom = 0.0D, gapTo = 0.0D;
            int gapTick = 0;
            double prevTime = Double.NEGATIVE_INFINITY;
            int next = 0;
            for (int k = 1; k < tickTime.length; k++) {
                double now = tickTime[k];
                // Moves handled before this tick, stamped when their datagram was pulled, each updating the gap.
                while (next < moves.size() && handled[next] < now) {
                    double gap = pulled[next] - history.getLast()[0];
                    history.add(new double[] {pulled[next], moves.get(next)[1]});
                    gapFrom = gapTo;
                    gapTo = gap;
                    gapTick = k;
                    next++;
                }
                if (!Double.isNaN(target)) position += (target - position) / 3.0D;
                double gap =
                        projectile ? gapTo : gapFrom + (gapTo - gapFrom) * Math.min(1.0D, (k - gapTick + 1) / 3.0D);
                double window = projectile ? 50.0D : 100.0D;
                double delay = gap == 0.0D ? window : Math.max(window, window * Math.clamp(gap / 50.0D, 1.0D, 8.0D));
                double playback = Math.max(prevTime, now - delay);
                double[] prev = null;
                double[] after = null;
                for (double[] sample : history) {
                    if (sample[0] <= playback) prev = sample;
                    else if (after == null) after = sample;
                }
                if (prev == null) prev = history.getFirst();
                if (after == null) {
                    target = history.getLast()[1];
                } else {
                    double alpha = after[0] == prev[0] ? 1.0D : (playback - prev[0]) / (after[0] - prev[0]);
                    target = prev[1] + (after[1] - prev[1]) * Math.clamp(alpha, 0.0D, 1.0D);
                }
                prevTime = prev[0];
                shown[k] = position;
            }
            return shown;
        }
    }
}
