package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import org.junit.jupiter.api.Test;

/** Players retaining a chunk share its pools until the last lets go; a dimension without pools starts afresh. */
class SectionPoolRegistryTest {
    @Test
    void leasesSharePoolsUntilTheLastRelease() {
        String dimension = "test:shared";
        SectionPoolLeaseTracker first = new SectionPoolLeaseTracker();
        SectionPoolLeaseTracker second = new SectionPoolLeaseTracker();
        int pools = SectionPool.poolCount();
        int references = SectionPool.totalReferences();

        first.retain(dimension, -3, 1_000_000, 24);
        second.retain(dimension, -3, 1_000_000, 24);
        assertEquals(pools + 24, SectionPool.poolCount());
        assertEquals(references + 48, SectionPool.totalReferences());
        assertTrue(first.hasRetainedChunk(dimension, -3, 1_000_000));
        assertFalse(first.hasRetainedChunk(dimension, 1_000_000, -3));
        SectionPool pool = SectionPool.forChunk(dimension, -3, 5, 1_000_000);
        assertSame(pool, SectionPool.forChunk(dimension, -3, 5, 1_000_000));
        assertNotSame(pool, SectionPool.forChunk(dimension, 1_000_000, 5, -3));
        SectionPool.forChunk(dimension, 1_000_000, 5, -3);
        assertEquals(pools + 25, SectionPool.poolCount());

        CachedSection section = CachedSection.createAirSection();
        assertFalse(SectionPool.tryMakeExclusiveForMutation(dimension, -3, 5, 1_000_000, section));
        first.release(dimension, -3, 1_000_000, 24);
        assertSame(pool, SectionPool.forChunk(dimension, -3, 5, 1_000_000));
        assertEquals(references + 24, SectionPool.totalReferences());
        assertEquals(1, first.retainedChunkCount() + second.retainedChunkCount());

        second.release(dimension, -3, 1_000_000);
        assertEquals(pools + 1, SectionPool.poolCount());
        assertEquals(references, SectionPool.totalReferences());
        assertNotSame(pool, SectionPool.forChunk(dimension, -3, 5, 1_000_000));
        assertEquals(pools + 2, SectionPool.poolCount());
    }

    @Test
    void aSectionIndexBelowZeroHasItsOwnRegisteredPool() {
        String dimension = "test:below";
        int pools = SectionPool.poolCount();
        SectionPool below = SectionPool.forChunk(dimension, 2, -1, 3);
        assertSame(below, SectionPool.forChunk(dimension, 2, -1, 3));
        assertNotSame(below, SectionPool.forChunk(dimension, 2, -2, 3));
        assertEquals(pools + 2, SectionPool.poolCount());
        CachedSection section = CachedSection.createAirSection();
        assertFalse(SectionPool.tryMakeExclusiveForMutation(dimension, 2, -1, 3, section));
    }

    @Test
    void aChangedSectionCountRetainsTheNewOneBeforeReleasingTheOld() {
        String dimension = "test:resized";
        SectionPoolLeaseTracker lease = new SectionPoolLeaseTracker();
        int pools = SectionPool.poolCount();
        lease.retain(dimension, 4, 4, 16);
        SectionPool low = SectionPool.forChunk(dimension, 4, 3, 4);
        lease.retain(dimension, 4, 4, 24);
        assertSame(low, SectionPool.forChunk(dimension, 4, 3, 4));
        assertEquals(pools + 24, SectionPool.poolCount());
        lease.release(dimension, 4, 4, 16);
        assertEquals(pools + 24, SectionPool.poolCount(), "a release of another count is ignored");
        lease.clear();
        assertEquals(pools, SectionPool.poolCount());
        assertEquals(0, lease.retainedChunkCount());
        assertTrue(SectionPool.dimensionStats().stream()
                .noneMatch(stats -> stats.dimension().equals(dimension)));
        lease.retain(dimension, 4, 4, 24);
        assertEquals(pools + 24, SectionPool.poolCount());
        lease.clear();
    }
}
