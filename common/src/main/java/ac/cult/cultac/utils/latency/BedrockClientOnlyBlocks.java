package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.value.BlockPos;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Positions where a Bedrock client holds a block its translator sent on its own account, not the block the server
 * sent: a virtual container holder, a piston's intermediate blocks, a restored door half.
 *
 * <p>{@link CompensatedWorld} holds the client's block there. This keeps the server's state underneath it, which
 * the client only receives when the translator sends that block again. Owner-thread only.
 */
final class BedrockClientOnlyBlocks {
    static final int NONE = Integer.MIN_VALUE;

    private final Long2IntOpenHashMap serverStates = new Long2IntOpenHashMap();

    BedrockClientOnlyBlocks() {
        serverStates.defaultReturnValue(NONE);
    }

    /** The client took a block of its own here; {@code serverState} is what the world held unless one already is. */
    void placed(long position, int serverState) {
        serverStates.putIfAbsent(position, serverState);
    }

    /** The client holds the server's block again. */
    void removed(long position) {
        serverStates.remove(position);
    }

    /** Whether a server update for this position stays underneath the client's own block. */
    boolean keepsServerUpdate(long position, int state) {
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
            return pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ;
        });
    }

    void clear() {
        serverStates.clear();
    }
}
