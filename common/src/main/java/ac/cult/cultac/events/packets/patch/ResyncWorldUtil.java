package ac.cult.cultac.events.packets.patch;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.BlockPredictionAckSender;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.CultMath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Resends the server's most recently sent block states; never reads the live server world. */
public class ResyncWorldUtil {
    // Vanilla rejects bundles above 4096 sub-packets; leave room for the proof transaction.
    private static final int MAX_UPDATES_PER_BUNDLE = 1024;

    public static void resyncPosition(CultPlayer player, BlockPos pos) {
        resyncPositions(player, pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    public static void resyncPositions(CultPlayer player, SimpleCollisionBox box) {
        resyncPositions(
                player,
                CultMath.floor(box.minX),
                CultMath.floor(box.minY),
                CultMath.floor(box.minZ),
                CultMath.ceil(box.maxX),
                CultMath.ceil(box.maxY),
                CultMath.ceil(box.maxZ));
    }

    public static void resyncPositions(
            CultPlayer player, int minBlockX, int mY, int minBlockZ, int maxBlockX, int mxY, int maxBlockZ) {
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) return;
        // The server-sent view and the outbound proof belong to the connection owner.
        player.runSafely(() -> resyncOnOwner(player, minBlockX, mY, minBlockZ, maxBlockX, mxY, maxBlockZ));
    }

    private static void resyncOnOwner(
            CultPlayer player, int minBlockX, int mY, int minBlockZ, int maxBlockX, int mxY, int maxBlockZ) {
        if (player.getSetbackTeleportUtil().isDebug()) {
            LogUtil.info("Resyncing " + player.getName() + " at mbx=" + minBlockX + " mY=" + mY + " mbz=" + minBlockZ
                    + " mxbx=" + maxBlockX + " mxY=" + mxY + " mxbz=" + maxBlockZ);
        }
        // Check the 4 corners of the player world for loaded chunks before resending
        // all merge requests to delete this check are nocom exploit backdoors and should be rejected
        CompensatedWorld world = player.compensatedWorld;
        if (!world.isChunkLoaded(minBlockX >> 4, minBlockZ >> 4)
                || !world.isChunkLoaded(minBlockX >> 4, maxBlockZ >> 4)
                || !world.isChunkLoaded(maxBlockX >> 4, minBlockZ >> 4)
                || !world.isChunkLoaded(maxBlockX >> 4, maxBlockZ >> 4)) return;

        if (!player.getSetbackTeleportUtil().hasFullyLoaded) return;
        int minBlockY = Math.max(world.getMinHeight(), mY);
        int maxBlockY = Math.min(world.getMaxHeight() - 1, mxY);
        List<BlockPos> positions = new ArrayList<>();
        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int y = minBlockY; y <= maxBlockY; y++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        sendServerStates(player, positions, -1);
    }

    /**
     * Resends the server's most recently sent state for each known position, then acknowledges
     * {@code ackSequence} unless it is negative. Owner thread only. Returns the updates sent.
     */
    public static int sendServerStates(CultPlayer player, Collection<BlockPos> positions, int ackSequence) {
        CompensatedWorld world = player.compensatedWorld;
        List<BlockPredictionAckSender.TrackedBlockUpdate> updates = new ArrayList<>();
        int sent = 0;
        for (BlockPos pos : positions) {
            int state = world.getServerSentBlockStateIdAt(pos);
            if (state == CompensatedWorld.UNKNOWN_SERVER_STATE) continue;
            updates.add(new BlockPredictionAckSender.TrackedBlockUpdate(pos, state));
            if (updates.size() == MAX_UPDATES_PER_BUNDLE) {
                BlockPredictionAckSender.sendCorrections(player, 0, false, updates, List.of());
                sent += updates.size();
                updates = new ArrayList<>();
            }
        }
        sent += updates.size();
        // The acknowledgement follows every update, so the client ends its predictions on server states.
        BlockPredictionAckSender.sendCorrections(
                player, Math.max(0, ackSequence), ackSequence >= 0, updates, List.of());
        return sent;
    }
}
