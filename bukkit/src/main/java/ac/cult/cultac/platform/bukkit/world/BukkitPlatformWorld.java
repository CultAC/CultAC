package ac.cult.cultac.platform.bukkit.world;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformChunk;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

public record BukkitPlatformWorld(@NotNull World bukkitWorld) implements PlatformWorld {

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());
    private static final boolean LEGACY_SERVER_VERSION = SERVER_VERSION.isOlderThanOrEquals(ClientVersion.V_1_12_2);

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return bukkitWorld.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public int getBlockAt(int x, int y, int z) {
        if (LEGACY_SERVER_VERSION) {
            org.bukkit.block.Block block = bukkitWorld.getBlockAt(x, y, z);
            @SuppressWarnings({"deprecation", "UnstableApiUsage"})
            int blockId = (block.getType().getId() << 4) | block.getData();
            return BukkitPlatformChunk.toModelState(blockId);
        } else {
            if (BukkitPlatformChunk.isIllegalY(bukkitWorld, y)) return BlockIds.AIR.defaultState();
            return BukkitPlatformChunk.toModelState(Block.getId(((org.bukkit.craftbukkit.block.data.CraftBlockData)
                            bukkitWorld.getBlockAt(x, y, z).getBlockData())
                    .getState()));
        }
    }

    @Override
    public String getName() {
        return bukkitWorld.getName();
    }

    @Override
    public @NotNull UUID getUID() {
        return this.bukkitWorld.getUID();
    }

    @Override
    public PlatformChunk getChunkAt(int currChunkX, int currChunkZ) {
        return new BukkitPlatformChunk(bukkitWorld.getChunkAt(currChunkX, currChunkZ));
    }

    @Override
    public boolean isLoaded() {
        return Bukkit.getWorld(bukkitWorld.getUID()) != null;
    }
}
