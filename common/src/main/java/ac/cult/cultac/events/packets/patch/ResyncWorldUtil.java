package ac.cult.cultac.events.packets.patch;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;

public class ResyncWorldUtil {
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
        if (player.platformPlayer == null || !player.platformPlayer.hasServerAuthority()) return;
        if (player.getSetbackTeleportUtil().isDebug()) {
            LogUtil.info("Resyncing " + player.getName() + " at mbx=" + minBlockX + " mY=" + mY + " mbz=" + minBlockZ
                    + " mxbx=" + maxBlockX + " mxY=" + mxY + " mxbz=" + maxBlockZ);
        }
        if (player.throwError) {
            try {
                throw new RuntimeException(new String(""));
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
        }
        // Check the 4 corners of the player world for loaded chunks before calling event
        // all merge requests to delete this check are nocom exploit backdoors and should be rejected
        if (!player.compensatedWorld.isChunkLoaded(minBlockX >> 4, minBlockZ >> 4)
                || !player.compensatedWorld.isChunkLoaded(minBlockX >> 4, maxBlockZ >> 4)
                || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, minBlockZ >> 4)
                || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, maxBlockZ >> 4)) return;

        // Snapshot compensated bounds on the calling packet timeline, not in region tasks.
        if (!player.getSetbackTeleportUtil().hasFullyLoaded) return;
        int minBlockY = Math.max(player.compensatedWorld.getMinHeight(), mY);
        int maxBlockY = Math.min(player.compensatedWorld.getMaxHeight() - 1, mxY);
        if (player.platformPlayer != null && minBlockY <= maxBlockY) {
            player.platformPlayer.resendBlocks(minBlockX, minBlockY, minBlockZ, maxBlockX, maxBlockY, maxBlockZ);
        }
    }
}
