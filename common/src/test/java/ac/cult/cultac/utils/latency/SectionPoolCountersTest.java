package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The lock-free pool and reference counts equal the locked sums after any retain, release and lookup. */
class SectionPoolCountersTest {
    @Test
    void countersFollowTheLockedSumsAndReturnAfterRelease() {
        String dimension = "test:counters";
        int pools = SectionPoolCounters.pools();
        long references = SectionPoolCounters.references();
        assertCountersMatch();

        SectionPoolLeaseTracker first = new SectionPoolLeaseTracker();
        SectionPoolLeaseTracker second = new SectionPoolLeaseTracker();
        first.retain(dimension, 7, -9, 24);
        second.retain(dimension, 7, -9, 24);
        SectionPool.forChunk(dimension, 7, -2, -9);
        SectionPool.forChunk(dimension, 8, 3, -9);
        assertEquals(pools + 26, SectionPoolCounters.pools());
        assertEquals(references + 48, SectionPoolCounters.references());
        assertCountersMatch();

        first.release(dimension, 7, -9, 24);
        assertEquals(references + 24, SectionPoolCounters.references());
        assertCountersMatch();

        SectionPoolRegistry.releaseChunk(dimension, 8, -9, 24);
        second.release(dimension, 7, -9, 24);
        assertEquals(pools + 1, SectionPoolCounters.pools());
        assertEquals(references, SectionPoolCounters.references());
        assertCountersMatch();
    }

    private static void assertCountersMatch() {
        assertEquals(SectionPool.poolCount(), SectionPoolCounters.pools());
        assertEquals(SectionPool.totalReferences(), SectionPoolCounters.references());
    }
}
