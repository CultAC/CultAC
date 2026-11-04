package ac.cult.cultac.network.routing;

import ac.cult.reload.api.HostContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * The ids taken within the 60 s replay window, within which a message id can arrive again: the first to {@link #claim}
 * an id wins it. Every engine generation on this instance keeps its claims in the host's one shared map under
 * {@value #KEY}, which holds JDK types only, each claimant within its own {@code scope}; without a host the map is this
 * object's own. Any thread.
 */
public final class SharedClaims {
    static final String KEY = "cultac.network.claims";
    static final long SWEEP_PERIOD_MILLIS = 1_000;

    /** Scoped id to the time it was claimed. */
    private final Map<String, Long> claims;

    private final String scope;
    private final LongSupplier clock;
    private volatile long nextSweep = Long.MIN_VALUE;

    @SuppressWarnings("unchecked")
    public SharedClaims(@Nullable HostContext host, String scope, LongSupplier clock) {
        this.claims =
                host == null ? new ConcurrentHashMap<>() : (Map<String, Long>) host.shared(KEY, ConcurrentHashMap::new);
        this.scope = scope + ":";
        this.clock = clock;
    }

    /** Whether this caller is the first of its scope to claim {@code id} within the window. */
    public boolean claim(String id) {
        return claim(id, clock.getAsLong());
    }

    /** {@link #claim(String)} at {@code now}. */
    boolean claim(String id, long now) {
        if (now >= nextSweep) {
            nextSweep = now + SWEEP_PERIOD_MILLIS;
            claims.values().removeIf(claimed -> expired(claimed, now));
        }
        String key = scope + id;
        while (true) {
            Long claimed = claims.putIfAbsent(key, now);
            if (claimed == null) return true;
            if (!expired(claimed, now)) return false;
            if (claims.replace(key, claimed, now)) return true;
        }
    }

    private static boolean expired(long claimed, long now) {
        return now - claimed > EnvelopeCodec.REPLAY_WINDOW_MILLIS;
    }
}
