package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;

/** One actor generation's value-only history. All access belongs to its movement loop. */
public final class BedrockActorHistory {
    public static final int CAPACITY = 40;
    private final ArrayList<Frame> frames = new ArrayList<>(CAPACITY);
    private long sequence;
    // The ticks whose following frame an update changed: the next replay starts after the oldest of them.
    private final TreeSet<Long> marks = new TreeSet<>();
    // The frame that last left the history: a replay marked on it starts at the oldest frame still held.
    private Frame departed;
    private BedrockReplayEvent.Metadata metadataReceiptStart;
    private BedrockReplayEvent.Metadata metadataReceipt;

    public long newestTick() {
        return frames.isEmpty() ? -1 : frames.getLast().tick();
    }

    public long oldestTick() {
        return frames.isEmpty() ? -1 : frames.getFirst().tick();
    }

    public List<Frame> frames() {
        return List.copyOf(frames);
    }

    public List<Entry> current() {
        return frames.isEmpty() ? List.of() : frames.getLast().end();
    }

    public void clear() {
        frames.clear();
        sequence = 0;
        marks.clear();
        departed = null;
        metadataReceiptStart = null;
        metadataReceipt = null;
    }

    public BedrockActorHistory copy() {
        var copy = new BedrockActorHistory();
        copy.frames.addAll(frames);
        copy.sequence = sequence;
        copy.marks.addAll(marks);
        copy.departed = departed;
        copy.metadataReceiptStart = metadataReceiptStart;
        copy.metadataReceipt = metadataReceipt;
        return copy;
    }

    boolean matches(long tick, BedrockReplayEvent event) {
        return frames.stream()
                .filter(frame -> frame.tick() == tick)
                .findFirst()
                .map(frame -> frame.end().stream().allMatch(entry -> event.matchesHistory(entry.state())))
                .orElse(false);
    }

    public BedrockReplayEvent.Metadata metadata(long tick, BedrockReplayEvent.Metadata incoming) {
        return metadata(tick, incoming, 0);
    }

    public BedrockReplayEvent.Metadata metadata(long tick, BedrockReplayEvent.Metadata incoming, int flagWords) {
        long comparisonTick =
                tick != 0 && !frames.isEmpty() && Long.compareUnsigned(tick, oldestTick()) < 0 ? oldestTick() : tick;
        var historical = tick == 0
                ? null
                : frames.stream()
                        .filter(frame -> frame.tick() == comparisonTick)
                        .findFirst()
                        .orElse(null);
        var effective = historical == null
                ? incoming
                : new BedrockReplayEvent.Metadata(
                        changedDimension(incoming.width(), historical, true),
                        changedDimension(incoming.height(), historical, false),
                        changed(incoming.gliding(), historical, BedrockMovementState::gliding),
                        changed(incoming.crawling(), historical, BedrockMovementState::horizontalPose),
                        changed(incoming.swimming(), historical, BedrockMovementState::swimming),
                        changed(incoming.spinning(), historical, BedrockMovementState::riptideSpinActive),
                        changed(incoming.sprinting(), historical, BedrockMovementState::sprinting),
                        incoming.scaledDefinition(),
                        incoming.climberFlags());
        if (!frames.isEmpty()) {
            // An untimed size takes effect at its receipt, which the next frame records.
            var retained = new BedrockReplayEvent.Metadata(
                    tick == 0 ? null : effective.width(),
                    tick == 0 ? null : effective.height(),
                    effective.gliding(),
                    effective.crawling(),
                    effective.swimming(),
                    effective.spinning(),
                    effective.sprinting(),
                    tick == 0 ? null : effective.scaledDefinition());
            if (retained.hasValues()) {
                retainMetadata(historical == null ? newestTick() : comparisonTick, retained);
            }
            if (historical != null
                    && (retained.hasValues()
                            || historical.end().stream()
                                    .anyMatch(entry -> otherFlagWordDiffers(entry.state(), flagWords)))) {
                mark(comparisonTick);
            }
            Frame latest = frames.getLast();
            if (effective.hasValues() && metadataReceiptStart == null) {
                metadataReceiptStart = BedrockReplayEvent.Metadata.trackedValuesOf(
                        latest.end().getFirst().state());
            }
            var end = latest.end().stream()
                    .map(entry -> entry.withState(effective.state(entry.state())))
                    .toList();
            if (effective.hasValues()) {
                metadataReceipt = BedrockReplayEvent.Metadata.trackedValuesOf(
                                end.getFirst().state())
                        .changedValues(metadataReceiptStart);
            }
            frames.set(frames.size() - 1, latest.withEnd(latest.beforeEvents(), end, latest.events()));
        }
        return effective;
    }

    private static boolean otherFlagWordDiffers(BedrockMovementState state, int words) {
        boolean lower = state.sprinting()
                || state.gliding()
                || state.swimming()
                || state.inputFrame().sneaking()
                || state.itemUseSlowdownActive()
                || state.riptideSpinActive();
        boolean upper = state.horizontalPose();
        return (words & 2) != 0 && lower || (words & 1) != 0 && upper;
    }

    private void retainMetadata(long tick, BedrockReplayEvent.Metadata effective) {
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.tick() != tick) continue;
            var events = new ArrayList<>(frame.events());
            events.add(new Event(
                    ++sequence, tick, frame.end().getFirst().state().simulationTick(), newestTick(), effective));
            frames.set(i, frame.withEnd(frame.beforeEvents(), frame.end(), events));
            return;
        }
    }

    private static Boolean changed(
            Boolean value, Frame historical, java.util.function.Predicate<BedrockMovementState> flag) {
        return value != null && historical.end().stream().allMatch(entry -> flag.test(entry.state()) == value)
                ? null
                : value;
    }

    private static Float changedDimension(Float value, Frame historical, boolean width) {
        return value != null
                        && historical.end().stream().allMatch(entry -> {
                            var dimensions = BedrockReplayEvent.Metadata.metadataDimensions(entry.state());
                            return value == (float) (width ? dimensions.width() : dimensions.height());
                        })
                ? null
                : value;
    }

    public void ordinary(BedrockReplayEvent event) {
        if (frames.isEmpty()) return;
        Frame latest = frames.getLast();
        var events = new ArrayList<>(latest.events());
        events.add(new Event(
                ++sequence, latest.tick(), latest.end().getFirst().state().simulationTick(), latest.tick(), event));
        var end = latest.end().stream()
                .map(entry -> entry.withState(event.state(entry.state())))
                .toList();
        frames.set(frames.size() - 1, latest.withEnd(latest.beforeEvents(), end, events));
    }

    public void record(Frame frame) {
        if (frame.tick() <= newestTick()) return;
        // Missing inputs cannot be manufactured into movement ticks.
        if (!frames.isEmpty() && frame.tick() != newestTick() + 1) clear();
        if (metadataReceipt != null && metadataReceipt.hasValues()) frame = frame.withReceivedMetadata(metadataReceipt);
        metadataReceiptStart = null;
        metadataReceipt = null;
        if (frames.size() == CAPACITY) departed = frames.removeFirst();
        frames.add(frame);
        // A mark lasts as long as the frame after its tick is held.
        marks.headSet(oldestTick() - 1).clear();
    }

    /** The frame of {@code tick}, or null when the history does not hold it. */
    Frame frame(long tick) {
        int index = index(tick);
        return index < 0 ? null : frames.get(index);
    }

    private int index(long tick) {
        if (frames.isEmpty()) return -1;
        long index = tick - oldestTick();
        return index < 0 || index >= frames.size() ? -1 : (int) index;
    }

    /** Whether an update has marked a frame since the last replay. */
    boolean marked() {
        return !marks.isEmpty();
    }

    /** Marks the frame after {@code tick}: the next replay starts no later than it. */
    void mark(long tick) {
        if (index(tick) >= 0) marks.add(tick);
    }

    /**
     * Records {@code event} as taken after the frame of {@code tick} and marks the frame that follows. The frames
     * after it keep the states they were recorded with until a replay runs them again. Returns whether the history
     * holds that frame.
     */
    boolean attach(long tick, BedrockReplayEvent event) {
        int anchor = index(tick);
        if (anchor < 0) return false;
        Frame original = frames.get(anchor);
        var events = new ArrayList<>(original.events());
        events.add(new Event(
                ++sequence, original.tick(), original.end().getFirst().state().simulationTick(), newestTick(), event));
        frames.set(
                anchor,
                original.withEnd(original.beforeEvents(), applyEvents(original.beforeEvents(), events), events));
        marks.add(tick);
        return true;
    }

    /**
     * Runs every frame after the oldest marked one again, each with the updates recorded on it, and clears the marks
     * of the frames it ran. Returns the states the newest frame then ends with: the current ones when nothing is
     * marked.
     */
    List<Entry> replay(
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> world,
            BedrockReplayTick.Actors actors) {
        if (marks.isEmpty()) return current();
        long start = marks.first();
        // A mark on the newest frame is for the frame that will follow it, which the client marks once that frame
        // exists: after this replay, so the next one still starts there.
        marks.headSet(newestTick()).clear();
        if (start >= newestTick()) return current();
        int anchor = index(start);
        Frame first = anchor >= 0 ? frames.get(anchor) : departed != null && departed.tick() == start ? departed : null;
        if (first == null) return current();
        List<Entry> states = applyEvents(withUntimedMotion(first.beforeEvents(), anchor + 1), first.events());
        var active = new ArrayList<Event>();
        if (anchor < 0) active.addAll(first.events());
        else frames.set(anchor, first.withEnd(first.beforeEvents(), states, first.events()));
        active.addAll(eventsBefore(start + 1));
        for (int i = anchor + 1; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            List<Entry> before = BedrockReplayTick.advance(frame, states, active, world, actors);
            states = applyEvents(before, frame.events());
            active.addAll(frame.events());
            frames.set(i, frame.withEnd(before, states, frame.events()));
        }
        return List.copyOf(states);
    }

    /**
     * The states a replay starts from. An untimed motion changes the velocity the newest frame ended with, so the frame
     * it arrived after keeps it when a replay starts there, before that frame's own updates; a replay from an earlier
     * frame runs that frame's end again without it.
     */
    private List<Entry> withUntimedMotion(List<Entry> anchorEnd, int next) {
        if (next < 0 || next >= frames.size()) return anchorEnd;
        Vec3d motion = frames.get(next).imposedVelocity();
        if (motion == null) return anchorEnd;
        return anchorEnd.stream()
                .map(entry -> entry.withState(entry.state()
                        .withVelocityAndCollisionFlags(motion, entry.state().collisionFlags())))
                .toList();
    }

    /**
     * As {@link #replay(BiFunction, BedrockReplayTick.Actors)}, for a replay the client ran with one of several sets
     * of solid actors, {@code variants}. Each runs from the same frames, and the history keeps the one whose end the
     * client's next tick, which reported {@code position} and {@code velocity}, started from: the first of those
     * that fit it equally.
     */
    List<Entry> replay(
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> world,
            List<BedrockReplayTick.Actors> variants,
            Vec3d position,
            Vec3d velocity) {
        if (variants.size() == 1 || position == null || marks.isEmpty()) return replay(world, variants.getFirst());
        var others = new ArrayList<BedrockActorHistory>();
        for (int i = 1; i < variants.size(); i++) others.add(copy());
        List<Entry> ends = replay(world, variants.getFirst());
        double least = distance(ends, position, velocity);
        BedrockActorHistory taken = null;
        for (int i = 1; i < variants.size(); i++) {
            BedrockActorHistory other = others.get(i - 1);
            List<Entry> otherEnds = other.replay(world, variants.get(i));
            double distance = distance(otherEnds, position, velocity);
            if (!(distance < least - 1.0E-6D)) continue;
            least = distance;
            taken = other;
            ends = otherEnds;
        }
        if (taken != null) {
            frames.clear();
            frames.addAll(taken.frames);
            sequence = taken.sequence;
            departed = taken.departed;
        }
        return ends;
    }

    /**
     * How far a tick that reported {@code position} and {@code velocity} is from having started at one of
     * {@code ends}. Along an axis it reports moving on, it moved by about that velocity; along one it reports none
     * on, it was stopped somewhere within the move its start velocity gives.
     */
    private static double distance(List<Entry> ends, Vec3d position, Vec3d velocity) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Entry end : ends) {
            Vec3d moved = position.subtract(end.state().physicalFeetPosition());
            Vec3d start = end.state().velocity();
            nearest = Math.min(
                    nearest,
                    distance(moved.x(), velocity == null ? 0 : velocity.x(), start.x())
                            + distance(moved.y(), velocity == null ? 0 : velocity.y(), start.y())
                            + distance(moved.z(), velocity == null ? 0 : velocity.z(), start.z()));
        }
        return nearest;
    }

    private static double distance(double moved, double reported, double start) {
        if (reported != 0.0D) return Math.abs(moved - reported);
        return Math.max(0.0D, Math.max(Math.min(0.0D, start) - moved, moved - Math.max(0.0D, start)));
    }

    public void advance(
            Frame frame, BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> world) {
        if (frames.isEmpty() || frame.tick() != newestTick() + 1) {
            record(frame);
            return;
        }
        List<Entry> end = BedrockReplayTick.advance(frame, current(), eventsBefore(frame.tick()), world);
        record(frame.withEnd(end, end, frame.events()));
    }

    /** Records {@code event} after the frame of {@code tick} and replays from the oldest marked frame. */
    public List<Entry> apply(
            long tick,
            BedrockReplayEvent event,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> world) {
        if (index(tick) < 0) return List.of();
        if (matches(tick, event)) return current();
        attach(tick, event);
        return replay(world, (frame, state, snapshot) -> snapshot);
    }

    private List<Event> eventsBefore(long tick) {
        return frames.stream()
                .filter(frame -> frame.tick() < tick)
                .flatMap(frame -> frame.events().stream())
                .toList();
    }

    private static List<Entry> applyEvents(List<Entry> states, List<Event> events) {
        for (Event event : events)
            states = states.stream()
                    .map(entry -> entry.withState(event.value().replayState(entry.state())))
                    .distinct()
                    .toList();
        return states;
    }

    public record Frame(
            long tick,
            BedrockSimulation.Input input,
            List<Entry> beforeEvents,
            List<Entry> end,
            Vec3d imposedVelocity,
            Vec3d addedVelocity,
            Vec3d externalDisplacement,
            Vec3d observedPosition,
            Vec3d observedVelocity,
            List<Event> events,
            BedrockReplayEvent.Metadata receivedMetadata,
            WorldCollisionBox collisionFetchBox,
            BedrockSolidActors.Met solidActors) {
        public Frame(
                long tick,
                BedrockSimulation.Input input,
                List<Entry> beforeEvents,
                List<Entry> end,
                Vec3d imposedVelocity,
                Vec3d addedVelocity,
                Vec3d externalDisplacement,
                Vec3d observedPosition,
                Vec3d observedVelocity,
                List<Event> events,
                BedrockReplayEvent.Metadata receivedMetadata,
                WorldCollisionBox collisionFetchBox) {
            this(
                    tick,
                    input,
                    beforeEvents,
                    end,
                    imposedVelocity,
                    addedVelocity,
                    externalDisplacement,
                    observedPosition,
                    observedVelocity,
                    events,
                    receivedMetadata,
                    collisionFetchBox,
                    null);
        }

        public Frame(
                long tick,
                BedrockSimulation.Input input,
                List<Entry> beforeEvents,
                List<Entry> end,
                Vec3d imposedVelocity,
                Vec3d addedVelocity,
                Vec3d externalDisplacement,
                Vec3d observedPosition,
                Vec3d observedVelocity,
                List<Event> events) {
            this(
                    tick,
                    input,
                    beforeEvents,
                    end,
                    imposedVelocity,
                    addedVelocity,
                    externalDisplacement,
                    observedPosition,
                    observedVelocity,
                    events,
                    null,
                    null);
        }

        public Frame(
                long tick,
                BedrockSimulation.Input input,
                List<Entry> beforeEvents,
                List<Entry> end,
                Vec3d imposedVelocity,
                Vec3d addedVelocity,
                Vec3d externalDisplacement,
                Vec3d observedPosition,
                Vec3d observedVelocity,
                List<Event> events,
                WorldCollisionBox collisionFetchBox) {
            this(
                    tick,
                    input,
                    beforeEvents,
                    end,
                    imposedVelocity,
                    addedVelocity,
                    externalDisplacement,
                    observedPosition,
                    observedVelocity,
                    events,
                    null,
                    collisionFetchBox);
        }

        public Frame {
            input = BedrockReplaySnapshot.withState(input, BedrockReplaySnapshot.detach(input.previousState()));
            beforeEvents = detach(beforeEvents);
            end = detach(end);
            events = List.copyOf(events);
            if (end.isEmpty()) throw new IllegalArgumentException("A movement frame needs a continuation");
        }

        private static List<Entry> detach(List<Entry> entries) {
            return entries.stream()
                    .map(entry -> entry.withState(BedrockReplaySnapshot.detach(entry.state())))
                    .toList();
        }

        Frame withEnd(List<Entry> before, List<Entry> end, List<Event> events) {
            return new Frame(
                    tick,
                    input,
                    before,
                    end,
                    imposedVelocity,
                    addedVelocity,
                    externalDisplacement,
                    observedPosition,
                    observedVelocity,
                    events,
                    receivedMetadata,
                    collisionFetchBox,
                    solidActors);
        }

        Frame withReceivedMetadata(BedrockReplayEvent.Metadata metadata) {
            return new Frame(
                    tick,
                    input,
                    beforeEvents,
                    end,
                    imposedVelocity,
                    addedVelocity,
                    externalDisplacement,
                    observedPosition,
                    observedVelocity,
                    events,
                    metadata,
                    collisionFetchBox,
                    solidActors);
        }
    }

    public record Event(long sequence, long tick, long simulationTick, long throughTick, BedrockReplayEvent value) {}
}
