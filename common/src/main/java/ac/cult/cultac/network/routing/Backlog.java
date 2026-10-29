package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogThrottle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bound on the items waiting for one worker: at most {@code maxItems} items of {@code maxBytes} bytes in all. An
 * item that does not fit is dropped; the drops are logged at most once a minute, with how many there were. Every
 * {@link #admit} that returns true is paired with one {@link #release} of the same size. Any thread.
 */
final class Backlog {
    private final String dropped;
    private final int maxItems;
    private final long maxBytes;
    private final LogThrottle throttle;
    private final AtomicInteger items = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();

    Backlog(String dropped, int maxItems, long maxBytes) {
        this(dropped, maxItems, maxBytes, new LogThrottle());
    }

    /** {@code dropped} is the warning logged for a drop. */
    Backlog(String dropped, int maxItems, long maxBytes, LogThrottle throttle) {
        this.dropped = dropped;
        this.maxItems = maxItems;
        this.maxBytes = maxBytes;
        this.throttle = throttle;
    }

    /** Holds a place for an item of {@code size} bytes; false, and the drop counted, when it does not fit. */
    boolean admit(long size) {
        if (items.incrementAndGet() <= maxItems) {
            if (bytes.addAndGet(size) <= maxBytes) return true;
            bytes.addAndGet(-size);
        }
        items.decrementAndGet();
        throttle.warn("dropped", dropped, null);
        return false;
    }

    /** Gives back the place {@link #admit} held for an item of {@code size} bytes. */
    void release(long size) {
        bytes.addAndGet(-size);
        items.decrementAndGet();
    }
}
