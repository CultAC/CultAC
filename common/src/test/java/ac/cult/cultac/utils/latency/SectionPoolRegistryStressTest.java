package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Players' event loops retaining and releasing columns while others look up pools for block writes: a write's lookup
 * always finds its retained pool, a retained column keeps its pools, and every reference is given back.
 */
class SectionPoolRegistryStressTest {
    private static final String DIMENSION = "test:stress";
    private static final int WRITERS = 4;
    private static final int READERS = 4;
    private static final int COLUMNS_PER_WRITER = 2_000;
    private static final int ROUNDS = 40;

    @Test
    void lookupsDuringChurnFindRetainedPools() throws Exception {
        SectionPoolLeaseTracker pins = new SectionPoolLeaseTracker();
        for (int reader = 0; reader < READERS; reader++) pins.retain(DIMENSION, -50 - reader, 7, 24);
        assertEquals(READERS * 24, stats().orElseThrow().sectionReferences());

        AtomicBoolean writing = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(WRITERS + READERS);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                int id = writer;
                writers.add(threads.submit(() -> {
                    start.await();
                    churn(id);
                    return null;
                }));
            }
            List<Future<Integer>> readers = new ArrayList<>();
            for (int reader = 0; reader < READERS; reader++) {
                int id = reader;
                readers.add(threads.submit(() -> {
                    start.await();
                    return mutate(id, writing);
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) writer.get(2, TimeUnit.MINUTES);
            writing.set(false);
            for (Future<Integer> reader : readers) assertTrue(reader.get(1, TimeUnit.MINUTES) > 0);
        } finally {
            threads.shutdownNow();
        }

        SectionPool.DimensionStats remaining = stats().orElseThrow();
        assertEquals(READERS * 24, remaining.sectionReferences(), "only the pins hold references");
        pins.clear();
        assertTrue(stats().isEmpty(), "the dimension retires with its last pool");
    }

    /** One player's loads and unloads: every column's pools stay its own while it is retained. */
    private static void churn(int writer) {
        SectionPoolLeaseTracker leases = new SectionPoolLeaseTracker();
        int baseX = writer * 10_000;
        for (int round = 0; round < ROUNDS; round++) {
            SectionPool[] pools = new SectionPool[COLUMNS_PER_WRITER];
            for (int column = 0; column < COLUMNS_PER_WRITER; column++) {
                leases.retain(DIMENSION, baseX + column, round, 24);
                pools[column] = SectionPool.forChunk(DIMENSION, baseX + column, column % 24, round);
            }
            for (int column = 0; column < COLUMNS_PER_WRITER; column++) {
                assertSame(pools[column], SectionPool.forChunk(DIMENSION, baseX + column, column % 24, round));
                if (column % 2 == 0) leases.release(DIMENSION, baseX + column, round, 24);
            }
            for (int column = 1; column < COLUMNS_PER_WRITER; column += 2)
                assertSame(pools[column], SectionPool.forChunk(DIMENSION, baseX + column, column % 24, round));
            leases.clear();
        }
        assertEquals(0, leases.retainedChunkCount());
    }

    /** One player's block writes to its own pinned column: each lookup finds the single-reference pool. */
    private static int mutate(int reader, AtomicBoolean writing) {
        int chunkX = -50 - reader;
        int writes = 0;
        SectionPool pool = SectionPool.forChunk(DIMENSION, chunkX, reader, 7);
        while (writing.get() || writes == 0) {
            CachedSection section = CachedSection.createAirSection();
            section.setStateId(writes % 4096, BlockIds.STONE.defaultState());
            section.setStateId((writes * 7 + 1) % 4096, BlockIds.GLOWSTONE.defaultState());
            CachedSection pooled = pool.internRetained(section);
            assertSame(section, pooled, "a fresh section enters the pool");
            if (writes % 256 == 0) assertSame(pool, SectionPool.forChunk(DIMENSION, chunkX, reader, 7));
            assertTrue(
                    SectionPool.tryMakeExclusiveForMutation(DIMENSION, chunkX, reader, 7, section),
                    "a pinned pool is found and held once");
            writes++;
        }
        return writes;
    }

    private static Optional<SectionPool.DimensionStats> stats() {
        return SectionPool.dimensionStats().stream()
                .filter(stats -> stats.dimension().equals(DIMENSION))
                .findFirst();
    }
}
