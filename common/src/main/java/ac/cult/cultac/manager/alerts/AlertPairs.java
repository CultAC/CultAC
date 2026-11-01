package ac.cult.cultac.manager.alerts;

import ac.cult.reload.api.HostContext;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * The staff who already got one message of each verbose and alert pair received lately, so that nobody gets both. A
 * pair is remembered for {@value #WINDOW_MILLIS} ms after its first message, in an object every engine generation on
 * the instance shares. Any thread.
 */
public final class AlertPairs {
    static final String SHARED_KEY = "cultac.alerts.pairs";
    static final long WINDOW_MILLIS = 30_000;

    private static final long SWEEP_PERIOD_MILLIS = 1_000;

    /** Pair id to when it was first seen and the native players that got one of its messages; JDK types only. */
    private final Map<String, SimpleImmutableEntry<Long, Set<Object>>> pairs;

    private final LongSupplier clock;
    private volatile long nextSweep = Long.MIN_VALUE;

    @SuppressWarnings("unchecked")
    public AlertPairs(@Nullable HostContext host, LongSupplier clock) {
        this.pairs = host == null
                ? new ConcurrentHashMap<>()
                : (Map<String, SimpleImmutableEntry<Long, Set<Object>>>)
                        host.shared(SHARED_KEY, ConcurrentHashMap::new);
        this.clock = clock;
    }

    /** Whether {@code nativePlayer} may take a message of {@code pair}: true once per player and pair. */
    public boolean take(String pair, Object nativePlayer) {
        long now = clock.getAsLong();
        if (now >= nextSweep) {
            nextSweep = now + SWEEP_PERIOD_MILLIS;
            pairs.values().removeIf(entry -> expired(entry, now));
        }
        return pairs.compute(
                        pair,
                        (key, entry) -> entry == null || expired(entry, now)
                                ? new SimpleImmutableEntry<>(now, newSet())
                                : entry)
                .getValue()
                .add(nativePlayer);
    }

    private static boolean expired(SimpleImmutableEntry<Long, Set<Object>> entry, long now) {
        return now - entry.getKey() > WINDOW_MILLIS;
    }

    private static Set<Object> newSet() {
        return Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    }
}
