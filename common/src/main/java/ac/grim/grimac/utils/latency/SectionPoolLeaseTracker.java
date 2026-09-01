package ac.grim.grimac.utils.latency;


import java.util.HashMap;
import java.util.Map;

public final class SectionPoolLeaseTracker {
    private final Map<ChunkCoordinate, Integer> retainedChunks = new HashMap<>();

    public void retain(String dimension, int chunkX, int chunkZ, int sectionCount) {
        ChunkCoordinate coordinate = new ChunkCoordinate(dimension, chunkX, chunkZ);
        Integer previousSectionCount = retainedChunks.get(coordinate);
        if (previousSectionCount == null) {
            retainedChunks.put(coordinate, sectionCount);
            SectionPoolRegistry.retainChunk(dimension, chunkX, chunkZ, sectionCount);
            return;
        }

        if (previousSectionCount == sectionCount) {
            return;
        }

        retainedChunks.put(coordinate, sectionCount);
        SectionPoolRegistry.retainChunk(dimension, chunkX, chunkZ, sectionCount);
        SectionPoolRegistry.releaseChunk(dimension, chunkX, chunkZ, previousSectionCount);
    }

    public void release(String dimension, int chunkX, int chunkZ, int sectionCount) {
        ChunkCoordinate coordinate = new ChunkCoordinate(dimension, chunkX, chunkZ);
        Integer retainedSectionCount = retainedChunks.get(coordinate);
        if (retainedSectionCount == null || retainedSectionCount != sectionCount) {
            return;
        }

        retainedChunks.remove(coordinate);
        SectionPoolRegistry.releaseChunk(dimension, chunkX, chunkZ, sectionCount);
    }

    public void release(String dimension, int chunkX, int chunkZ) {
        ChunkCoordinate coordinate = new ChunkCoordinate(dimension, chunkX, chunkZ);
        Integer sectionCount = retainedChunks.remove(coordinate);
        if (sectionCount != null) {
            SectionPoolRegistry.releaseChunk(dimension, chunkX, chunkZ, sectionCount);
        }
    }

    public void clear() {
        for (Map.Entry<ChunkCoordinate, Integer> entry : retainedChunks.entrySet()) {
            ChunkCoordinate coordinate = entry.getKey();
            SectionPoolRegistry.releaseChunk(
                    coordinate.dimension,
                    coordinate.chunkX,
                    coordinate.chunkZ,
                    entry.getValue());
        }
        retainedChunks.clear();
    }

    public int retainedChunkCount() {
        return retainedChunks.size();
    }

    public boolean hasRetainedChunk(String dimension, int chunkX, int chunkZ) {
        return retainedChunks.containsKey(new ChunkCoordinate(dimension, chunkX, chunkZ));
    }

    private record ChunkCoordinate(String dimension, int chunkX, int chunkZ) {
    }
}
