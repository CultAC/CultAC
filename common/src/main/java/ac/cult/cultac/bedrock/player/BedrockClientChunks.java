package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * The chunk columns the Bedrock client holds, in world chunk coordinates.
 *
 * <p>A chunk publisher update moves the client's chunk view to the square of columns its radius spans around its
 * position; the client drops every column outside it, and a column it has not received inside it holds nothing.
 * A column the client receives in a tick holds its blocks from the next tick. The client does not move its actor
 * in a tick when a column under the actor's box holds nothing. Before the first publisher update the view has no
 * edge and the client keeps every column it receives.
 */
public final class BedrockClientChunks {
    private static final long HELD = Long.MIN_VALUE;

    // Column -> first client tick its blocks are there.
    private final Long2LongOpenHashMap columns = new Long2LongOpenHashMap();
    private boolean bounded;
    private int minX, minZ, maxX, maxZ;

    /** The client processed a column in the tick after {@code acknowledgedAfterTick}; returns whether it was new. */
    public boolean receive(int chunkX, int chunkZ, long acknowledgedAfterTick) {
        if (bounded && (chunkX < minX || chunkX > maxX || chunkZ < minZ || chunkZ > maxZ)) return false;
        long key = key(chunkX, chunkZ);
        if (columns.containsKey(key)) return false;
        columns.put(key, acknowledgedAfterTick == HELD ? HELD : acknowledgedAfterTick + 2);
        return true;
    }

    /** The client moved its chunk view to a publisher update's position and radius, in world blocks. */
    public void publish(int blockX, int blockZ, int radius) {
        bounded = true;
        minX = (blockX - radius) >> 4;
        maxX = (blockX + radius) >> 4;
        minZ = (blockZ - radius) >> 4;
        maxZ = (blockZ + radius) >> 4;
        columns.keySet().removeIf(key -> {
            int x = (int) (key >> 32), z = (int) key;
            return x < minX || x > maxX || z < minZ || z > maxZ;
        });
    }

    /** The client entered a new level, which holds none of the earlier columns. */
    public void clear() {
        columns.clear();
        bounded = false;
    }

    /** Whether every column under the box holds its blocks in the given client tick. */
    public boolean holds(WorldCollisionBox box, long tick) {
        if (!bounded || box == null) return true;
        int fromX = (int) Math.floor(box.minX()) >> 4, toX = (int) Math.floor(box.maxX()) >> 4;
        int fromZ = (int) Math.floor(box.minZ()) >> 4, toZ = (int) Math.floor(box.maxZ()) >> 4;
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                long key = key(x, z);
                if (!columns.containsKey(key) || columns.get(key) > tick) return false;
            }
        }
        return true;
    }

    /** Records a column written to the client, held from any tick; returns whether its view lacked it. */
    public boolean written(int chunkX, int chunkZ) {
        return receive(chunkX, chunkZ, HELD);
    }

    /** The same view, with every column held from any tick. */
    public BedrockClientChunks copy() {
        var copy = new BedrockClientChunks();
        copy.replace(this);
        return copy;
    }

    /** Takes another view's area and columns, each held from any tick. */
    public void replace(BedrockClientChunks other) {
        columns.clear();
        for (long key : other.columns.keySet()) columns.put(key, HELD);
        bounded = other.bounded;
        minX = other.minX;
        minZ = other.minZ;
        maxX = other.maxX;
        maxZ = other.maxZ;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
