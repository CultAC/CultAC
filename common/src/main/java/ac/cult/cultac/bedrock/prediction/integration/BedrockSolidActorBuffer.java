package ac.cult.cultac.bedrock.prediction.integration;

/**
 * How far a move may end from Cult's end along an axis a moving solid actor ended it on: Cult's box for an actor in
 * the middle of its lerp can stand a step off the box the client meets. A pool of {@link #MAX} that refills by
 * {@link #REFILL_PER_TICK} each client tick and lends at most {@link #MAX_PER_TICK} to one tick. A tick is charged
 * what its accepted end used, once: charging the same tick again replaces its charge.
 */
public final class BedrockSolidActorBuffer {
    static final double MAX = 0.20D;
    static final double REFILL_PER_TICK = 0.01D;
    static final double MAX_PER_TICK = 0.05D;

    private long chargedTick = Long.MIN_VALUE;
    // The pool at the start of the charged tick, and what that tick took of it.
    private double startOfChargedTick = MAX;
    private double charged;

    /** What a move of client tick {@code tick} may take. */
    public float available(long tick) {
        return (float) Math.min(MAX_PER_TICK, poolAt(tick));
    }

    /** Charges client tick {@code tick} the {@code amount} its accepted end took, up to what it may take. */
    public void charge(long tick, double amount) {
        if (tick != chargedTick) {
            startOfChargedTick = poolAt(tick);
            chargedTick = tick;
        }
        charged = Math.min(Math.max(0.0D, amount), Math.min(MAX_PER_TICK, startOfChargedTick));
    }

    // The pool at the start of client tick {@code tick}: a later tick refills by the ticks since the charged one, an
    // earlier one (a restarted tick counter) refills nothing.
    private double poolAt(long tick) {
        if (chargedTick == Long.MIN_VALUE) return MAX;
        if (tick == chargedTick) return startOfChargedTick;
        double after = startOfChargedTick - charged;
        if (tick < chargedTick) return after;
        return Math.min(MAX, after + (double) (tick - chargedTick) * REFILL_PER_TICK);
    }
}
