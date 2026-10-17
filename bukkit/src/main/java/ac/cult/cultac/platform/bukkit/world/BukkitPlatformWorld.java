package ac.cult.cultac.platform.bukkit.world;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.platform.api.world.PlatformChunk;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import java.util.UUID;
import net.minecraft.world.level.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

public record BukkitPlatformWorld(@NotNull World bukkitWorld) implements PlatformWorld {

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return bukkitWorld.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public int getBlockAt(int x, int y, int z) {
        if (BukkitPlatformChunk.isIllegalY(bukkitWorld, y)) return BlockIds.AIR.defaultState();
        return BukkitPlatformChunk.toModelState(Block.getId(((org.bukkit.craftbukkit.block.data.CraftBlockData)
                        bukkitWorld.getBlockAt(x, y, z).getBlockData())
                .getState()));
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
