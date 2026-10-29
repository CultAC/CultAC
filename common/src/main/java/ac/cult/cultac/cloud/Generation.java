package ac.cult.cultac.cloud;

import java.util.function.BooleanSupplier;

/** The configure generation: work a configure started goes on only while its own generation is the latest. */
public final class Generation {
    private long latest;

    /** Starts a generation; every earlier one stops being the latest. */
    public synchronized long next() {
        return ++latest;
    }

    public synchronized long current() {
        return latest;
    }

    public synchronized boolean latest(long generation) {
        return generation == latest;
    }

    /** {@code action}'s answer while {@code generation} is the latest, no configure between; else false. */
    public synchronized boolean whileLatest(long generation, BooleanSupplier action) {
        return generation == latest && action.getAsBoolean();
    }
}
