package ac.cult.cultac.utils.latency;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.HashMap;
import java.util.Map;

public final class SectionPoolLeaseTracker {
    private static final int NOT_RETAINED = -1;

    /** Section counts of the retained chunks, by dimension, then chunk position. */
    private final Map<String, Long2IntOpenHashMap> retainedChunks = new HashMap<>();

    public void retain(String dimension, int chunkX, int chunkZ, int sectionCount) {
        Long2IntOpenHashMap chunks = retainedChunks.get(dimension);
        if (chunks == null) {
            chunks = new Long2IntOpenHashMap();
            chunks.defaultReturnValue(NOT_RETAINED);
            retainedChunks.put(dimension, chunks);
        }
        long position = key(chunkX, chunkZ);
        int previousSectionCount = chunks.get(position);
        if (previousSectionCount == NOT_RETAINED) {
            chunks.put(position, sectionCount);
            SectionPoolRegistry.retainChunk(dimension, chunkX, chunkZ, sectionCount);
            return;
        }

        if (previousSectionCount == sectionCount) {
            return;
        }

        chunks.put(position, sectionCount);
        SectionPoolRegistry.retainChunk(dimension, chunkX, chunkZ, sectionCount);
        SectionPoolRegistry.releaseChunk(dimension, chunkX, chunkZ, previousSectionCount);
    }

    public void release(String dimension, int chunkX, int chunkZ, int sectionCount) {
        Long2IntOpenHashMap chunks = retainedChunks.get(dimension);
        if (chunks == null) return;
        long position = key(chunkX, chunkZ);
        int retainedSectionCount = chunks.get(position);
        if (retainedSectionCount == NOT_RETAINED || retainedSectionCount != sectionCount) {
            return;
        }

        chunks.remove(position);
        SectionPoolRegistry.releaseChunk(dimension, chunkX, chunkZ, sectionCount);
    }

    public void release(String dimension, int chunkX, int chunkZ) {
        Long2IntOpenHashMap chunks = retainedChunks.get(dimension);
        if (chunks == null) return;
        long position = key(chunkX, chunkZ);
        int sectionCount = chunks.get(position);
        if (sectionCount != NOT_RETAINED) {
            chunks.remove(position);
            SectionPoolRegistry.releaseChunk(dimension, chunkX, chunkZ, sectionCount);
        }
    }

    public void clear() {
        for (Map.Entry<String, Long2IntOpenHashMap> dimension : retainedChunks.entrySet()) {
            for (Long2IntMap.Entry entry : dimension.getValue().long2IntEntrySet()) {
                long position = entry.getLongKey();
                SectionPoolRegistry.releaseChunk(
                        dimension.getKey(), (int) (position >> 32), (int) position, entry.getIntValue());
            }
        }
        retainedChunks.clear();
    }

    public int retainedChunkCount() {
        int count = 0;
        for (Long2IntOpenHashMap chunks : retainedChunks.values()) count += chunks.size();
        return count;
    }

    public boolean hasRetainedChunk(String dimension, int chunkX, int chunkZ) {
        Long2IntOpenHashMap chunks = retainedChunks.get(dimension);
        return chunks != null && chunks.containsKey(key(chunkX, chunkZ));
    }

    private static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }
}
