package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Where a Bedrock client shows one remote actor. The client lerps toward each move's target in floats in the
 * coordinate frame, {@code pos += (target - pos) / n} for n = 3, 2, 1, once per client tick.
 *
 * <p>A move sits between two latency markers, and the client handles it some time between its answers to them, as a
 * Java move between its two pings. {@link #begin} adds the states in which the client handled it, each
 * {@link #tick} adds those in which it handles it at the next tick, and {@link #settle} drops the states that had not
 * handled it. The entity's position is the state that handled the most moves; reach also accepts every other state.
 *
 * <p>Actors the client draws by time-based playback instead (mobs from 26.10 to 26.52, projectiles and physics
 * actors from 26.60) are not modelled exactly: reach also accepts every position sent within the playback delay.
 */
public final class BedrockEntityInterpolation {
    /** A move's target; {@code packetPosition} is the wire position, {@code offset} above the actor's feet. */
    public record Target(Vec3 packetPosition, float yaw, float pitch, float offset, boolean forceCompletion) {
        private Vec3 feet() {
            return new Vec3(packetPosition.x, packetPosition.y - offset, packetPosition.z);
        }
    }

    /** One move as sent; a player's client snaps to a teleport at least half a block away. */
    public record Move(Target target, boolean teleport) {}

    private static final int STEPS = 3;
    private static final double SNAP_DISTANCE_SQUARED = 0.25D;
    // A bound on the states a long run of unanswered markers can open; markers follow every flush.
    private static final int MAX_STATES = 32;
    // The longest delay a client plays an actor back by: twice 50 ms, scaled by at most 8.
    private static final long PLAYBACK_LIMIT_NANOS = 800_000_000L;

    private final boolean snapsTeleports;
    private final boolean playsBack;
    private final ArrayList<State> states = new ArrayList<>(2);
    private final ArrayList<PendingMove> pending = new ArrayList<>(2);
    private final ArrayList<Sample> samples = new ArrayList<>();
    private final SimpleCollisionBox spread = new SimpleCollisionBox();
    private ReachInterpolationData spreadLocation;

    private record PendingMove(long sequence, Move move) {}

    private record Sample(Vec3 feet, long answeredNanos) {}

    /** One client state: its shown transform, its lerp and the last move it handled. */
    private static final class State {
        private Vec3 feet;
        private float yaw;
        private float pitch;
        private Target target;
        private int remaining;
        private Target queued;
        private int queuedSteps;
        private long handled;

        private State(Vec3 feet, float yaw, float pitch, long handled) {
            this.feet = feet;
            this.yaw = yaw;
            this.pitch = pitch;
            this.handled = handled;
        }

        private State copy() {
            State copy = new State(feet, yaw, pitch, handled);
            copy.target = target;
            copy.remaining = remaining;
            copy.queued = queued;
            copy.queuedSteps = queuedSteps;
            return copy;
        }

        private boolean sameAs(State other) {
            return handled == other.handled
                    && feet.equals(other.feet)
                    && Float.compare(yaw, other.yaw) == 0
                    && Float.compare(pitch, other.pitch) == 0
                    && remaining == other.remaining
                    && queuedSteps == other.queuedSteps
                    && Objects.equals(target, other.target)
                    && Objects.equals(queued, other.queued);
        }
    }

    /** The interpolation of an actor the client shows at its current position. */
    public BedrockEntityInterpolation(PacketEntity entity, boolean snapsTeleports, boolean playsBack) {
        this.snapsTeleports = snapsTeleports;
        this.playsBack = playsBack;
        Vec3 shown = entity.clientPhysicalPosition != null ? entity.clientPhysicalPosition : entity.desyncClientPos;
        states.add(
                new State(shown != null ? shown : Vec3.ZERO, entity.clientPhysicalYaw, entity.clientPhysicalPitch, 0));
    }

    /** The client may have handled move {@code sequence} from now on. */
    public void begin(CultPlayer player, PacketEntity entity, long sequence, Move move) {
        if (!addPending(sequence, move)) return;
        branch(player, entity);
        write(player, entity);
    }

    /** The client has handled move {@code sequence}, and every move before it. */
    public void settle(CultPlayer player, PacketEntity entity, long sequence, Move move) {
        long now = System.nanoTime();
        // Playback starts from where the actor stood before its first sample.
        if (playsBack && samples.isEmpty()) samples.add(new Sample(entity.clientPhysicalPosition, now));
        addPending(sequence, move);
        for (int i = 0; i < states.size(); i++) {
            State state = states.get(i);
            if (state.handled >= sequence) continue;
            for (PendingMove begun : pending) {
                if (begun.sequence > sequence) break;
                if (begun.sequence > state.handled) handle(player, entity, state, begun);
            }
        }
        pending.removeIf(begun -> begun.sequence <= sequence);
        distinct();
        if (playsBack) samples.add(new Sample(move.target().feet(), now));
        write(player, entity);
    }

    /** One client tick: every state lerps once, and the client may handle a begun move before the next. */
    public void tick(CultPlayer player, PacketEntity entity) {
        BedrockCoordinateFrame coordinates = player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame();
        float yawOffset = entity.isBoat() ? 90.0F : 0.0F;
        for (State state : states) step(coordinates, yawOffset, state);
        branch(player, entity);
        write(player, entity);
    }

    /** Whether no state moves, no move is pending and no playback sample can still be shown. */
    public boolean idle() {
        return pending.isEmpty()
                && samples.isEmpty()
                && states.size() == 1
                && states.getFirst().remaining == 0
                && states.getFirst().queued == null;
    }

    // Keeps the begun moves in packet order; false when the move is already begun.
    private boolean addPending(long sequence, Move move) {
        int at = 0;
        while (at < pending.size() && pending.get(at).sequence < sequence) at++;
        if (at < pending.size() && pending.get(at).sequence == sequence) return false;
        pending.add(at, new PendingMove(sequence, move));
        return true;
    }

    // Every state that has not handled every begun move may handle any further prefix of them now.
    private void branch(CultPlayer player, PacketEntity entity) {
        int existing = states.size();
        for (int i = 0; i < existing; i++) {
            State chained = states.get(i);
            for (PendingMove begun : pending) {
                if (begun.sequence <= chained.handled) continue;
                chained = chained.copy();
                handle(player, entity, chained, begun);
                states.add(chained);
            }
        }
        distinct();
    }

    private void handle(CultPlayer player, PacketEntity entity, State state, PendingMove begun) {
        Target next = begun.move.target();
        state.handled = begun.sequence;
        if (snapsTeleports && begun.move.teleport() && state.feet.distanceToSqr(next.feet()) >= SNAP_DISTANCE_SQUARED) {
            state.feet = next.feet();
            state.yaw = next.yaw();
            state.pitch = next.pitch();
            state.target = next;
            state.remaining = 0;
            state.queued = null;
            return;
        }
        // A forced interpolation completes before a newer ordinary target can begin.
        if (state.target != null && state.target.forceCompletion() && state.remaining != 0 && !next.forceCompletion()) {
            state.queued = next;
            state.queuedSteps = STEPS;
        } else {
            state.target = next;
            state.remaining = STEPS;
            state.queued = null;
        }
    }

    private static void step(BedrockCoordinateFrame coordinates, float yawOffset, State state) {
        if (state.queued != null) {
            if (state.remaining == 0) {
                state.target = state.queued;
                state.remaining = state.queuedSteps;
                state.queued = null;
            } else state.queuedSteps = Math.max(1, state.queuedSteps - 1);
        }
        if (state.remaining == 0) return;
        Target target = state.target;
        Vec3 from = coordinates.toLocal(state.feet);
        Vec3 to = coordinates.toLocal(target.packetPosition());
        float offset = target.offset();
        int remaining = state.remaining;
        float x = lerp(remaining, (float) from.x, (float) to.x);
        float y = lerp(remaining, (float) from.y + offset, (float) to.y) - offset;
        float z = lerp(remaining, (float) from.z, (float) to.z);
        state.feet = coordinates.toWorld(new Vec3(x, y, z));
        state.yaw = angle(remaining, state.yaw + yawOffset, target.yaw()) - yawOffset;
        state.pitch = angle(remaining, state.pitch, target.pitch());
        state.remaining--;
    }

    private static float lerp(int remaining, float from, float to) {
        return remaining == 1 ? to : from + (to - from) * (1.0F / remaining);
    }

    private static float angle(int remaining, float from, float to) {
        return remaining == 1 ? to : from + CultMath.wrapDegrees(to - from) * (1.0F / remaining);
    }

    private void distinct() {
        for (int i = states.size() - 1; i > 0; i--) {
            State state = states.get(i);
            for (int j = 0; j < i; j++) {
                if (states.get(j).sameAs(state)) {
                    states.remove(i);
                    break;
                }
            }
        }
        // Keep the states that handled the most moves; markers after every flush keep this far from the bound.
        while (states.size() > MAX_STATES) {
            int oldest = 0;
            for (int i = 1; i < states.size(); i++) {
                if (states.get(i).handled < states.get(oldest).handled) oldest = i;
            }
            states.remove(oldest);
        }
    }

    // The entity shows the state that handled the most moves, furthest along its lerp; reach also accepts the others
    // and the playback samples.
    private void write(CultPlayer player, PacketEntity entity) {
        State shown = states.getFirst();
        for (State state : states) {
            if (state.handled > shown.handled || state.handled == shown.handled && state.remaining < shown.remaining)
                shown = state;
        }
        SimpleCollisionBox shownBox = box(player, entity, shown.feet);
        entity.setPositionRaw(shownBox, shown.yaw, shown.pitch);
        boolean spreads = states.size() > 1;
        spread.set(shownBox);
        for (State state : states) {
            if (state != shown) spread.union(box(player, entity, state.feet));
        }
        if (playsBack && !samples.isEmpty()) {
            long shownFrom =
                    System.nanoTime() - PLAYBACK_LIMIT_NANOS - Math.max(0, player.getTransactionPing()) * 1_000_000L;
            int recent = 0;
            while (recent < samples.size() && samples.get(recent).answeredNanos < shownFrom) recent++;
            if (recent == samples.size()) {
                // Playback has reached the last sample, where every state's lerp ends too.
                samples.clear();
            } else {
                // The client interpolates between the two samples around its playback time: keep one older sample.
                samples.subList(0, Math.max(0, recent - 1)).clear();
                for (Sample sample : samples) spread.union(box(player, entity, sample.feet));
                spreads = true;
            }
        }
        if (!spreads) {
            entity.oldPacketLocation = null;
            return;
        }
        if (spreadLocation == null || !spreadLocation.resetFinished(spread)) {
            spreadLocation = new ReachInterpolationData(spread);
        }
        entity.oldPacketLocation = spreadLocation;
    }

    private static SimpleCollisionBox box(CultPlayer player, PacketEntity entity, Vec3 feet) {
        return GetBoundingBox.getPacketEntityBoundingBox(player, feet.x, feet.y, feet.z, entity);
    }
}
