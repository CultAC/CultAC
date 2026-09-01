package ac.grim.grimac.events.packets.patch;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.protocol.util.FoliaCompatUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.GrimMath;
import io.papermc.paper.math.Position;
import net.minecraft.core.BlockPos;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;

public class ResyncWorldUtil {
    public static void resyncPosition(GrimPlayer player, BlockPos pos) {
        resyncPositions(player, pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    public static void resyncPositions(GrimPlayer player, SimpleCollisionBox box) {
        resyncPositions(player, GrimMath.floor(box.minX), GrimMath.floor(box.minY), GrimMath.floor(box.minZ),
                GrimMath.ceil(box.maxX), GrimMath.ceil(box.maxY), GrimMath.ceil(box.maxZ));
    }

    public static void resyncPositions(GrimPlayer player, int minBlockX, int mY, int minBlockZ, int maxBlockX, int mxY, int maxBlockZ) {
        if (player.getSetbackTeleportUtil().isDebug()) { LogUtil.info("Resyncing " + player.getName() + " at mbx=" + minBlockX + " mY=" + mY + " mbz=" + minBlockZ + " mxbx=" + maxBlockX + " mxY=" + mxY + " mxbz=" + maxBlockZ); }
        if (player.throwError) { try {
            throw new RuntimeException(new String(""));
        } catch (RuntimeException ex) {
            ex.printStackTrace();
        } }
        // Check the 4 corners of the player world for loaded chunks before calling event
        if (!player.compensatedWorld.isChunkLoaded(minBlockX >> 4, minBlockZ >> 4) || !player.compensatedWorld.isChunkLoaded(minBlockX >> 4, maxBlockZ >> 4)
                || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, minBlockZ >> 4) || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, maxBlockZ >> 4))
            return;

        FoliaCompatUtil.runTaskForEntity(player.bukkitPlayer, GrimAPI.INSTANCE.getPlugin(), () -> {
            if (player.bukkitPlayer == null) return;
            // Player hasn't spawned, don't spam packets
            if (!player.getSetbackTeleportUtil().hasFullyLoaded) return;

            // Check the 4 corners of the BB for loaded chunks, don't freeze main thread to load chunks.
            if (!player.bukkitPlayer.getWorld().isChunkLoaded(minBlockX >> 4, minBlockZ >> 4) || !player.bukkitPlayer.getWorld().isChunkLoaded(minBlockX >> 4, maxBlockZ >> 4)
                    || !player.bukkitPlayer.getWorld().isChunkLoaded(maxBlockX >> 4, minBlockZ >> 4) || !player.bukkitPlayer.getWorld().isChunkLoaded(maxBlockX >> 4, maxBlockZ >> 4))
                return;

            // This is based on Tuinity's code, thanks leaf. Now merged into paper.
            final int minSection = player.compensatedWorld.getMinHeight() >> 4;
            final int minBlock = minSection << 4;
            final int maxBlock = player.compensatedWorld.getMaxHeight() - 1;

            int minBlockY = Math.max(minBlock, mY);
            int maxBlockY = Math.min(maxBlock, mxY);

            int width = Math.max(1, maxBlockX - minBlockX + 1);
            int height = Math.max(1, maxBlockY - minBlockY + 1);
            int depth = Math.max(1, maxBlockZ - minBlockZ + 1);
            Map<Position, BlockData> changes = new HashMap<>(width * height * depth);

            for (int blockX = minBlockX; blockX <= maxBlockX; blockX++) {
                for (int blockY = minBlockY; blockY <= maxBlockY; blockY++) {
                    for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
                        changes.put(
                                Position.block(blockX, blockY, blockZ),
                                player.bukkitPlayer.getWorld().getBlockAt(blockX, blockY, blockZ).getBlockData()
                        );
                    }
                }
            }

            player.bukkitPlayer.sendMultiBlockChange(changes, false);
        }, null, 0);
    }
}
