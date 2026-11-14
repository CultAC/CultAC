package ac.cult.cultac.utils.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A vanilla client on a lagging link as the pending-ping bound sees it. The server writes a ping every tick and the
 * watchdog runs every 60 ms; the client answers each ping when it arrives and sends a move every tick. Each direction
 * delivers in order. A setback is a teleport and its ping, pending until that ping is answered.
 */
class PendingPingBoundTest {
    private static final int BOUND_MS = 1000;
    private static final int RUN_MS = 60_000;
    private static final int SPIKE_AT = 20_000;
    private static final long BASE_NANOS = 1_000_000_000_000L;

    @Test
    void steadyLatencyUpToNineHundredMsIsNeverSetBack() {
        for (int rtt : new int[] {50, 300, 500, 800, 900}) {
            int oneWay = rtt / 2;
            assertEquals(0, setbacks(t -> oneWay, t -> oneWay, false), rtt + " ms");
        }
    }

    @Test
    void jitterBetweenThreeAndNineHundredMsIsNeverSetBack() {
        for (long seed = 1; seed <= 5; seed++) {
            Random down = new Random(seed);
            Random up = new Random(-seed);
            int[] downDelays = delays(down);
            int[] upDelays = delays(up);
            assertEquals(0, setbacks(t -> downDelays[t], t -> upDelays[t], false), "seed " + seed);
            assertEquals(0, setbacks(t -> downDelays[t], t -> upDelays[t], true), "seed " + seed + " split reads");
        }
    }

    @Test
    void aDownstreamSpikeWhileMovingIsSetBackOnce() {
        for (int base : new int[] {150, 400}) {
            for (int spike : new int[] {2_000, 3_000, 5_000}) {
                String at = "base " + base + " spike " + spike;
                assertEquals(1, setbacks(spiked(base, spike), t -> base, false), at);
                assertEquals(1, setbacks(spiked(base, spike), t -> base, true), at + " split reads");
            }
        }
    }

    @Test
    void anUpstreamSpikeIsNotSetBack() {
        for (int base : new int[] {150, 400}) {
            for (int spike : new int[] {2_000, 3_000, 5_000}) {
                String at = "base " + base + " spike " + spike;
                assertEquals(0, setbacks(t -> base, spiked(base, spike), false), at);
                assertTrue(setbacks(t -> base, spiked(base, spike), true) <= 1, at + " split reads");
            }
        }
    }

    @Test
    void aSpikeBothWaysIsSetBackAtMostOnce() {
        for (int base : new int[] {150, 400}) {
            for (int spike : new int[] {2_000, 3_000, 5_000}) {
                String at = "base " + base + " spike " + spike;
                assertTrue(setbacks(spiked(base, spike), spiked(base, spike), false) <= 1, at);
                assertTrue(setbacks(spiked(base, spike), spiked(base, spike), true) <= 1, at + " split reads");
            }
        }
    }

    @Test
    void aSustainedDelayAboveTheBoundIsSetBackOnce() {
        for (int[] oneWay : new int[][] {{550, 550}, {750, 750}, {1_000, 100}, {1_500, 1_500}}) {
            String at = "down " + oneWay[0] + " up " + oneWay[1];
            Network network = new Network(t -> oneWay[0], t -> oneWay[1], false);
            network.run(RUN_MS);
            assertEquals(1, network.setbacks, at);
            assertTrue(network.movesAfterAcceptance > 1_000, at + ": moves after the setback " + network.movesAfterAcceptance);
            assertEquals(1, setbacks(t -> oneWay[0], t -> oneWay[1], true), at + " split reads");
        }
    }

    @Test
    void jitterEachWayAroundEightHundredMsSetsBackAtMostOncePerBound() {
        int[] counts = new int[5];
        for (int seed = 1; seed <= 5; seed++) {
            int[] downDelays = delays(new Random(seed), 250, 550);
            int[] upDelays = delays(new Random(-seed), 250, 550);
            Network network = new Network(t -> downDelays[t], t -> upDelays[t], false);
            network.run(RUN_MS);
            counts[seed - 1] = network.setbacks;
            assertTrue(network.minSetbackGap >= BOUND_MS, "seed " + seed + " gap " + network.minSetbackGap);
        }
        assertEquals(Arrays.toString(EXPECTED_JITTER_800), Arrays.toString(counts));
    }

    @Test
    void oneLostServerToClientSegmentAtFiveHundredMsIsSetBackOnce() {
        // The lost segment is resent one round trip plus 100 ms later; everything written after it waits for it.
        assertEquals(1, setbacks(spiked(250, 600), t -> 250, false));
        assertEquals(1, setbacks(spiked(250, 600), t -> 250, true), "split reads");
    }

    @Test
    void aThreeSecondServerToClientStallIsSetBackOnce() {
        for (int base : new int[] {150, 250, 400}) {
            assertEquals(1, setbacks(spiked(base, 3_000), t -> base, false), "base " + base);
            assertEquals(1, setbacks(spiked(base, 3_000), t -> base, true), "base " + base + " split reads");
        }
    }

    @Test
    void anUnansweredPingPastTheBoundIsSetBackOnce() {
        Network network = new Network(t -> 50, t -> 50, false);
        network.holdPongsFrom(SPIKE_AT);
        network.run(SPIKE_AT + 10_000);
        assertEquals(1, network.setbacks);
        // The first unanswered ping was written within one tick before SPIKE_AT.
        assertTrue(network.firstSetbackAt > SPIKE_AT + BOUND_MS - 50, "at " + network.firstSetbackAt);
        assertTrue(network.firstSetbackAt <= SPIKE_AT + BOUND_MS + 120, "at " + network.firstSetbackAt);
    }

    @Test
    void aBoundOfZeroOrLessIsOff() {
        PendingPingBound bound = new PendingPingBound();
        long move = BASE_NANOS + 59_000_000_000L;
        long now = BASE_NANOS + 60_000_000_000L;
        assertEquals(60_000, bound.overdueMs(now, BASE_NANOS, move, BOUND_MS));
        assertEquals(-1, bound.overdueMs(now, BASE_NANOS, move, 0));
        assertEquals(-1, bound.overdueMs(now, BASE_NANOS, move, -1));
    }

    @Test
    void onlyATickArrivingPastTheBoundCounts() {
        PendingPingBound bound = new PendingPingBound();
        long now = BASE_NANOS + ms(5_000);
        assertEquals(-1, bound.overdueMs(now, BASE_NANOS, BASE_NANOS + ms(1_000), BOUND_MS), "no tick after the bound");
        assertEquals(5_000, bound.overdueMs(now, BASE_NANOS, BASE_NANOS + ms(1_001), BOUND_MS));
    }

    @Test
    void aSetbackRearmsOnlyAfterItsAnswerAnAgeBelowTheBoundAndOneBound() {
        PendingPingBound bound = new PendingPingBound();
        long firedAt = BASE_NANOS + ms(1_100);
        bound.fired(firedAt, 7);
        long owed = BASE_NANOS + ms(200);
        long tick = firedAt;
        assertEquals(-1, bound.overdueMs(firedAt + ms(60), owed, tick, BOUND_MS), "fired");

        assertFalse(bound.advances(firedAt + ms(500), owed, 6, BOUND_MS), "the setback is unanswered");
        assertTrue(bound.advances(firedAt + ms(500), owed, 7, BOUND_MS));
        bound.advance(firedAt + ms(500), owed, 7, BOUND_MS);
        assertFalse(bound.advances(firedAt + ms(600), owed, 7, BOUND_MS), "the oldest ping is still past the bound");
        assertEquals(-1, bound.overdueMs(firedAt + ms(600), owed, firedAt + ms(600), BOUND_MS));

        long younger = firedAt + ms(400);
        assertTrue(bound.advances(firedAt + ms(700), younger, 7, BOUND_MS));
        bound.advance(firedAt + ms(700), younger, 7, BOUND_MS);
        assertFalse(bound.advances(firedAt + ms(990), younger, 7, BOUND_MS), "within one bound of the setback");
        assertTrue(bound.advances(firedAt + ms(1_000), younger, 7, BOUND_MS));
        bound.advance(firedAt + ms(1_000), younger, 7, BOUND_MS);
        assertFalse(bound.advances(firedAt + ms(1_000), younger, 7, BOUND_MS), "armed");
        assertEquals(-1, bound.overdueMs(firedAt + ms(1_300), younger, firedAt + ms(1_300), BOUND_MS));
        assertEquals(1_001, bound.overdueMs(firedAt + ms(1_401), younger, firedAt + ms(1_401), BOUND_MS));
    }

    /** Setbacks in a minute at 800 ms with 250 to 550 ms each way, for seeds 1 to 5. */
    private static final int[] EXPECTED_JITTER_800 = {5, 3, 5, 3, 5};

    private static long ms(long ms) {
        return ms * 1_000_000L;
    }

    /** One-way delays of 150 to 450 ms per millisecond sent, so the round trip spans 300 to 900 ms. */
    private static int[] delays(Random random) {
        return delays(random, 150, 450);
    }

    private static int[] delays(Random random, int min, int max) {
        int[] delays = new int[RUN_MS + 1];
        for (int t = 0; t <= RUN_MS; t++) delays[t] = min + random.nextInt(max - min + 1);
        return delays;
    }

    /** {@code base} ms, except that nothing sent during the spike arrives before it ends. */
    private static IntUnaryOperator spiked(int base, int spike) {
        return t -> t >= SPIKE_AT && t < SPIKE_AT + spike ? SPIKE_AT + spike - t + base : base;
    }

    private static int setbacks(IntUnaryOperator down, IntUnaryOperator up, boolean splitReads) {
        Network network = new Network(down, up, splitReads);
        network.run(RUN_MS);
        return network.setbacks;
    }

    private static final class Network {
        private final IntUnaryOperator down;
        private final IntUnaryOperator up;
        private final boolean splitReads;
        private final PendingPingBound bound = new PendingPingBound();
        /** Packets in flight as {arrival ms, ping id or -1 for a move}; each direction keeps its order. */
        private final ArrayDeque<int[]> toClient = new ArrayDeque<>();
        private final ArrayDeque<int[]> toServer = new ArrayDeque<>();
        private final ArrayDeque<long[]> pending = new ArrayDeque<>();
        private final ArrayDeque<Long> moves = new ArrayDeque<>();
        private final ArrayDeque<Integer> heldPongs = new ArrayDeque<>();
        private int lastToClient;
        private int lastToServer;
        private int nextPing = 1;
        private int answered;
        private int setbackProof = -1;
        private int holdPongsFrom = Integer.MAX_VALUE;
        int setbacks;
        int firstSetbackAt = -1;
        int lastSetbackAt = -1;
        int minSetbackGap = Integer.MAX_VALUE;
        int movesAfterAcceptance;

        Network(IntUnaryOperator down, IntUnaryOperator up, boolean splitReads) {
            this.down = down;
            this.up = up;
            this.splitReads = splitReads;
        }

        void holdPongsFrom(int t) {
            holdPongsFrom = t;
        }

        void run(int durationMs) {
            for (int t = 0; t <= durationMs; t++) {
                clientReceives(t);
                if (t % 50 == 25) sendToServer(t, -1);
                serverReceives(t);
                if (t % 50 == 0) ping(t);
            }
        }

        private void clientReceives(int t) {
            while (!toClient.isEmpty() && toClient.peek()[0] <= t) {
                int id = toClient.poll()[1];
                if (t >= holdPongsFrom) heldPongs.add(id);
                else sendToServer(t, id);
            }
        }

        private void serverReceives(int t) {
            int arrived = 0;
            for (int[] packet : toServer) {
                if (packet[0] > t) break;
                arrived++;
            }
            int firstRead = splitReads && arrived > 1 ? arrived / 2 : arrived;
            for (int i = 0; i < firstRead; i++) read(t, toServer.poll());
            if (t % 60 == 0 || firstRead < arrived) watchdog(t);
            for (int i = firstRead; i < arrived; i++) read(t, toServer.poll());
        }

        private void read(int t, int[] packet) {
            if (packet[1] < 0) {
                moves.add(nanos(t));
                if (moves.size() > 20) moves.poll();
                if (setbackProof >= 0 && answered >= setbackProof) movesAfterAcceptance++;
                return;
            }
            while (!pending.isEmpty() && pending.peek()[0] <= packet[1]) pending.poll();
            answered = packet[1];
        }

        private void watchdog(int t) {
            long owedSince = pending.isEmpty() ? 0 : pending.peek()[1];
            bound.advance(nanos(t), owedSince, answered, BOUND_MS);
            if (setbackProof > answered) return;
            long lastTick = moves.isEmpty() ? 0 : moves.peekLast();
            if (bound.overdueMs(nanos(t), owedSince, lastTick, BOUND_MS) < 0) return;
            setbacks++;
            if (firstSetbackAt < 0) firstSetbackAt = t;
            if (lastSetbackAt >= 0) minSetbackGap = Math.min(minSetbackGap, t - lastSetbackAt);
            lastSetbackAt = t;
            setbackProof = ping(t);
            bound.fired(nanos(t), setbackProof);
        }

        private int ping(int t) {
            int id = nextPing++;
            pending.add(new long[] {id, nanos(t)});
            lastToClient = Math.max(lastToClient, t + down.applyAsInt(t));
            toClient.add(new int[] {lastToClient, id});
            return id;
        }

        private void sendToServer(int t, int id) {
            lastToServer = Math.max(lastToServer, t + up.applyAsInt(t));
            toServer.add(new int[] {lastToServer, id});
        }

        private static long nanos(int t) {
            return BASE_NANOS + t * 1_000_000L;
        }
    }
}
