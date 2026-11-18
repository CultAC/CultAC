package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.utils.latency.SectionPoolCounters;
import ac.cult.cultac.utils.latency.SectionStore;
import java.util.function.IntSupplier;

/** The anticheat's players and shared section storage, read from counters without a lock. Never throws. */
final class AnticheatState {
    /** A player count that is never known. */
    static final IntSupplier NO_PLAYERS = () -> -1;

    private AnticheatState() {}

    /** The state now; {@code trackedPlayers} below zero leaves its field absent. */
    static PerfProfile read(IntSupplier trackedPlayers) {
        PerfProfile.Builder state = PerfProfile.newBuilder();
        try {
            int players = trackedPlayers.getAsInt();
            if (players >= 0) state.setTrackedPlayers(players);
        } catch (RuntimeException | LinkageError ignored) {
            // no player count
        }
        try {
            state.setSectionStoreEntries(SectionStore.size()).setSectionStoreCapacity(SectionStore.capacity());
        } catch (RuntimeException | LinkageError ignored) {
            // no section store
        }
        try {
            state.setSectionPoolDimensions(SectionPoolCounters.dimensions())
                    .setSectionPools(SectionPoolCounters.pools())
                    .setSectionPoolReferences(SectionPoolCounters.references());
        } catch (RuntimeException | LinkageError ignored) {
            // no section pools
        }
        return state.build();
    }
}
