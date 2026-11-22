package ac.cult.cultac.cloud.diag;

import java.util.ArrayDeque;

/**
 * The encoded parts waiting for the socket, at most {@link #MAX_BYTES}, and the token bucket that paces them. The
 * diagnostics thread offers; the connection's sender takes, never faster than the bucket allows. Neither blocks.
 */
public final class DiagUpload {
    public static final long MAX_BYTES = 1024 * 1024;
    /** Room past {@link #MAX_BYTES} for closing parts, which are small. */
    static final long CLOSING_BYTES = 64 * 1024;

    private record Part(long generation, byte[] record) {}

    private ArrayDeque<Part> parts;
    private long bytes;
    private int bytesPerSecond = Limits.OFF.uploadBytesPerSecond();
    private double tokens;
    private long refilledNanos = Long.MIN_VALUE;

    /** Queues a part for the socket of {@code generation}; false, with nothing queued, when it does not fit. */
    boolean offer(long generation, byte[] record) {
        return queue(generation, record, MAX_BYTES);
    }

    /**
     * As {@link #offer}, up to {@link #CLOSING_BYTES} past the cap: a bundle's small closing part, so the service learns
     * why the rest is missing. False, with nothing queued, past that too.
     */
    boolean force(long generation, byte[] record) {
        return queue(generation, record, MAX_BYTES + CLOSING_BYTES);
    }

    private synchronized boolean queue(long generation, byte[] record, long cap) {
        if (bytes + record.length > cap) return false;
        if (parts == null) parts = new ArrayDeque<>();
        parts.addLast(new Part(generation, record));
        bytes += record.length;
        return true;
    }

    synchronized void rate(int bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
    }

    /** Whether anything waits. */
    public synchronized boolean pending() {
        return parts != null && !parts.isEmpty();
    }

    /**
     * The next part for the socket of {@code generation} when the bucket holds enough tokens for it at {@code nanos};
     * null otherwise. Parts of other sockets are dropped.
     */
    public synchronized byte[] poll(long generation, long nanos) {
        if (parts == null) return null;
        Part head;
        while ((head = parts.peekFirst()) != null && head.generation() != generation) {
            parts.pollFirst();
            bytes -= head.record().length;
        }
        if (head == null) return null;
        double capacity = Math.max(bytesPerSecond, Limits.MAX_PART_BYTES + 1024);
        if (refilledNanos == Long.MIN_VALUE) tokens = capacity;
        else tokens = Math.min(capacity, tokens + (nanos - refilledNanos) * 1e-9 * bytesPerSecond);
        refilledNanos = nanos;
        if (tokens < head.record().length) return null;
        tokens -= head.record().length;
        parts.pollFirst();
        bytes -= head.record().length;
        return head.record();
    }

    /** Drops everything waiting. */
    public synchronized void clear() {
        parts = null;
        bytes = 0;
    }

    synchronized long bytes() {
        return bytes;
    }
}
