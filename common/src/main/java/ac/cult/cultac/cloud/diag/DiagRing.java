package ac.cult.cultac.cloud.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A watched player's last ticks, oldest first: each tick's trace, flags and, while the byte budget allows, its full
 * SuperDebug text, either compressed or as the recorded state it is printed from once something asks for it. Past
 * {@code maxTicks} the oldest tick goes; past {@code maxBytes} the oldest recorded states of ended ticks go first, then
 * the oldest full texts, then the newest tick's state, which waits for its tick end, then the oldest ticks. Only the
 * diagnostics thread touches it.
 */
final class DiagRing {
    /** What a tick costs besides its strings and arrays. */
    static final int TICK_OVERHEAD = 64;
    /** A tick's joined traces at most; a trace that would pass it is left out. */
    static final int MAX_TRACE_CHARS = 2048;

    static final class Tick {
        final long seq;
        double offset;
        String trace;
        List<String> flags = List.of();
        byte[] full;
        /** The full text's state, not yet printed; {@link #stateBytes} is what it holds of the heap at most. */
        DiagWatch.Raw state;

        long stateBytes;

        Tick(long seq, double offset, String trace, byte[] full) {
            this.seq = seq;
            this.offset = offset;
            this.trace = trace;
            this.full = full;
        }

        long bytes() {
            long bytes = TICK_OVERHEAD + trace.length() + stateBytes;
            for (String flag : flags) bytes += flag.length() + 16;
            return full == null ? bytes : bytes + full.length;
        }
    }

    private final ArrayDeque<Tick> ticks = new ArrayDeque<>();
    private final int maxTicks;
    private final long maxBytes;
    private long bytes;
    /** The held states' share of {@link #bytes}. */
    private long heldBytes;
    /** The newest seq evicted for room or age; {@link Long#MIN_VALUE} while none has been. */
    private long evictedThrough = Long.MIN_VALUE;

    DiagRing(int maxTicks, long maxBytes) {
        this.maxTicks = maxTicks;
        this.maxBytes = maxBytes;
    }

    /**
     * Appends a tick's prediction. A second prediction in the same tick joins the first: traces joined up to
     * {@link #MAX_TRACE_CHARS}, the newest full text and the larger offset kept.
     */
    void add(long seq, double offset, String trace, byte[] full) {
        add(seq, offset, trace, full, null, 0);
    }

    /** As {@link #add(long, double, String, byte[])}, the full text held as {@code state} until it is printed. */
    void add(long seq, double offset, String trace, byte[] full, DiagWatch.Raw state, long stateBytes) {
        Tick last = ticks.peekLast();
        if (last != null && last.seq == seq) {
            bytes -= last.bytes();
            if (last.trace.isEmpty()) last.trace = trace;
            else if (last.trace.length() + 3 + trace.length() <= MAX_TRACE_CHARS) last.trace += " | " + trace;
            if (full != null || state != null) {
                heldBytes += stateBytes - last.stateBytes;
                last.full = full;
                last.state = state;
                last.stateBytes = stateBytes;
            }
            if (Double.isNaN(last.offset) || !Double.isNaN(offset) && offset > last.offset) last.offset = offset;
            bytes += last.bytes();
        } else {
            Tick tick = new Tick(seq, offset, trace, full);
            tick.state = state;
            tick.stateBytes = stateBytes;
            ticks.addLast(tick);
            bytes += tick.bytes();
            heldBytes += stateBytes;
        }
        trim();
    }

    /**
     * Whether a new tick holding {@code stateBytes} fits once the ended ticks' states are gone, every full text kept
     * and its trace joined up to {@link #MAX_TRACE_CHARS}.
     */
    boolean fits(String trace, long stateBytes) {
        int traceChars = Math.max(trace.length(), MAX_TRACE_CHARS);
        return bytes - heldBytes + TICK_OVERHEAD + traceChars + stateBytes <= maxBytes;
    }

    /** Whether the newest tick is {@code seq} and flagged. */
    boolean flagged(long seq) {
        Tick last = ticks.peekLast();
        return last != null && last.seq == seq && !last.flags.isEmpty();
    }

    /** Tick {@code seq}'s state printed into {@code full}, or dropped when {@code full} is null; the caller trims. */
    void rendered(Tick tick, byte[] full) {
        bytes -= tick.bytes();
        heldBytes -= tick.stateBytes;
        tick.full = full;
        tick.state = null;
        tick.stateBytes = 0;
        bytes += tick.bytes();
    }

    /** The checks that flagged tick {@code seq}; a tick without a prediction gets an entry of its own. */
    void flags(long seq, List<String> flags) {
        if (flags.isEmpty()) return;
        Tick found = find(seq);
        if (found == null) {
            Tick last = ticks.peekLast();
            if (last != null && last.seq > seq) return;
            found = new Tick(seq, Double.NaN, "", null);
            ticks.addLast(found);
            bytes += found.bytes();
        }
        bytes -= found.bytes();
        found.flags = List.copyOf(flags);
        bytes += found.bytes();
        trim();
    }

    /** Tick {@code seq}; null when the ring holds none. */
    Tick find(long seq) {
        for (Iterator<Tick> newest = ticks.descendingIterator(); newest.hasNext(); ) {
            Tick tick = newest.next();
            if (tick.seq == seq) return tick;
            if (tick.seq < seq) return null;
        }
        return null;
    }

    void trim() {
        while (ticks.size() > maxTicks) evictOldest();
        if (bytes <= maxBytes) return;
        Tick newest = ticks.peekLast();
        for (Tick tick : ticks) {
            if (bytes <= maxBytes) return;
            if (tick.state != null && tick != newest) drop(tick);
        }
        for (Tick tick : ticks) {
            if (bytes <= maxBytes) return;
            if (tick.full == null) continue;
            bytes -= tick.full.length;
            tick.full = null;
        }
        if (bytes > maxBytes && newest != null && newest.state != null) drop(newest);
        while (bytes > maxBytes && !ticks.isEmpty()) evictOldest();
    }

    private void drop(Tick tick) {
        bytes -= tick.stateBytes;
        heldBytes -= tick.stateBytes;
        tick.state = null;
        tick.stateBytes = 0;
    }

    private void evictOldest() {
        Tick oldest = ticks.pollFirst();
        bytes -= oldest.bytes();
        heldBytes -= oldest.stateBytes;
        evictedThrough = Math.max(evictedThrough, oldest.seq);
    }

    /** The ticks from {@code from} to {@code to}, oldest first. */
    List<Tick> range(long from, long to) {
        List<Tick> range = new ArrayList<>();
        for (Tick tick : ticks) {
            if (tick.seq > to) break;
            if (tick.seq >= from) range.add(tick);
        }
        return range;
    }

    /** The newest seq held; {@link Long#MIN_VALUE} when empty. */
    long newest() {
        Tick last = ticks.peekLast();
        return last == null ? Long.MIN_VALUE : last.seq;
    }

    long evictedThrough() {
        return evictedThrough;
    }

    long bytes() {
        return bytes;
    }

    int size() {
        return ticks.size();
    }
}
