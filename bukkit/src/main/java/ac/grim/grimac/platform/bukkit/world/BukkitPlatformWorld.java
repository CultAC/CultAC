package ac.grim.grimac.platform.bukkit.world;

import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.platform.api.world.PlatformChunk;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record BukkitPlatformWorld(@NotNull World bukkitWorld) implements PlatformWorld {

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());
    private static final boolean LEGACY_SERVER_VERSION = SERVER_VERSION.isOlderThanOrEquals(ClientVersion.V_1_12_2);

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return bukkitWorld.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public BlockState getBlockAt(int x, int y, int z) {
        if (LEGACY_SERVER_VERSION) {
            org.bukkit.block.Block block = bukkitWorld.getBlockAt(x, y, z);
            @SuppressWarnings({"deprecation", "UnstableApiUsage"})
            int blockId = (block.getType().getId() << 4) | block.getData();
            return Block.stateById(blockId);
        } else {
            if (BukkitPlatformChunk.isIllegalY(bukkitWorld, y)) return Blocks.AIR.defaultBlockState();
            return NmsBlockTags.toNmsState(bukkitWorld.getBlockAt(x, y, z).getBlockData());
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
