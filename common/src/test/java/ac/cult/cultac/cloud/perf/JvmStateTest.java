package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.PerfBufferPool;
import ac.cult.cultac.cloud.intake.v3.PerfCollector;
import ac.cult.cultac.cloud.intake.v3.PerfMemoryPool;
import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import java.lang.management.MemoryUsage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JvmStateTest {

    @Test
    void readsTheHeapItsPoolsBuffersAndCollectors() throws Exception {
        Map<String, JvmState.Totals> start = JvmState.collections();
        System.gc();

        PerfProfile state = PerfProfile.parseFrom(JvmState.read(start).toByteArray());

        assertTrue(state.hasHeapCommittedBytes());
        assertTrue(state.getHeapCommittedBytes() > 0);
        if (state.hasHeapMaxBytes()) assertTrue(state.getHeapMaxBytes() >= state.getHeapCommittedBytes());
        assertFalse(state.getHeapPoolsList().isEmpty());
        long used = 0;
        for (PerfMemoryPool pool : state.getHeapPoolsList()) {
            assertFalse(pool.getName().isEmpty());
            used += pool.getUsedBytes();
        }
        assertTrue(used > 0);
        assertTrue(state.getHeapPoolsList().stream().anyMatch(PerfMemoryPool::hasUsedAfterGcBytes));
        assertTrue(
                state.getBufferPoolsList().stream().map(PerfBufferPool::getName).anyMatch("direct"::equals));
        assertFalse(state.getCollectorsList().isEmpty());
        assertTrue(state.getCollectorsList().stream().allMatch(PerfCollector::hasWindowCount));
        assertTrue(state.getCollectorsList().stream()
                        .mapToLong(PerfCollector::getWindowCount)
                        .sum()
                > 0);
    }

    @Test
    void anAbsentUsageLeavesItsFieldOut() {
        PerfMemoryPool pool = JvmState.pool("Old", null, null);

        assertEquals("Old", pool.getName());
        assertFalse(pool.hasUsedBytes());
        assertFalse(pool.hasUsedAfterGcBytes());
        assertEquals(7, JvmState.pool("Old", null, new MemoryUsage(0, 7, 8, 9)).getUsedAfterGcBytes());
    }

    @Test
    void collectorCountsAreWindowDeltasOnlyWhenBothEndsAreKnown() {
        PerfCollector delta = JvmState.collector("Cycles", new JvmState.Totals(3, 40), new JvmState.Totals(5, 70));
        PerfCollector unknownStart = JvmState.collector("Cycles", null, new JvmState.Totals(5, 70));
        PerfCollector noTime = JvmState.collector("Cycles", new JvmState.Totals(3, -1), new JvmState.Totals(5, -1));

        assertEquals(2, delta.getWindowCount());
        assertEquals(30, delta.getWindowMillis());
        assertFalse(unknownStart.hasWindowCount());
        assertFalse(unknownStart.hasWindowMillis());
        assertEquals(2, noTime.getWindowCount());
        assertFalse(noTime.hasWindowMillis());
    }
}
