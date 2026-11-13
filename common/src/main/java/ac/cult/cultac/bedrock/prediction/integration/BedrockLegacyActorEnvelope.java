package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.utils.math.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Where a client before 1.26.60 may show a remote actor. Such a client shows an actor through a history of the
 * positions it received, sampled a delay behind its own clock: 100 ms times its smoothed packet interval over 50 ms,
 * clamped to one to eight, and reached in three lerp steps. The interval comes from the client's receive clock, which
 * the server does not see, so the delay can be anything up to 800 ms: the actor can stand anywhere between the
 * positions it was sent within the last 16 ticks and 3 steps. Each actor tick the client starts a fresh three-step lerp
 * from where it shows the actor toward the history's target, so it closes a third of the way each tick and never quite
 * arrives. Per axis the shown position therefore stays in an interval that moves a third of the way toward the range of
 * those positions each tick. An actor's buffer for a tick is the farthest that interval, or any of those positions,
 * stands from Cult's box for it on one axis.
 */
public final class BedrockLegacyActorEnvelope {
    static final int LERP_STEPS = 3;
    static final int MAX_INTERVAL_TICKS = 8;
    // The longest delay, 800 ms, and the three lerp steps after it.
    static final int WINDOW_TICKS = 2 * MAX_INTERVAL_TICKS + LERP_STEPS;
    // A track no tick has read for this long is gone from the client's view of its actor.
    private static final int FORGET_TICKS = 2 * WINDOW_TICKS;

    private final Int2ObjectOpenHashMap<Track> tracks = new Int2ObjectOpenHashMap<>();

    /**
     * The buffer of actor {@code entityId} in client tick {@code tick}, recording {@code sent}, the position the server
     * last sent it, against {@code shown}, the position Cult shows it at.
     */
    public float buffer(int entityId, long tick, Vec3 sent, Vec3 shown) {
        return buffer(entityId, tick, null, sent, shown);
    }

    /** As {@link #buffer(int, long, Vec3, Vec3)}, starting a new actor's history at {@code placed}, where it spawned. */
    public float buffer(int entityId, long tick, Vec3 placed, Vec3 sent, Vec3 shown) {
        if (sent == null || shown == null) return 0.0F;
        if ((tick & 31) == 0) forget(tick);
        Track track = tracks.get(entityId);
        if (track == null) {
            track = new Track();
            tracks.put(entityId, track);
            if (placed != null) track.record(tick, placed);
        }
        track.record(tick, sent);
        return track.reach(tick, shown);
    }

    /**
     * The offsets from {@code shown} of the positions actor {@code entityId} was sent within its delay and of the ends
     * of the interval it may be shown in, as the last {@link #buffer} call for it left them.
     */
    public java.util.List<Vec3> alternatives(int entityId, Vec3 shown) {
        Track track = tracks.get(entityId);
        if (track == null || shown == null) return java.util.List.of();
        java.util.List<Vec3> offsets = new java.util.ArrayList<>();
        for (Sample sample : track.samples) {
            Vec3 offset = new Vec3(sample.x() - shown.x, sample.y() - shown.y, sample.z() - shown.z);
            if (offset.x != 0.0D || offset.y != 0.0D || offset.z != 0.0D) offsets.add(offset);
        }
        for (double[] end : new double[][] {track.low, track.high}) {
            Vec3 offset = new Vec3(end[0] - shown.x, end[1] - shown.y, end[2] - shown.z);
            if ((offset.x != 0.0D || offset.y != 0.0D || offset.z != 0.0D) && !offsets.contains(offset)) offsets.add(offset);
        }
        return offsets;
    }

    public void clear() {
        tracks.clear();
    }

    private void forget(long tick) {
        tracks.values().removeIf(track -> tick - track.readTick > FORGET_TICKS);
    }

    private record Sample(long tick, double x, double y, double z) {}

    private static final class Track {
        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private long readTick;
        // Per axis, the interval the client's shown position lies in after the tick it was last moved for.
        private final double[] low = new double[3];
        private final double[] high = new double[3];
        private long shownTick = Long.MIN_VALUE;

        void record(long tick, Vec3 sent) {
            readTick = tick;
            Sample last = samples.peekLast();
            if (last == null || last.x() != sent.x || last.y() != sent.y || last.z() != sent.z)
                samples.addLast(new Sample(tick, sent.x, sent.y, sent.z));
            if (shownTick == Long.MIN_VALUE) {
                low[0] = high[0] = sent.x;
                low[1] = high[1] = sent.y;
                low[2] = high[2] = sent.z;
                shownTick = tick;
            }
        }

        /** Moves the shown interval a third of the way toward the range of the positions sent, once per tick. */
        private void lerp(long tick) {
            if (tick <= shownTick) return;
            double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (Sample sample : samples) {
                double[] at = {sample.x(), sample.y(), sample.z()};
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], at[axis]);
                    max[axis] = Math.max(max[axis], at[axis]);
                }
            }
            long ticks = Math.min(tick - shownTick, WINDOW_TICKS);
            for (long i = 0; i < ticks; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    low[axis] += (min[axis] - low[axis]) / LERP_STEPS;
                    high[axis] += (max[axis] - high[axis]) / LERP_STEPS;
                }
            }
            shownTick = tick;
        }

        float reach(long tick, Vec3 shown) {
            long from = tick - WINDOW_TICKS;
            // The position before the delay's start is one end of the pair the client lerps between at that start.
            while (samples.size() > 1) {
                Iterator<Sample> it = samples.iterator();
                it.next();
                if (it.next().tick() > from) break;
                samples.pollFirst();
            }
            lerp(tick);
            double[] at = {shown.x, shown.y, shown.z};
            double reach = 0.0D;
            for (int axis = 0; axis < 3; axis++)
                reach = Math.max(reach, Math.max(Math.abs(low[axis] - at[axis]), Math.abs(high[axis] - at[axis])));
            for (Sample sample : samples) {
                reach = Math.max(reach, Math.abs(sample.x() - shown.x));
                reach = Math.max(reach, Math.abs(sample.y() - shown.y));
                reach = Math.max(reach, Math.abs(sample.z() - shown.z));
            }
            return (float) reach;
        }
    }
}
