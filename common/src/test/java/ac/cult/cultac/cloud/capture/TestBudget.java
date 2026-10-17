package ac.cult.cultac.cloud.capture;

import java.util.concurrent.atomic.AtomicLong;

/** A shared budget for retained frames, as the capture keeps one, for sinks in tests. */
final class TestBudget {
    /** The process's budget for frames held before JOIN or its answer. */
    static final long MAX_HELD_BYTES = 64L * 1024 * 1024;

    private final long limit;
    private final AtomicLong used = new AtomicLong();

    TestBudget(long limit) {
        this.limit = limit;
    }

    boolean take(long bytes) {
        if (used.addAndGet(bytes) <= limit) return true;
        used.addAndGet(-bytes);
        return false;
    }

    void give(long bytes) {
        used.addAndGet(-bytes);
    }

    long remaining() {
        return limit - used.get();
    }
}
