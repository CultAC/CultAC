package ac.cult.cultac.events.packets.patch;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.util.FoliaCompatUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;
import io.papermc.paper.math.Position;
import net.minecraft.core.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

public class ResyncWorldUtil {
    public static void resyncPosition(CultPlayer player, BlockPos pos) {
        resyncPositions(player, pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    public static void resyncPositions(CultPlayer player, SimpleCollisionBox box) {
        resyncPositions(player, CultMath.floor(box.minX), CultMath.floor(box.minY), CultMath.floor(box.minZ),
                CultMath.ceil(box.maxX), CultMath.ceil(box.maxY), CultMath.ceil(box.maxZ));
    }

    public static void resyncPositions(CultPlayer player, int minBlockX, int mY, int minBlockZ, int maxBlockX, int mxY, int maxBlockZ) {
        if (player.getSetbackTeleportUtil().isDebug()) { LogUtil.info("Resyncing " + player.getName() + " at mbx=" + minBlockX + " mY=" + mY + " mbz=" + minBlockZ + " mxbx=" + maxBlockX + " mxY=" + mxY + " mxbz=" + maxBlockZ); }
        if (player.throwError) { try {
            throw new RuntimeException(new String(""));
        } catch (RuntimeException ex) {
            ex.printStackTrace();
        } }
        // Check the 4 corners of the player world for loaded chunks before calling event
        // all merge requests to delete this check are nocom exploit backdoors and should be rejected
        if (!player.compensatedWorld.isChunkLoaded(minBlockX >> 4, minBlockZ >> 4) || !player.compensatedWorld.isChunkLoaded(minBlockX >> 4, maxBlockZ >> 4)
                || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, minBlockZ >> 4) || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, maxBlockZ >> 4))
            return;

        // Snapshot compensated bounds on the calling packet timeline, not in region tasks.
        if (!player.getSetbackTeleportUtil().hasFullyLoaded) return;
        int minBlockY = Math.max(player.compensatedWorld.getMinHeight(), mY);
        int maxBlockY = Math.min(player.compensatedWorld.getMaxHeight() - 1, mxY);
        Player recipient = player.bukkitPlayer;
        Plugin plugin = CultAPI.INSTANCE.getPlugin();
        FoliaCompatUtil.runTaskForEntity(recipient, plugin, () -> {
            World world = recipient.getWorld();
            for (int chunkX = minBlockX >> 4; chunkX <= maxBlockX >> 4; chunkX++) {
                for (int chunkZ = minBlockZ >> 4; chunkZ <= maxBlockZ >> 4; chunkZ++) {
                    int x1 = Math.max(minBlockX, chunkX << 4);
                    int x2 = Math.min(maxBlockX, (chunkX << 4) + 15);
                    int z1 = Math.max(minBlockZ, chunkZ << 4);
                    int z2 = Math.min(maxBlockZ, (chunkZ << 4) + 15);
                    Runnable snapshot = () -> snapshotChunk(recipient, plugin, world,
                            x1, minBlockY, z1, x2, maxBlockY, z2);
                    // The player may have teleported since the request, or the box
                    // may straddle a region boundary. Entity ownership is not block ownership.
                    if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) snapshot.run();
                    else Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, snapshot);
                }
            }
        }, null, 0);
    }

    private static void snapshotChunk(Player recipient, Plugin plugin, World world,
                                      int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (!world.isChunkLoaded(minX >> 4, minZ >> 4)) return;
        Map<Position, BlockData> changes = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    changes.put(Position.block(x, y, z), world.getBlockAt(x, y, z).getBlockData());
                }
            }
        }
        if (changes.isEmpty()) return;
        Runnable send = () -> {
            // Discard an old world's snapshot if a teleport won the scheduling race.
            if (recipient.isOnline() && recipient.getWorld() == world) recipient.sendMultiBlockChange(changes, false);
        };
        if (Bukkit.isOwnedByCurrentRegion(recipient)) send.run();
        else FoliaCompatUtil.runTaskForEntity(recipient, plugin, send, null, 0);
    }
}
