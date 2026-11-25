package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.utils.math.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a 1.26.60 or later client may show a remote actor in its lerp. Such a client lerps the actor exactly as Cult
 * does, but Cult's box for it can stand a step off the client's: the client may still show it where it stood the tick
 * before, or already a step further toward its target. An actor's buffer for a tick is the farthest either of those
 * stands from Cult's box on one axis: none for an actor that is not moving.
 */
public final class BedrockActorStepEnvelope {
    // A track no tick has read for this long is gone.
    private static final int FORGET_TICKS = 40;

    private final Int2ObjectOpenHashMap<Track> tracks = new Int2ObjectOpenHashMap<>();

    /**
     * The buffer of actor {@code entityId} in client tick {@code tick}: Cult shows it at {@code shown}, lerping toward
     * {@code target} with {@code stepsLeft} steps to go.
     */
    public float buffer(int entityId, long tick, Vec3 shown, Vec3 target, int stepsLeft) {
        if (shown == null) return 0.0F;
        if ((tick & 31) == 0) tracks.values().removeIf(track -> tick - track.tick > FORGET_TICKS);
        Track track = tracks.computeIfAbsent(entityId, id -> new Track());
        track.read(tick, shown, target, stepsLeft);
        double reach = 0.0D;
        for (Vec3 offset : track.offsets) {
            reach = Math.max(reach, Math.max(Math.abs(offset.x), Math.max(Math.abs(offset.y), Math.abs(offset.z))));
        }
        return (float) reach;
    }

    /** The offsets from Cult's box of where the client may show actor {@code entityId}, as the last read left them. */
    public List<Vec3> alternatives(int entityId) {
        Track track = tracks.get(entityId);
        return track == null ? List.of() : track.offsets;
    }

    public void clear() {
        tracks.clear();
    }

    private static final class Track {
        private long tick = Long.MIN_VALUE;
        private Vec3 shown;
        private Vec3 before;
        private List<Vec3> offsets = List.of();

        void read(long tick, Vec3 shown, Vec3 target, int stepsLeft) {
            if (tick != this.tick) {
                before = this.tick == tick - 1 ? this.shown : null;
                this.tick = tick;
                this.shown = shown;
                List<Vec3> found = new ArrayList<>(2);
                if (before != null) add(found, before.subtract(shown));
                if (target != null && stepsLeft > 0) {
                    Vec3 toward = target.subtract(shown);
                    add(found, new Vec3(toward.x / stepsLeft, toward.y / stepsLeft, toward.z / stepsLeft));
                }
                offsets = List.copyOf(found);
            }
        }

        private static void add(List<Vec3> found, Vec3 offset) {
            if (offset.x != 0.0D || offset.y != 0.0D || offset.z != 0.0D) found.add(offset);
        }
    }
}
