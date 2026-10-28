package ac.cult.cultac.network.routing;

import ac.cult.reload.api.HostContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Lets exactly one engine generation on this instance act on a message every generation receives: the first to
 * {@link #claim} an id wins it for the 60 s replay window, within which a message id can arrive again. The claims are
 * the host's shared object under {@code cultac.network.claims.<name>}, which holds JDK types only; without a host they
 * are this object's own. Any thread.
 */
public final class SharedClaims {
    private static final long SWEEP_PERIOD_MILLIS = 1_000;

    /** Claimed id to the time it was claimed. */
    private final Map<String, Long> claims;

    private final LongSupplier clock;
    private volatile long nextSweep = Long.MIN_VALUE;

    @SuppressWarnings("unchecked")
    public SharedClaims(@Nullable HostContext host, String name, LongSupplier clock) {
        this.claims = host == null
                ? new ConcurrentHashMap<>()
                : (Map<String, Long>) host.shared("cultac.network.claims." + name, ConcurrentHashMap::new);
        this.clock = clock;
    }

    /** Whether this caller is the first to claim {@code id} within the window. */
    public boolean claim(String id) {
        long now = clock.getAsLong();
        if (now >= nextSweep) {
            nextSweep = now + SWEEP_PERIOD_MILLIS;
            claims.values().removeIf(claimed -> expired(claimed, now));
        }
        Long claimed = claims.putIfAbsent(id, now);
        return claimed == null || expired(claimed, now) && claims.replace(id, claimed, now);
    }

    private static boolean expired(long claimed, long now) {
        return now - claimed > EnvelopeCodec.REPLAY_WINDOW_MILLIS;
    }
}
