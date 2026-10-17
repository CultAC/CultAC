package ac.cult.cultac.platform.bukkit.world;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.platform.api.world.PlatformChunk;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Block;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
public class BukkitPlatformChunk implements PlatformChunk {
    private static final ModelBlockStates MODEL_STATES =
            ModelBlockStates.load(ProtocolVersion.of(SharedConstants.getProtocolVersion()), ProtocolVersion.V26_3);

    private static final Map<BlockData, Integer> blockDataToId =
            CultAPI.INSTANCE.getPlatform() == Platform.FOLIA ? new ConcurrentHashMap<>() : new HashMap<>();
    private final @NotNull Chunk chunk;

    @Override
    public int getBlockID(int x, int y, int z) {
        if (isIllegalPosition(chunk.getWorld(), x, y, z)) {
            return BlockIds.AIR.defaultState();
        }

        org.bukkit.block.Block block = chunk.getBlock(x, y, z);

        // Cache blockDataToID because Strings are expensive
        return blockDataToId.computeIfAbsent(
                block.getBlockData(),
                data -> toModelState(
                        Block.getId(((org.bukkit.craftbukkit.block.data.CraftBlockData) data).getState())));
    }

    static int toModelState(int hostState) {
        return MODEL_STATES.toModel(hostState);
    }

    public static boolean isIllegalY(@NotNull World world, int y) {
        return world.getMinHeight() > y || y > world.getMaxHeight();
    }

    public static boolean isIllegalPosition(World world, int x, int y, int z) {
        return isIllegalY(world, y) || x < 0 || x > 15 || z < 0 || z > 15;
    }
}
