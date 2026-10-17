package ac.cult.cultac.checks.impl.prediction;

/** Receives a copy of each of a player's Java predictions from their {@link SuperDebug}. */
public interface PredictionObserver {
    /** The seq of the tick the running prediction belongs to; on the player's packet thread. */
    long pendingSeq();

    /**
     * A prediction of tick {@code seq}, copied on the packet thread that started at {@code startNanos}. {@code state}
     * and {@code grid} are null for a tick with a trace only.
     */
    void capture(long seq, double offset, String trace, String state, BlockGrid grid, long startNanos);

    /** A capture failed on the packet thread; the prediction it copied is unaffected. */
    void failed(RuntimeException failure);
}
