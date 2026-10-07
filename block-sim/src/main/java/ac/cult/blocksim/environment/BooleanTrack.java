package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.nbt.NbtJson;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;

/** Received keyframes baked once; boolean arguments use whole clock ticks and step interpolation. */
public final class BooleanTrack {
    private record Frame(int tick, boolean value) { }
    private record Segment(int from, boolean previous, int to, boolean next) { }
    private final BooleanRule.Operation operation;
    private final ClientEasing easing;
    private final Integer period;
    private final List<Segment> segments;

    private BooleanTrack(BooleanRule.Operation operation, ClientEasing easing, Integer period, List<Segment> segments) {
        this.operation = operation; this.easing = easing; this.period = period; this.segments = List.copyOf(segments);
    }
    public static BooleanTrack read(JsonElement definition, Integer period) {
        if (period != null && period <= 0) throw new IllegalArgumentException("Timeline period must be positive");
        var object = definition.getAsJsonObject();
        var operation = object.has("modifier") ? BooleanRule.Operation.read(object.get("modifier").getAsString()) : BooleanRule.Operation.OVERRIDE;
        var easing = ClientEasing.read(object.get("ease"));
        var frames = new ArrayList<Frame>();
        for (var entry : object.getAsJsonArray("keyframes")) {
            var frame = entry.getAsJsonObject();
            int tick = frame.get("ticks").getAsInt();
            if (tick < 0) throw new IllegalArgumentException("Keyframe ticks must be non-negative");
            if (!frames.isEmpty() && tick < frames.getLast().tick()) throw new IllegalArgumentException("Keyframes must be ordered");
            if (period != null && tick > period) throw new IllegalArgumentException("Keyframe outside timeline period");
            frames.add(new Frame(tick, NbtJson.booleanValue(frame.get("value"))));
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("Track has no keyframes");
        // The received codec starts its repeat counter at the final tick, and resets to zero
        // on a different tick. Preserve the valid repeated-frame sequences it accepts.
        int previousTick = frames.getLast().tick(), repeats = 0;
        for (var frame : frames) {
            repeats = frame.tick() == previousTick ? repeats + 1 : 0;
            if (repeats > 2) throw new IllegalArgumentException("Too many keyframes at tick " + frame.tick());
            previousTick = frame.tick();
        }
        var segments = new ArrayList<Segment>();
        var first = frames.getFirst(); var last = frames.getLast();
        if (frames.size() == 1) segments.add(new Segment(0, first.value(), 0, first.value()));
        else {
            if (period != null) segments.add(new Segment(last.tick() - period, last.value(), first.tick(), first.value()));
            for (int index = 1; index < frames.size(); index++) {
                var previous = frames.get(index - 1); var next = frames.get(index);
                segments.add(new Segment(previous.tick(), previous.value(), next.tick(), next.value()));
            }
            if (period != null) segments.add(new Segment(last.tick(), last.value(), first.tick() + period, first.value()));
        }
        return new BooleanTrack(operation, easing, period, segments);
    }
    public boolean apply(long ticks, boolean base) {
        long time = period == null ? ticks : Math.floorMod(ticks, period);
        var segment = segments.getLast();
        for (var candidate : segments) if (time < candidate.to()) { segment = candidate; break; }
        boolean argument;
        if (time <= segment.from()) argument = segment.previous();
        else if (time >= segment.to()) argument = segment.next();
        else {
            float progress = (float) (time - segment.from()) / (segment.to() - segment.from());
            argument = easing.apply(progress) >= 1.0F ? segment.next() : segment.previous();
        }
        return operation.apply(base, argument);
    }
}
