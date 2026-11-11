package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.cloud.wire.IntakeWire;
import java.util.ArrayList;
import java.util.List;

/**
 * The check flags and setbacks since the last tick end, for that tick's VERDICT: at most {@link IntakeWire#MAX_FLAGS}
 * flags, {@link IntakeWire#MAX_VERBOSE_FLAGS} of them with a verbose, and {@link IntakeWire#MAX_SETBACKS} setbacks.
 * Safe to use from any thread, since a flag may come from another one.
 */
public final class TickFlags {
    private final List<IntakeWire.Flag> flags = new ArrayList<>();
    private List<IntakeWire.Setback> setbacks;
    private int verboseCount;

    public void add(String check, double violations) {
        add(check, violations, null);
    }

    /** {@code verbose} is kept while the tick has room for another verbose; null carries none. */
    public void add(String check, double violations, String verbose) {
        synchronized (flags) {
            if (flags.size() >= IntakeWire.MAX_FLAGS) return;
            if (verbose != null && verboseCount++ >= IntakeWire.MAX_VERBOSE_FLAGS) verbose = null;
            flags.add(IntakeWire.Flag.of(check, violations, verbose));
        }
    }

    /** Whether a flag added now could still carry its verbose. */
    public boolean verboseRoom() {
        synchronized (flags) {
            return flags.size() < IntakeWire.MAX_FLAGS && verboseCount < IntakeWire.MAX_VERBOSE_FLAGS;
        }
    }

    /** A setback of {@code kind} went out, for {@code cause} and naming {@code check}, either null. */
    public void setback(int kind, String cause, String check) {
        synchronized (flags) {
            if (setbacks == null) setbacks = new ArrayList<>(2);
            if (setbacks.size() >= IntakeWire.MAX_SETBACKS) return;
            setbacks.add(new IntakeWire.Setback(kind, cause, check));
        }
    }

    /** The flags since the last drain, oldest first. */
    public List<IntakeWire.Flag> drain() {
        synchronized (flags) {
            verboseCount = 0;
            if (flags.isEmpty()) return List.of();
            List<IntakeWire.Flag> drained = List.copyOf(flags);
            flags.clear();
            return drained;
        }
    }

    /** The setbacks since the last call, oldest first. */
    public List<IntakeWire.Setback> drainSetbacks() {
        synchronized (flags) {
            if (setbacks == null || setbacks.isEmpty()) return List.of();
            List<IntakeWire.Setback> drained = List.copyOf(setbacks);
            setbacks.clear();
            return drained;
        }
    }
}
