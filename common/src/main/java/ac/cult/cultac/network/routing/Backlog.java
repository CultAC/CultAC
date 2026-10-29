package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogThrottle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bound on the items waiting for one worker: at most {@code maxItems} items of {@code maxBytes} bytes in all. An
 * item that does not fit is dropped; the drops are logged at most once a minute, with how many there were. Every
 * {@link #admit} that returns true is paired with one {@link #release} of the same size. Any thread.
 */
public final class Backlog {
    /** The usual bound on the messages waiting to publish. */
    public static final int MAX_ITEMS = 4096;
    /** The usual bound on the size of the messages waiting to publish. */
    public static final long MAX_BYTES = 16L * 1024 * 1024;

    private final String dropped;
    private final int maxItems;
    private final long maxBytes;
    private final LogThrottle throttle;
    private final AtomicInteger items = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();

    public Backlog(String dropped, int maxItems, long maxBytes) {
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
    public boolean admit(long size) {
        if (items.incrementAndGet() <= maxItems) {
            if (bytes.addAndGet(size) <= maxBytes) return true;
            bytes.addAndGet(-size);
        }
        items.decrementAndGet();
        throttle.warn("dropped", dropped, null);
        return false;
    }

    /** Gives back the place {@link #admit} held for an item of {@code size} bytes. */
    public void release(long size) {
        bytes.addAndGet(-size);
        items.decrementAndGet();
    }
}
