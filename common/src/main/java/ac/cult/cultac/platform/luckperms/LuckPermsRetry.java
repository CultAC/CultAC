package ac.cult.cultac.platform.luckperms;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Hooks LuckPerms once its API is up, for a runtime that can load before LuckPerms has enabled: now, then every
 * {@link #RETRY_SECONDS} seconds {@link #RETRIES} times, then once more at the next player join. {@code attempt}
 * answers whether it hooked; it runs under this object's lock, so it never runs after {@link #stop}.
 */
public final class LuckPermsRetry {
    static final long RETRY_SECONDS = 5;
    static final int RETRIES = 12;

    /** Runs a task after a delay, answering what cancels it. */
    @FunctionalInterface
    public interface Delays {
        Runnable schedule(Runnable task, long seconds);
    }

    private final BooleanSupplier attempt;
    private final Delays delays;
    private final Consumer<Runnable> nextJoin;
    private final Consumer<String> gaveUp;

    private int retries;
    private boolean done;
    private @Nullable Runnable cancel;

    public LuckPermsRetry(
            BooleanSupplier attempt, Delays delays, Consumer<Runnable> nextJoin, Consumer<String> gaveUp) {
        this.attempt = attempt;
        this.delays = delays;
        this.nextJoin = nextJoin;
        this.gaveUp = gaveUp;
    }

    public synchronized void start() {
        if (done) return;
        if (attempt.getAsBoolean()) {
            done = true;
        } else if (retries < RETRIES) {
            retries++;
            cancel = delays.schedule(this::start, RETRY_SECONDS);
        } else {
            cancel = null;
            nextJoin.accept(this::last);
        }
    }

    private synchronized void last() {
        if (done) return;
        done = true;
        if (!attempt.getAsBoolean())
            gaveUp.accept("LuckPerms is installed but its API never came up; permission changes are not tracked");
    }

    public synchronized void stop() {
        done = true;
        Runnable pending = cancel;
        cancel = null;
        if (pending != null) pending.run();
    }
}
