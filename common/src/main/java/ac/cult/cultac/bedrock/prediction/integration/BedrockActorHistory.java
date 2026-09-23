package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** One actor generation's value-only history. All access belongs to its movement loop. */
public final class BedrockActorHistory {
    public static final int CAPACITY = 40;
    private final ArrayList<Frame> frames = new ArrayList<>(CAPACITY);
    private long sequence;
    private Frame pendingMetadataFrame;
    private BedrockReplayEvent.Metadata metadataReceiptStart;
    private BedrockReplayEvent.Metadata metadataReceipt;

    public long newestTick() { return frames.isEmpty() ? -1 : frames.getLast().tick(); }
    public long oldestTick() { return frames.isEmpty() ? -1 : frames.getFirst().tick(); }
    public List<Frame> frames() { return List.copyOf(frames); }
    public List<Entry> current() { return frames.isEmpty() ? List.of() : frames.getLast().end(); }
    public void clear() {
        frames.clear(); sequence = 0; pendingMetadataFrame = null;
        metadataReceiptStart = null; metadataReceipt = null;
    }

    public BedrockActorHistory copy() {
        var copy = new BedrockActorHistory();
        copy.frames.addAll(frames);
        copy.sequence = sequence;
        copy.pendingMetadataFrame = pendingMetadataFrame;
        copy.metadataReceiptStart = metadataReceiptStart;
        copy.metadataReceipt = metadataReceipt;
        return copy;
    }

    boolean matches(long tick, BedrockReplayEvent event) {
        return frames.stream().filter(frame -> frame.tick() == tick).findFirst()
                .map(frame -> frame.end().stream().allMatch(entry -> event.matchesHistory(entry.state())))
                .orElse(false);
    }

    public BedrockReplayEvent.Metadata metadata(long tick, BedrockReplayEvent.Metadata incoming) {
        return metadata(tick, incoming, 0);
    }

    public BedrockReplayEvent.Metadata metadata(long tick, BedrockReplayEvent.Metadata incoming, int flagWords) {
        long comparisonTick = tick != 0 && !frames.isEmpty() && Long.compareUnsigned(tick, oldestTick()) < 0
                ? oldestTick() : tick;
        var historical = tick == 0 ? null : frames.stream().filter(frame -> frame.tick() == comparisonTick)
                .findFirst().orElse(null);
        var effective = historical == null ? incoming : new BedrockReplayEvent.Metadata(
                incoming.width(), incoming.height(),
                changed(incoming.gliding(), historical, BedrockMovementState::gliding),
                changed(incoming.crawling(), historical, BedrockMovementState::horizontalPose),
                changed(incoming.swimming(), historical, BedrockMovementState::swimming),
                changed(incoming.spinning(), historical, BedrockMovementState::riptideSpinActive),
                changed(incoming.sprinting(), historical, BedrockMovementState::sprinting));
        if (!frames.isEmpty()) {
            var flags = new BedrockReplayEvent.Metadata(null, null, effective.gliding(),
                    effective.crawling(), effective.swimming(), effective.spinning(), effective.sprinting());
            boolean changedFlags = flags.hasFlags();
            if (changedFlags) {
                retainMetadata(historical == null ? newestTick() : comparisonTick, flags);
            }
            if (historical != null && (changedFlags || historical.end().stream()
                    .anyMatch(entry -> otherFlagWordDiffers(entry.state(), flagWords)))) {
                Frame marked = frames.stream().filter(frame -> frame.tick() == comparisonTick).findFirst().orElseThrow();
                if (pendingMetadataFrame == null || marked.tick() <= pendingMetadataFrame.tick()) {
                    pendingMetadataFrame = marked;
                }
            }
            Frame latest = frames.getLast();
            if (changedFlags && metadataReceiptStart == null) {
                metadataReceiptStart = BedrockReplayEvent.Metadata.flagsOf(latest.end().getFirst().state());
            }
            var end = latest.end().stream().map(entry -> entry.withState(effective.state(entry.state()))).toList();
            if (changedFlags) {
                metadataReceipt = BedrockReplayEvent.Metadata.flagsOf(end.getFirst().state())
                        .changedFlags(metadataReceiptStart);
            }
            frames.set(frames.size() - 1, latest.withEnd(latest.beforeEvents(), end, latest.events()));
        }
        return effective;
    }

    private static boolean otherFlagWordDiffers(BedrockMovementState state, int words) {
        boolean lower = state.sprinting() || state.gliding() || state.swimming()
                || state.inputFrame().sneaking() || state.itemUseSlowdownActive() || state.riptideSpinActive();
        boolean upper = state.horizontalPose();
        return (words & 2) != 0 && lower || (words & 1) != 0 && upper;
    }

    private void retainMetadata(long tick, BedrockReplayEvent.Metadata effective) {
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.tick() != tick) continue;
            var events = new ArrayList<>(frame.events());
            events.add(new Event(++sequence, tick, frame.end().getFirst().state().simulationTick(),
                    newestTick(), effective));
            frames.set(i, frame.withEnd(frame.beforeEvents(), frame.end(), events));
            return;
        }
    }

    private static Boolean changed(Boolean value, Frame historical,
                                   java.util.function.Predicate<BedrockMovementState> flag) {
        return value != null && historical.end().stream().allMatch(entry -> flag.test(entry.state()) == value)
                ? null : value;
    }

    public void ordinary(BedrockReplayEvent event) {
        if (frames.isEmpty()) return;
        Frame latest = frames.getLast();
        var events = new ArrayList<>(latest.events());
        events.add(new Event(++sequence, latest.tick(), latest.end().getFirst().state().simulationTick(),
                latest.tick(), event));
        var end = latest.end().stream().map(entry -> entry.withState(event.state(entry.state()))).toList();
        frames.set(frames.size() - 1, latest.withEnd(latest.beforeEvents(), end, events));
    }

    public void record(Frame frame) {
        if (frame.tick() <= newestTick()) return;
        // Missing inputs cannot be manufactured into movement ticks.
        if (!frames.isEmpty() && frame.tick() != newestTick() + 1) clear();
        if (metadataReceipt != null && metadataReceipt.hasFlags()) frame = frame.withReceivedFlags(metadataReceipt);
        metadataReceiptStart = null;
        metadataReceipt = null;
        if (frames.size() == CAPACITY) frames.removeFirst();
        frames.add(frame);
        if (pendingMetadataFrame != null && pendingMetadataFrame.tick() + 1 < oldestTick()) {
            pendingMetadataFrame = null;
        }
    }

    public void advance(Frame frame, BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> world) {
        if (frames.isEmpty() || frame.tick() != newestTick() + 1) {
            record(frame);
            return;
        }
        List<Entry> end = BedrockReplayTick.advance(frame, current(), eventsBefore(frame.tick()), world);
        record(frame.withEnd(end, end, frame.events()));
    }

    /** The anchor is an end-of-tick state; replay starts at its following frame. */
    public List<Entry> apply(long tick, BedrockReplayEvent event,
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> world) {
        if (frames.isEmpty()) return List.of();
        int anchor = -1;
        for (int i = 0; i < frames.size(); i++) if (frames.get(i).tick() == tick) { anchor = i; break; }
        if (anchor < 0) return List.of();
        if (matches(tick, event)) return current();
        Frame original = frames.get(anchor);
        var events = new ArrayList<>(original.events());
        events.add(new Event(++sequence, original.tick(), original.end().getFirst().state().simulationTick(), newestTick(), event));
        List<Entry> states = applyEvents(original.beforeEvents(), events);
        frames.set(anchor, original.withEnd(original.beforeEvents(), states, events));
        var active = new ArrayList<Event>();
        if (pendingMetadataFrame != null && pendingMetadataFrame.tick() < original.tick()) {
            long start = pendingMetadataFrame.tick();
            anchor = -1;
            for (int i = 0; i < frames.size(); i++) if (frames.get(i).tick() == start) { anchor = i; break; }
            Frame first = anchor < 0 ? pendingMetadataFrame : frames.get(anchor);
            states = applyEvents(first.beforeEvents(), first.events());
            if (anchor < 0) active.addAll(first.events());
            else frames.set(anchor, first.withEnd(first.beforeEvents(), states, first.events()));
            active.addAll(eventsBefore(start + 1));
        } else {
            active.addAll(eventsBefore(original.tick() + 1));
        }
        for (int i = anchor + 1; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            List<Entry> before = BedrockReplayTick.advance(frame, states, active, world);
            states = applyEvents(before, frame.events());
            active.addAll(frame.events());
            frames.set(i, frame.withEnd(before, states, frame.events()));
        }
        pendingMetadataFrame = null;
        return List.copyOf(states);
    }

    public long elapsedActorTicks(long tick) {
        if (frames.isEmpty()) return 0;
        long anchor = Long.compareUnsigned(tick, oldestTick()) < 0 ? oldestTick() : tick;
        return frames.stream().filter(frame -> frame.tick() == anchor).findFirst()
                .map(frame -> current().getFirst().state().simulationTick() - frame.end().getFirst().state().simulationTick())
                .orElse(0L);
    }

    private List<Event> eventsBefore(long tick) {
        return frames.stream().filter(frame -> frame.tick() < tick).flatMap(frame -> frame.events().stream()).toList();
    }

    private static List<Entry> applyEvents(List<Entry> states, List<Event> events) {
        for (Event event : events) states = states.stream().map(entry -> entry.withState(event.value().state(entry.state())))
                .distinct().toList();
        return states;
    }

    public record Frame(long tick, BedrockSimulation.Input input, List<Entry> beforeEvents, List<Entry> end,
                        Vec3d imposedVelocity, Vec3d addedVelocity, Vec3d externalDisplacement, Vec3d observedPosition,
                        Vec3d observedVelocity, List<Event> events, BedrockReplayEvent.Metadata receivedFlags) {
        public Frame(long tick, BedrockSimulation.Input input, List<Entry> beforeEvents, List<Entry> end,
                     Vec3d imposedVelocity, Vec3d addedVelocity, Vec3d externalDisplacement, Vec3d observedPosition,
                     Vec3d observedVelocity, List<Event> events) {
            this(tick, input, beforeEvents, end, imposedVelocity, addedVelocity, externalDisplacement,
                    observedPosition, observedVelocity, events, null);
        }
        public Frame {
            input = BedrockReplaySnapshot.withState(input, BedrockReplaySnapshot.detach(input.previousState()));
            beforeEvents = detach(beforeEvents);
            end = detach(end);
            events = List.copyOf(events);
            if (end.isEmpty()) throw new IllegalArgumentException("A movement frame needs a continuation");
        }
        private static List<Entry> detach(List<Entry> entries) {
            return entries.stream().map(entry -> entry.withState(BedrockReplaySnapshot.detach(entry.state()))).toList();
        }
        Frame withEnd(List<Entry> before, List<Entry> end, List<Event> events) {
            return new Frame(tick, input, before, end, imposedVelocity, addedVelocity, externalDisplacement,
                    observedPosition, observedVelocity, events, receivedFlags);
        }
        Frame withReceivedFlags(BedrockReplayEvent.Metadata flags) {
            return new Frame(tick, input, beforeEvents, end, imposedVelocity, addedVelocity, externalDisplacement,
                    observedPosition, observedVelocity, events, flags);
        }
    }
    public record Event(long sequence, long tick, long simulationTick, long throughTick, BedrockReplayEvent value) { }
}
