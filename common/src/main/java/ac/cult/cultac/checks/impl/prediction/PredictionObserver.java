package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.utils.debug.DebugText;

/** Receives a copy of each of a player's Java predictions from their {@link SuperDebug}. */
public interface PredictionObserver {
    /** The seq of the tick the running prediction belongs to; on the player's packet thread. */
    long pendingSeq();

    /**
     * A prediction of tick {@code seq}, copied on the packet thread that started at {@code startNanos}. {@code state}
     * and {@code grid} are null for a tick with a trace only.
     */
    void capture(long seq, double offset, String trace, String state, BlockGrid grid, long startNanos);

    /**
     * As {@link #capture(long, double, String, String, BlockGrid, long)}, with {@code state} kept as the values it
     * prints, which print the same on any thread at any time; this prints it now.
     */
    default void captureText(long seq, double offset, String trace, DebugText state, BlockGrid grid, long startNanos) {
        capture(seq, offset, trace, state == null ? null : state.toString(), grid, startNanos);
    }

    /** A capture failed on the packet thread; the prediction it copied is unaffected. */
    void failed(RuntimeException failure);
}
