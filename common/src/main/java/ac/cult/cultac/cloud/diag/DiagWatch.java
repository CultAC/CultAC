package ac.cult.cultac.cloud.diag;

import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import ac.cult.cultac.checks.impl.prediction.PredictionObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The service watches one player: each of their predictions is copied on their packet thread and handed to the
 * diagnostics thread, which keeps it in the player's ring until the lease ends. Nothing here writes to the player's
 * connection or changes what the checks see.
 */
public final class DiagWatch implements PredictionObserver {
    /** One prediction as its packet thread copied it: the full text less the block grid, printed on the diag thread. */
    record Raw(DiagWatch watch, long seq, double offset, String trace, String state, BlockGrid grid, long nanos) {}

    /** A tick ended: the checks that flagged during it. */
    record TickEnd(DiagWatch watch, long seq, List<String> flags) {}

    final Diagnostics owner;
    final UUID player;
    final long generation;
    final String history;
    final int ringTicks;
    final long ringBytes;
    private final LongSupplier pendingSeq;
    private final Consumer<DiagWatch> detach;

    volatile long leaseEndNanos;
    volatile boolean ended;
    /** Why the watch ended: lease-ended, left, failed. */
    volatile String endReason;
    /** The seqs of the last {@link #DROPS} predictions and tick ends the diag thread's queue had no room for. */
    private final AtomicLongArray drops = new AtomicLongArray(DROPS);

    private final AtomicInteger dropCount = new AtomicInteger();
    static final int DROPS = 32;

    // Diagnostics thread only.
    DiagRing ring;
    long firstSeq = Long.MIN_VALUE;
    final long[] captureNanos = new long[Diagnostics.CAPTURE_WINDOW];
    int captures;

    DiagWatch(
            Diagnostics owner,
            UUID player,
            long generation,
            long leaseEndNanos,
            LongSupplier pendingSeq,
            String history,
            Limits limits,
            Consumer<DiagWatch> detach) {
        this.owner = owner;
        this.player = player;
        this.generation = generation;
        this.leaseEndNanos = leaseEndNanos;
        this.pendingSeq = pendingSeq;
        this.history = history;
        this.ringTicks = limits.ringTicks();
        this.ringBytes = limits.playerRingBytes();
        this.detach = detach;
    }

    public UUID player() {
        return player;
    }

    public boolean ended() {
        return ended;
    }

    /**
     * The seq of the frame the running prediction goes out in: the frame cut at the next tick end. On the player's
     * packet thread, before that tick end.
     */
    @Override
    public long pendingSeq() {
        return pendingSeq.getAsLong();
    }

    /**
     * A prediction of tick {@code seq}, copied on the packet thread that started at {@code startNanos}. {@code state}
     * and {@code grid} are null for a tick with a trace only.
     */
    @Override
    public void capture(long seq, double offset, String trace, String state, BlockGrid grid, long startNanos) {
        if (ended) return;
        if (!owner.offer(new Raw(this, seq, offset, trace, state, grid, System.nanoTime() - startNanos))) dropped(seq);
    }

    /** Tick {@code seq} ended with these checks' flags; on the player's packet thread. */
    public void tickEnd(long seq, List<String> flags) {
        if (ended || flags.isEmpty()) return;
        if (!owner.offer(new TickEnd(this, seq, flags))) dropped(seq);
    }

    private void dropped(long seq) {
        drops.set(dropCount.getAndIncrement() & (DROPS - 1), seq);
    }

    /**
     * Whether something of ticks {@code from..to} was dropped for want of queue room. The last {@link #DROPS} drops are
     * known; a range reaching back before them counts as having lost some once more were dropped.
     */
    boolean droppedWithin(long from, long to) {
        int count = dropCount.get();
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < Math.min(count, DROPS); i++) {
            long seq = drops.get(i);
            if (seq >= from && seq <= to) return true;
            oldest = Math.min(oldest, seq);
        }
        return count > DROPS && from < oldest;
    }

    /** A capture failed on the packet thread; the prediction it copied is unaffected. */
    @Override
    public void failed(RuntimeException failure) {
        owner.failed(failure);
    }

    /** Called once when the watch ends, from whichever thread ends it. */
    void detach() {
        detach.accept(this);
    }
}
