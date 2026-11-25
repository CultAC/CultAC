package ac.cult.cultac.utils.anticheat;

import java.util.concurrent.TimeUnit;

/**
 * Sets back a Java client whose ticks arrive more than max-pending-ping-ms after a ping it has not answered. A vanilla
 * client answers a ping before its next tick and its packets arrive in order, so such a tick means the round trip
 * between them took longer than the bound. After a setback the bound waits until the client has answered through
 * it, its oldest unanswered ping has since been younger than the bound, and the bound has passed since the setback.
 */
public final class PendingPingBound {
    private boolean fired;
    private long firedAt;
    private int setbackPing;
    private boolean answeredThrough;
    private boolean belowSince;

    /**
     * The age in ms of the oldest unanswered ping, owed since {@code owedSince} (0 for none), when a client tick
     * arrived at {@code lastClientTick} more than the bound after it and the bound is armed; else -1.
     */
    public long overdueMs(long now, long owedSince, long lastClientTick, int boundMs) {
        if (boundMs <= 0 || fired || owedSince == 0) return -1;
        long bound = TimeUnit.MILLISECONDS.toNanos(boundMs);
        if (now - owedSince <= bound || lastClientTick - owedSince <= bound) return -1;
        return TimeUnit.NANOSECONDS.toMillis(now - owedSince);
    }

    /** A setback at {@code now}, accepted once the client answers {@code setbackPing}. */
    public void fired(long now, int setbackPing) {
        fired = true;
        firedAt = now;
        this.setbackPing = setbackPing;
        answeredThrough = false;
        belowSince = false;
    }

    /** Whether {@link #advance} with the same arguments changes this bound. */
    public boolean advances(long now, long owedSince, int answered, int boundMs) {
        if (!fired) return false;
        if (!answeredThrough) return answered >= setbackPing;
        if (!belowSince) return below(now, owedSince, boundMs);
        return now - firedAt >= TimeUnit.MILLISECONDS.toNanos(boundMs);
    }

    /** Takes the client's answers through {@code answered} and its oldest unanswered ping, owed since {@code owedSince}. */
    public void advance(long now, long owedSince, int answered, int boundMs) {
        if (!fired) return;
        if (!answeredThrough && answered >= setbackPing) answeredThrough = true;
        if (answeredThrough && !belowSince && below(now, owedSince, boundMs)) belowSince = true;
        if (belowSince && now - firedAt >= TimeUnit.MILLISECONDS.toNanos(boundMs)) fired = false;
    }

    private static boolean below(long now, long owedSince, int boundMs) {
        return owedSince == 0 || now - owedSince <= TimeUnit.MILLISECONDS.toNanos(boundMs);
    }
}
