package ac.cult.cultac.cloud.transport;

/**
 * When to wake a quiet socket and when to give it up: quiet for the given time, it is pinged; quiet as long again after
 * the ping, it is dead once it has answered a ping before. One that never has is pinged again and given up after
 * {@link #NEVER_ANSWERED_PERIODS} quiet periods. Anything heard from the other side counts as heard.
 */
final class SocketLiveness {
    enum Action {
        NONE,
        PING,
        DEAD
    }

    /** Quiet periods a socket that never answered a ping is kept through. */
    static final int NEVER_ANSWERED_PERIODS = 3;

    private final long quietNanos;
    private long heardNanos;
    private long pingedNanos;
    private boolean pinging;
    private boolean answersPings;
    /** Pings in a row that went unanswered for a quiet period. */
    private int unanswered;

    SocketLiveness(long quietNanos) {
        this.quietNanos = quietNanos;
    }

    /** A new socket: heard now, and not yet known to answer pings. */
    synchronized void opened(long nanos) {
        heard(nanos);
        answersPings = false;
    }

    synchronized long heardNanos() {
        return heardNanos;
    }

    synchronized void heard(long nanos) {
        heardNanos = nanos;
        pinging = false;
        unanswered = 0;
    }

    synchronized void pong(long nanos) {
        heard(nanos);
        answersPings = true;
    }

    synchronized Action check(long nanos) {
        if (pinging) {
            if (nanos - pingedNanos <= quietNanos) return Action.NONE;
            if (answersPings || ++unanswered >= NEVER_ANSWERED_PERIODS - 1) return Action.DEAD;
            pinging = false;
        }
        if (nanos - heardNanos <= quietNanos) return Action.NONE;
        pinging = true;
        pingedNanos = nanos;
        return Action.PING;
    }
}
