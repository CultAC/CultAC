package ac.cult.cultac.bedrock.prediction.integration;

/** Whether a timed movement effect runs for the client as it takes each update of it, in arrival order. */
final class BedrockEffectClock {
    private static final long STOPPED = Long.MIN_VALUE;
    // The first tick the effect no longer runs on; STOPPED while it does not run.
    private long ends = STOPPED;

    /**
     * Takes an update that gives the effect {@code duration} ticks from {@code tick} (-1 for no end) while the client's
     * newest tick is {@code now}. Returns whether it starts an effect that was not running or stops one that was.
     */
    boolean switches(long tick, long now, int duration) {
        boolean runs = ends != STOPPED && now < ends;
        long left = duration == -1 ? -1 : Math.max(0, duration - Math.max(0, now - tick));
        ends = left == 0 ? STOPPED : left == -1 ? Long.MAX_VALUE : now + left;
        return left == 0 ? runs : !runs;
    }

    void clear() {
        ends = STOPPED;
    }
}
