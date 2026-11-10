package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.value.BlockPos;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Positions where a Bedrock client holds a block its translator sent on its own account, not the block the server
 * sent: a virtual container holder, a piston's intermediate blocks, a restored door half. Also positions where the
 * client broke the server's block itself.
 *
 * <p>{@link CompensatedWorld} holds the client's block there. This keeps the server's state underneath it, which
 * the client only receives when the translator sends that block again. Owner-thread only.
 */
final class BedrockClientOnlyBlocks {
    static final int NONE = Integer.MIN_VALUE;

    private final Long2IntOpenHashMap serverStates = new Long2IntOpenHashMap();
    /** The positions the client broke itself: the server's next update there replaces the client's block. */
    private final LongOpenHashSet broken = new LongOpenHashSet();

    BedrockClientOnlyBlocks() {
        serverStates.defaultReturnValue(NONE);
    }

    /** The client took a block of its own here; {@code serverState} is what the world held unless one already is. */
    void placed(long position, int serverState) {
        serverStates.putIfAbsent(position, serverState);
    }

    /** The client broke the block here itself; {@code serverState} is what the world held. */
    void broken(long position, int serverState) {
        if (serverStates.putIfAbsent(position, serverState) == NONE) broken.add(position);
    }

    /** The client holds the server's block again. */
    void removed(long position) {
        serverStates.remove(position);
        broken.remove(position);
    }

    /** Whether a server update for this position stays underneath the client's own block. */
    boolean keepsServerUpdate(long position, int state) {
        if (broken.remove(position)) serverStates.remove(position);
        if (!serverStates.containsKey(position)) return false;
        serverStates.put(position, state);
        return true;
    }

    /** The server's state under the client's own block, or {@link #NONE}. */
    int serverState(long position) {
        return serverStates.get(position);
    }

    void forgetChunk(int chunkX, int chunkZ) {
        if (serverStates.isEmpty()) return;
        serverStates.keySet().removeIf((long position) -> {
            BlockPos pos = BlockPos.of(position);
            boolean inChunk = pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ;
            if (inChunk) broken.remove(position);
            return inChunk;
        });
    }

    void clear() {
        broken.clear();
        serverStates.clear();
    }
}
