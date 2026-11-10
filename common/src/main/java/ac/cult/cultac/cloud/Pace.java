package ac.cult.cultac.cloud;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * At most {@link #max} takes per key in each window of {@code windowMillis}, counted from the first take after the last
 * window ended; every key's count starts over with the window. A take given back counts again only in its own window.
 * Thread-safe.
 *
 * @param <K> what takes are counted by
 */
public final class Pace<K> {
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<K, Integer> counts = new HashMap<>();

    private volatile int max;
    private long windowStart;
    /** How many windows have begun; 0 before the first take. */
    private long window;

    /** A pace on {@link System#nanoTime}. */
    public Pace(long windowMillis, int max) {
        this(windowMillis, max, () -> Math.floorDiv(System.nanoTime(), 1_000_000L));
    }

    /** {@code clock} in milliseconds, never going back. */
    public Pace(long windowMillis, int max, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.max = max;
        this.clock = clock;
    }

    /**
     * {@code key}'s take in this window, as what gives it back, once, while that window lasts; null when {@code key}
     * has {@link #max} takes in it already.
     */
    public synchronized @Nullable Runnable take(K key) {
        long now = clock.getAsLong();
        if (window == 0 || now - windowStart >= windowMillis) {
            window++;
            windowStart = now;
            counts.clear();
        }
        int count = counts.getOrDefault(key, 0);
        if (count >= max) return null;
        counts.put(key, count + 1);
        long taken = window;
        AtomicBoolean given = new AtomicBoolean();
        return () -> {
            if (given.compareAndSet(false, true)) giveBack(key, taken);
        };
    }

    private synchronized void giveBack(K key, long taken) {
        if (taken == window) counts.computeIfPresent(key, (k, count) -> count > 1 ? count - 1 : null);
    }

    public int max() {
        return max;
    }

    /** Sets the most takes per key and window, from the next take on. */
    public void max(int max) {
        this.max = max;
    }
}
