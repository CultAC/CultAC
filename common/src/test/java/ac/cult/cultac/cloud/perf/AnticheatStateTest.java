package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.utils.latency.SectionStore;
import org.junit.jupiter.api.Test;

class AnticheatStateTest {

    @Test
    void readsPlayersAndSectionStorage() {
        PerfProfile state = AnticheatState.read(() -> 3);

        assertEquals(3, state.getTrackedPlayers());
        assertEquals(SectionStore.capacity(), state.getSectionStoreCapacity());
        assertTrue(state.getSectionStoreCapacity() > 0);
        assertTrue(state.getSectionStoreEntries() <= state.getSectionStoreCapacity());
        assertTrue(state.hasSectionPoolDimensions());
        assertTrue(state.hasSectionPools());
        assertTrue(state.hasSectionPoolReferences());
    }

    @Test
    void anUnknownOrFailingPlayerCountIsAbsent() {
        assertFalse(AnticheatState.read(AnticheatState.NO_PLAYERS).hasTrackedPlayers());
        PerfProfile failed = AnticheatState.read(() -> {
            throw new IllegalStateException("not started");
        });

        assertFalse(failed.hasTrackedPlayers());
        assertTrue(failed.hasSectionStoreCapacity());
    }
}
