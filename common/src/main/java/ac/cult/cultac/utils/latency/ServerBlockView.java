package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.value.BlockPos;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * The block states the server has most recently sent, ahead of the client acknowledging them.
 *
 * <p>{@link CompensatedWorld} applies server packets only once the client has processed them. A
 * resync written now is ordered after every packet already sent, so it must carry the newest sent
 * value rather than the acknowledged one. This overlay holds sent block updates until their proof
 * applies them to the compensated world. Columns or worlds whose replacement is still in flight are
 * unknown: the fresh chunk or world already resynchronizes those positions. Owner-thread only.
 */
final class ServerBlockView {
    static final int UNKNOWN = Integer.MIN_VALUE;

    private final Long2IntOpenHashMap sentStates = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap sentIds = new Long2LongOpenHashMap();
    /**
     * Per column, the newest id issued before its last sent chunk. An update in the column with an id
     * up to that one is superseded and stays in {@link #sentIds} until its own proof or a sweep removes
     * it. Empty whenever {@link #sentIds} is; a sent chunk sweeps once it holds more than twice as many
     * columns as there are pending updates.
     */
    private final Long2LongOpenHashMap supersededIds = new Long2LongOpenHashMap();

    private static final int MIN_SWEEP_COLUMNS = 64;
    private static final int TRIM_COLUMNS = 256;

    private final Long2IntOpenHashMap pendingColumns = new Long2IntOpenHashMap();
    private int pendingWorldChanges;
    private long nextId;

    long sent(long position, int state) {
        long id = ++nextId;
        sentStates.put(position, state);
        sentIds.put(position, id);
        return id;
    }

    void applied(long position, long id) {
        if (sentIds.get(position) != id) return;
        sentIds.remove(position);
        sentStates.remove(position);
        if (sentIds.isEmpty() && !supersededIds.isEmpty()) clearSuperseded();
    }

    /** A chunk load or unload was sent; it supersedes every earlier update in the column. */
    void columnSent(int chunkX, int chunkZ) {
        long column = CompensatedWorld.chunkPositionToLong(chunkX, chunkZ);
        pendingColumns.addTo(column, 1);
        if (sentIds.isEmpty()) return;
        supersededIds.put(column, nextId);
        if (supersededIds.size() > Math.max(MIN_SWEEP_COLUMNS, 2 * sentIds.size())) dropSuperseded();
    }

    private void dropSuperseded() {
        var iterator = sentIds.long2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long position = entry.getLongKey();
            if (entry.getLongValue() <= supersededIds.get(columnOf(position))) {
                iterator.remove();
                sentStates.remove(position);
            }
        }
        clearSuperseded();
    }

    private void clearSuperseded() {
        boolean grown = supersededIds.size() > TRIM_COLUMNS;
        supersededIds.clear();
        if (grown) supersededIds.trim(Hash.DEFAULT_INITIAL_SIZE);
    }

    private static long columnOf(long position) {
        BlockPos pos = BlockPos.of(position);
        return CompensatedWorld.chunkPositionToLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    void columnApplied(int chunkX, int chunkZ) {
        long column = CompensatedWorld.chunkPositionToLong(chunkX, chunkZ);
        if (pendingColumns.addTo(column, -1) <= 1) pendingColumns.remove(column);
    }

    /** A login or dimension change was sent; nothing earlier describes the next world. */
    void worldChangeSent() {
        pendingWorldChanges++;
        sentStates.clear();
        sentIds.clear();
        clearSuperseded();
    }

    void worldChangeApplied() {
        if (pendingWorldChanges > 0) pendingWorldChanges--;
    }

    boolean isUnknown(int chunkX, int chunkZ) {
        return pendingWorldChanges > 0
                || pendingColumns.containsKey(CompensatedWorld.chunkPositionToLong(chunkX, chunkZ));
    }

    /** The newest sent but unapplied state, or {@link #UNKNOWN} when the compensated world is current. */
    int sentState(long position) {
        long id = sentIds.get(position);
        if (id == 0L || id <= supersededIds.get(columnOf(position))) return UNKNOWN;
        return sentStates.get(position);
    }
}
