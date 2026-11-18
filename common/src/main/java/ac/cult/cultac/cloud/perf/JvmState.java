package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfBufferPool;
import ac.cult.cultac.cloud.intake.v3.PerfCollector;
import ac.cult.cultac.cloud.intake.v3.PerfMemoryPool;
import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The heap, its pools, the buffer pools and the collectors, read from the platform MXBeans. Never throws. */
final class JvmState {
    /** A collector's count and time as its bean reports them, -1 for each it does not. */
    record Totals(long count, long millis) {}

    private JvmState() {}

    /** Each collector's totals by name; empty when the beans cannot be read. */
    static Map<String, Totals> collections() {
        Map<String, Totals> totals = new HashMap<>();
        try {
            for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                totals.put(collector.getName(), totals(collector));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no collector beans
        }
        return totals;
    }

    /** The heap, its pools and the buffer pools now, and each collector's collections since {@code start}. */
    static PerfProfile read(Map<String, Totals> start) {
        PerfProfile.Builder state = PerfProfile.newBuilder();
        heap(state);
        pools(state);
        buffers(state);
        collectors(state, start);
        return state.build();
    }

    private static void heap(PerfProfile.Builder state) {
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            state.setHeapCommittedBytes(heap.getCommitted());
            if (heap.getMax() >= 0) state.setHeapMaxBytes(heap.getMax());
        } catch (RuntimeException | LinkageError ignored) {
            // no heap usage
        }
    }

    private static void pools(PerfProfile.Builder state) {
        List<MemoryPoolMXBean> pools;
        try {
            pools = ManagementFactory.getMemoryPoolMXBeans();
        } catch (RuntimeException | LinkageError ignored) {
            return;
        }
        for (MemoryPoolMXBean pool : pools) {
            try {
                if (pool.getType() != MemoryType.HEAP) continue;
                state.addHeapPools(pool(pool.getName(), pool.getUsage(), pool.getCollectionUsage()));
            } catch (RuntimeException | LinkageError ignored) {
                // the pool is no longer valid
            }
        }
    }

    /** A heap pool's entry; a null usage leaves its field absent. */
    static PerfMemoryPool pool(String name, MemoryUsage usage, MemoryUsage afterGc) {
        PerfMemoryPool.Builder pool = PerfMemoryPool.newBuilder().setName(name);
        if (usage != null) pool.setUsedBytes(usage.getUsed());
        if (afterGc != null) pool.setUsedAfterGcBytes(afterGc.getUsed());
        return pool.build();
    }

    private static void buffers(PerfProfile.Builder state) {
        List<BufferPoolMXBean> buffers;
        try {
            buffers = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        } catch (RuntimeException | LinkageError ignored) {
            return;
        }
        for (BufferPoolMXBean buffer : buffers) {
            try {
                PerfBufferPool.Builder pool = PerfBufferPool.newBuilder().setName(buffer.getName());
                if (buffer.getCount() >= 0) pool.setCount(buffer.getCount());
                if (buffer.getMemoryUsed() >= 0) pool.setUsedBytes(buffer.getMemoryUsed());
                if (buffer.getTotalCapacity() >= 0) pool.setCapacityBytes(buffer.getTotalCapacity());
                state.addBufferPools(pool);
            } catch (RuntimeException | LinkageError ignored) {
                // the pool cannot be read
            }
        }
    }

    private static void collectors(PerfProfile.Builder state, Map<String, Totals> start) {
        List<GarbageCollectorMXBean> collectors;
        try {
            collectors = ManagementFactory.getGarbageCollectorMXBeans();
        } catch (RuntimeException | LinkageError ignored) {
            return;
        }
        for (GarbageCollectorMXBean collector : collectors) {
            try {
                state.addCollectors(collector(collector.getName(), start.get(collector.getName()), totals(collector)));
            } catch (RuntimeException | LinkageError ignored) {
                // the collector cannot be read
            }
        }
    }

    /** A collector's entry: {@code end - start} of each total both report; absent otherwise. */
    static PerfCollector collector(String name, Totals start, Totals end) {
        PerfCollector.Builder collector = PerfCollector.newBuilder().setName(name);
        if (start != null && start.count >= 0 && end.count >= start.count)
            collector.setWindowCount(end.count - start.count);
        if (start != null && start.millis >= 0 && end.millis >= start.millis)
            collector.setWindowMillis(end.millis - start.millis);
        return collector.build();
    }

    private static Totals totals(GarbageCollectorMXBean collector) {
        return new Totals(collector.getCollectionCount(), collector.getCollectionTime());
    }
}
