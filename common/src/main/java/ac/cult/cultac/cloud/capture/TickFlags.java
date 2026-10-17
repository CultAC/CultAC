package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.cloud.wire.IntakeWire;
import java.util.ArrayList;
import java.util.List;

/**
 * The check flags since the last tick end, for that tick's VERDICT: at most {@link IntakeWire#MAX_FLAGS}. Safe to use
 * from any thread, since a flag may come from another one.
 */
public final class TickFlags {
    private final List<IntakeWire.Flag> flags = new ArrayList<>();

    public void add(String check, double violations) {
        synchronized (flags) {
            if (flags.size() < IntakeWire.MAX_FLAGS) flags.add(IntakeWire.Flag.of(check, violations));
        }
    }

    /** The flags since the last drain, oldest first. */
    public List<IntakeWire.Flag> drain() {
        synchronized (flags) {
            if (flags.isEmpty()) return List.of();
            List<IntakeWire.Flag> drained = List.copyOf(flags);
            flags.clear();
            return drained;
        }
    }
}
