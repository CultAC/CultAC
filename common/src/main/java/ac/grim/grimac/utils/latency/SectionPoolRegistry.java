package ac.grim.grimac.utils.latency;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class SectionPoolRegistry {
    private static final ConcurrentHashMap<String, ConcurrentHashMap<SectionCoordinate, PoolEntry>> poolsByDimension = new ConcurrentHashMap<>();

    private SectionPoolRegistry() {
    }

    static SectionPool forChunk(String dimension, int chunkX, int sectionIndex, int chunkZ) {
        SectionCoordinate coordinate = new SectionCoordinate(chunkX, sectionIndex, chunkZ);
        PoolEntry[] poolEntry = new PoolEntry[1];
        poolsByDimension.compute(dimension, (ignored, dimPools) -> {
            ConcurrentHashMap<SectionCoordinate, PoolEntry> pools = dimPools == null ? new ConcurrentHashMap<>() : dimPools;
            poolEntry[0] = pools.computeIfAbsent(coordinate, ignoredCoordinate -> new PoolEntry());
            return pools;
        });
        return poolEntry[0].pool;
    }

    static void retainChunk(String dimension, int chunkX, int chunkZ, int sectionCount) {
        poolsByDimension.compute(dimension, (ignored, dimPools) -> {
            ConcurrentHashMap<SectionCoordinate, PoolEntry> pools = dimPools == null ? new ConcurrentHashMap<>() : dimPools;
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                pools.compute(new SectionCoordinate(chunkX, sectionIndex, chunkZ), (ignoredCoordinate, entry) -> {
                    PoolEntry retained = entry == null ? new PoolEntry() : entry;
                    synchronized (retained.pool) {
                        retained.references++;
                    }
                    return retained;
                });
            }
            return pools;
        });
    }

    static void releaseChunk(String dimension, int chunkX, int chunkZ, int sectionCount) {
        poolsByDimension.computeIfPresent(dimension, (ignored, dimPools) -> {
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                dimPools.computeIfPresent(new SectionCoordinate(chunkX, sectionIndex, chunkZ), (ignoredCoordinate, entry) -> {
                    synchronized (entry.pool) {
                        return --entry.references <= 0 ? null : entry;
                    }
                });
            }
            return dimPools.isEmpty() ? null : dimPools;
        });
    }

    static boolean tryMakeExclusiveForMutation(String dimension, int chunkX, int sectionIndex, int chunkZ, CompensatedWorld.CachedSection section) {
        ConcurrentHashMap<SectionCoordinate, PoolEntry> dimPools = poolsByDimension.get(dimension);
        if (dimPools == null) return false;

        PoolEntry entry = dimPools.get(new SectionCoordinate(chunkX, sectionIndex, chunkZ));
        if (entry == null) return false;

        synchronized (entry.pool) {
            return entry.references == 1 && entry.pool.detachForSingleReferenceMutation(section);
        }
    }

    static void releaseSectionReference(String dimension, int chunkX, int sectionIndex, int chunkZ, CompensatedWorld.CachedSection section) {
        ConcurrentHashMap<SectionCoordinate, PoolEntry> dimPools = poolsByDimension.get(dimension);
        if (dimPools == null) return;

        PoolEntry entry = dimPools.get(new SectionCoordinate(chunkX, sectionIndex, chunkZ));
        if (entry != null) {
            entry.pool.releaseSectionReference(section);
        }
    }

    static int globalNonUniformPoolSize() {
        int total = 0;
        for (ConcurrentHashMap<SectionCoordinate, PoolEntry> dimPools : poolsByDimension.values()) {
            for (PoolEntry entry : dimPools.values()) {
                total += entry.pool.nonUniformPoolSize();
            }
        }
        return total;
    }

    static int globalUniformPoolSize() {
        return 0;
    }

    static int poolCount() {
        int total = 0;
        for (ConcurrentHashMap<SectionCoordinate, PoolEntry> dimPools : poolsByDimension.values()) {
            total += dimPools.size();
        }
        return total;
    }

    static int totalReferences() {
        int total = 0;
        for (ConcurrentHashMap<SectionCoordinate, PoolEntry> dimPools : poolsByDimension.values()) {
            for (PoolEntry entry : dimPools.values()) {
                synchronized (entry.pool) {
                    total += entry.references;
                }
            }
        }
        return total;
    }

    static List<SectionPool.DimensionStats> dimensionStats() {
        List<SectionPool.DimensionStats> stats = new ArrayList<>();
        for (var dimensionEntry : poolsByDimension.entrySet()) {
            int references = 0;
            ConcurrentHashMap<SectionCoordinate, PoolEntry> dimPools = dimensionEntry.getValue();
            for (PoolEntry poolEntry : dimPools.values()) {
                synchronized (poolEntry.pool) {
                    references += poolEntry.references;
                }
            }
            stats.add(new SectionPool.DimensionStats(dimensionEntry.getKey(), dimPools.size(), references));
        }
        stats.sort(Comparator.comparing(stat -> stat.dimension().toString()));
        return stats;
    }

    private static final class PoolEntry {
        private final SectionPool pool = new SectionPool();
        private int references;
    }

    private record SectionCoordinate(int chunkX, int sectionIndex, int chunkZ) {
    }
}
