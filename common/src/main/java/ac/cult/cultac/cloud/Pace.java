package ac.cult.cultac.cloud;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * At most {@link #max} takes per key in each window of {@code windowMillis}, counted from the first take after the last
 * window ended; every key's count starts over with the window. Thread-safe.
 *
 * @param <K> what takes are counted by
 */
public final class Pace<K> {
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<K, Integer> counts = new HashMap<>();

    private volatile int max;
    private long windowStart;
    private boolean started;

    /** {@code clock} in milliseconds. */
    public Pace(long windowMillis, int max, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.max = max;
        this.clock = clock;
    }

    /** Whether {@code key} may take one more in this window; it has, when true. */
    public synchronized boolean take(K key) {
        long now = clock.getAsLong();
        if (!started || now - windowStart >= windowMillis) {
            started = true;
            windowStart = now;
            counts.clear();
        }
        int count = counts.getOrDefault(key, 0);
        if (count >= max) return false;
        counts.put(key, count + 1);
        return true;
    }

    /** Gives back one of {@code key}'s takes in this window, when it has one. */
    public synchronized void giveBack(K key) {
        counts.computeIfPresent(key, (k, count) -> count > 1 ? count - 1 : null);
    }

    public int max() {
        return max;
    }

    /** Sets the most takes per key and window, from the next take on. */
    public void max(int max) {
        this.max = max;
    }
}
