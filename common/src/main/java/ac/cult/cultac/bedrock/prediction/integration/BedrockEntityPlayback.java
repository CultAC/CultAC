package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;

/**
 * Where a Bedrock client may show an actor it plays back by time instead of lerping it: up to 26.52 every non-player
 * actor while Mojang's flighting leaves predicted movement on, its default, and from 26.60 projectiles and physics
 * actors. The actor's lerp is modelled as well, and reach accepts either.
 *
 * <p>1.26.40.5's PredictedMovementSystem keeps every move it receives, stamped with the time the client read its
 * datagram. Each tick it plays back at T = max(prev sample time, now - D) and targets the path between the samples
 * around T, or the later sample itself when that one is on the ground. For mobs D is clamp(2 I, 100, 800) ms, I being
 * the gap between the actor's two newest moves, ramped over three ticks from the gap before; projectiles take
 * clamp(I, 50, 400) ms with the raw gap. The MovementInterpolatorSystem runs first in the tick and moves the actor a
 * third of the way toward the previous tick's target, or half and then all of it when no target was set.
 *
 * <p>The client's clock is not observable, so times are kept as tick ranges. The client reads a move's datagram after
 * Geyser sends it, so after the ticks processed by then, and before the tick after the one it answered the marker that
 * ends the move's batch in. It handles the move, adding it to the history, after answering the marker before it; its
 * packet budget of 1 ms a frame can leave part of a batch for a later frame, so it may handle a move frames after
 * reading it. A tick runs in the first frame at or after its time, so a frame of margin covers the late end of each
 * range. The shown position is a box of feet positions.
 */
public final class BedrockEntityPlayback {
    /** The windows the client may play the actor back with. */
    public enum Window {
        MOB,
        // The actor may be a projectile or physics actor, or lerp while playback is off.
        MOB_OR_PROJECTILE
    }

    /** A move as sent, and the times in which the client may have read it. */
    public static final class Sample {
        private final double x, y, z;
        // In ms of client ticks: the client read the move after readFrom and before readBefore.
        private final long readFrom;
        private long readBefore = Long.MAX_VALUE;
        // The first tick that certainly comes after the read.
        private long readByTick = Long.MAX_VALUE;
        // The gap from the sample before, and that sample's own gap, which the client ramps from.
        private long gapLow, gapHigh, previousGapLow, previousGapHigh;
        private boolean added;
        // The sample before this one, until this one is read.
        private Sample previous;

        /** A move sent after the client ticks {@code sentAfterTicks}. */
        public Sample(Vec3 feet, long sentAfterTicks) {
            this(feet.x, feet.y, feet.z, sentAfterTicks * TICK_MILLIS);
        }

        private Sample(double x, double y, double z, long readFrom) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.readFrom = readFrom;
        }

        private boolean read() {
            return readBefore != Long.MAX_VALUE;
        }
    }

    private static final long TICK_MILLIS = 50L;
    // The longest frame a client ticks at 20 frames per second or more.
    private static final long FRAME_MILLIS = 50L;
    private static final long UNKNOWN = Long.MIN_VALUE / 4;
    private static final int RAMP_TICKS = 3;

    private final Window window;
    // From the oldest sample the client can still play from to the newest it may have read.
    private final ArrayList<Sample> samples = new ArrayList<>(4);
    private final SimpleCollisionBox box = new SimpleCollisionBox();
    private double minX, minY, minZ, maxX, maxY, maxZ;
    private double targetMinX, targetMinY, targetMinZ, targetMaxX, targetMaxY, targetMaxZ;
    private long latestPlayback = UNKNOWN;

    /**
     * An actor the client has spawned at {@code spawn}, answering the marker after the spawn after {@code ticks}:
     * AddActor seeds its history, and it shows there.
     */
    public BedrockEntityPlayback(Vec3 spawn, Window window, long ticks, float width, float height) {
        this.window = window;
        Sample seed = new Sample(spawn.x, spawn.y, spawn.z, UNKNOWN);
        seed.readByTick = ticks + 1;
        seed.readBefore = seed.readByTick * TICK_MILLIS + FRAME_MILLIS;
        seed.added = true;
        samples.add(seed);
        minX = maxX = targetMinX = targetMaxX = spawn.x;
        minY = maxY = targetMinY = targetMaxY = spawn.y;
        minZ = maxZ = targetMinZ = targetMaxZ = spawn.z;
        resize(width, height);
    }

    /** The client may have handled {@code sample} from now on: it has answered the marker before it. */
    public void begin(Sample sample) {
        if (sample.added) return;
        sample.added = true;
        sample.previous = samples.getLast();
        samples.add(sample);
    }

    /** The client has handled {@code sample}: it answered the marker after it after {@code ticks}. */
    public void settle(Sample sample, long ticks) {
        if (!sample.added || sample.read()) return;
        sample.readByTick = ticks + 1;
        sample.readBefore = sample.readByTick * TICK_MILLIS + FRAME_MILLIS;
        Sample previous = sample.previous;
        sample.previous = null;
        sample.gapLow = Math.max(0L, sample.readFrom - previous.readBefore);
        sample.gapHigh = sample.readBefore - previous.readFrom;
        sample.previousGapLow = previous.gapLow;
        sample.previousGapHigh = previous.gapHigh;
    }

    /** Client tick {@code ticks}, after its interpolation step and playback: {@code box} is where it may show the actor. */
    public void tick(long ticks, float width, float height) {
        minX = Math.min(minX + (targetMinX - minX) / 3.0D, targetMinX);
        minY = Math.min(minY + (targetMinY - minY) / 3.0D, targetMinY);
        minZ = Math.min(minZ + (targetMinZ - minZ) / 3.0D, targetMinZ);
        maxX = Math.max(maxX + (targetMaxX - maxX) / 3.0D, targetMaxX);
        maxY = Math.max(maxY + (targetMaxY - maxY) / 3.0D, targetMaxY);
        maxZ = Math.max(maxZ + (targetMaxZ - maxZ) / 3.0D, targetMaxZ);

        long now = ticks * TICK_MILLIS;
        // I ramps toward the gap of the newest sample read, from that of the one before: any sample from the newest
        // certainly read on may be the newest read.
        int newestRead = 0;
        for (int i = samples.size() - 1; i > 0; i--) {
            if (samples.get(i).readByTick <= ticks) {
                newestRead = i;
                break;
            }
        }
        Sample certain = samples.get(newestRead);
        long gapLow = certain.gapLow;
        long gapHigh = certain.gapHigh;
        if (certain.readByTick + RAMP_TICKS > ticks) {
            gapLow = Math.min(gapLow, certain.previousGapLow);
            gapHigh = Math.max(gapHigh, certain.previousGapHigh);
        }
        for (int i = newestRead + 1; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            if (sample.read()) {
                gapLow = Math.min(gapLow, sample.gapLow);
                gapHigh = Math.max(gapHigh, sample.gapHigh);
            } else {
                // Read by now, if at all.
                gapLow = Math.min(gapLow, Math.max(0L, sample.readFrom - sample.previous.readBefore));
                gapHigh = Math.max(gapHigh, now + FRAME_MILLIS - sample.previous.readFrom);
            }
        }
        long earliest = now - delay(gapHigh, false);
        latestPlayback =
                Math.max(latestPlayback, now + FRAME_MILLIS - delay(gapLow, window == Window.MOB_OR_PROJECTILE));

        // A sample the client read by the earliest playback time is the oldest it can still play from.
        while (samples.size() > 1 && samples.get(1).readBefore <= earliest) samples.removeFirst();
        Sample first = samples.getFirst();
        targetMinX = targetMaxX = first.x;
        targetMinY = targetMaxY = first.y;
        targetMinZ = targetMaxZ = first.z;
        for (int i = 1; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            targetMinX = Math.min(targetMinX, sample.x);
            targetMinY = Math.min(targetMinY, sample.y);
            targetMinZ = Math.min(targetMinZ, sample.z);
            targetMaxX = Math.max(targetMaxX, sample.x);
            targetMaxY = Math.max(targetMaxY, sample.y);
            targetMaxZ = Math.max(targetMaxZ, sample.z);
            // The client plays toward a sample read at or after the latest playback time, never past it.
            if (sample.readFrom >= latestPlayback) break;
        }
        resize(width, height);
    }

    /** The box of every position the client may show the actor at, with this size. */
    public void resize(float width, float height) {
        double halfWidth = width / 2.0D;
        box.set(minX - halfWidth, minY, minZ - halfWidth, maxX + halfWidth, maxY + height, maxZ + halfWidth);
    }

    public SimpleCollisionBox box() {
        return box;
    }

    /** Whether the client can only show the actor at {@code location}. */
    public boolean isOnlyAt(SimpleCollisionBox location) {
        return box.minX == location.minX
                && box.minY == location.minY
                && box.minZ == location.minZ
                && box.maxX == location.maxX
                && box.maxY == location.maxY
                && box.maxZ == location.maxZ;
    }

    // D for a gap I: 100 ms times I / 50 clamped to [1, 8] for a mob, 50 ms times the same for a projectile.
    private static long delay(long gap, boolean projectile) {
        return projectile ? Math.clamp(gap, 50L, 400L) : Math.clamp(gap * 2L, 100L, 800L);
    }
}
