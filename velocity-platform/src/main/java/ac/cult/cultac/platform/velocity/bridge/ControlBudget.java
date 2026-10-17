package ac.cult.cultac.platform.velocity.bridge;

import java.util.concurrent.TimeUnit;

/** A token bucket over the control frames one kind of sender may send through one bridge session; loop-confined. */
final class ControlBudget {
    /** Frames admitted in one burst. */
    static final int BURST = 32;

    /** One more frame is admitted per this many nanoseconds, up to {@link #BURST}. */
    static final long REFILL_NANOS = TimeUnit.MILLISECONDS.toNanos(125);

    private int tokens = BURST;
    private long refilledNanos;

    ControlBudget(long nowNanos) {
        refilledNanos = nowNanos;
    }

    /** Takes one token; false when the burst is spent. */
    boolean take(long nowNanos) {
        if (tokens >= BURST) {
            refilledNanos = nowNanos;
        } else {
            long refill = (nowNanos - refilledNanos) / REFILL_NANOS;
            if (refill > 0) {
                tokens = (int) Math.min(BURST, tokens + refill);
                refilledNanos += refill * REFILL_NANOS;
            }
        }
        if (tokens == 0) return false;
        tokens--;
        return true;
    }
}
