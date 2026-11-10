package ac.cult.cultac.utils.latency;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * ClientChunkCache.Storage, independently of TransientEntitySectionManager's ticking chunks. Moving the view
 * center changes lookup/drop range without unloading anything. Resizing discards out-of-range cache entries
 * without calling ClientLevel#unload; replacing a circular slot does call it.
 */
final class ClientChunkCacheState {
    private final CompensatedWorld world;
    private Int2LongOpenHashMap slots = new Int2LongOpenHashMap();
    private final LongOpenHashSet ticking = new LongOpenHashSet();
    private boolean enabled;
    private int serverRadius = 3;
    private int radius = 6;
    private int centerX, centerZ;

    ClientChunkCacheState(CompensatedWorld world) {
        this.world = world;
    }

    void initialize(int serverRadius) {
        this.enabled = true;
        this.serverRadius = serverRadius;
        clearLevel();
    }

    void clearLevel() {
        for (long key : ticking) world.entityChunkTicking(x(key), z(key), false);
        slots.clear();
        ticking.clear();
        radius = Math.max(2, serverRadius) + 3;
        centerX = centerZ = 0;
    }

    void center(int x, int z) {
        centerX = x;
        centerZ = z;
    }

    void radius(int serverRadius) {
        this.serverRadius = serverRadius;
        int nextRadius = Math.max(2, serverRadius) + 3;
        if (!enabled || radius == nextRadius) return;
        radius = nextRadius;
        var previous = slots;
        slots = new Int2LongOpenHashMap();
        for (long key : previous.values()) {
            if (inRange(x(key), z(key))) slots.put(index(x(key), z(key)), key);
            else world.discardChunkGeometry(x(key), z(key));
        }
    }

    boolean contains(int x, int z) {
        if (!enabled) return true;
        int index = index(x, z);
        return inRange(x, z) && slots.containsKey(index) && slots.get(index) == key(x, z);
    }

    boolean ticks(int x, int z) {
        return !enabled ? world.isChunkLoaded(x, z) : ticking.contains(key(x, z));
    }

    boolean load(int x, int z) {
        if (!enabled) return true;
        if (!inRange(x, z)) return false;
        int index = index(x, z);
        long key = key(x, z);
        if (slots.containsKey(index)) {
            long old = slots.get(index);
            if (old != key) {
                ticking.remove(old);
                world.unloadClientChunk(x(old), z(old));
            }
        }
        slots.put(index, key);
        ticking.add(key);
        return true;
    }

    boolean drop(int x, int z) {
        if (!enabled) return true;
        if (!contains(x, z)) return false;
        slots.remove(index(x, z));
        ticking.remove(key(x, z));
        return true;
    }

    private boolean inRange(int x, int z) {
        return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
    }

    private int index(int x, int z) {
        int width = radius * 2 + 1;
        return Math.floorMod(z, width) * width + Math.floorMod(x, width);
    }

    private static long key(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    private static int x(long key) {
        return (int) (key >> 32);
    }

    private static int z(long key) {
        return (int) key;
    }
}
