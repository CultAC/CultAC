package ac.grim.grimac.platform.bukkit.world;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.platform.api.Platform;
import ac.grim.grimac.platform.api.world.PlatformChunk;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import lombok.RequiredArgsConstructor;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class BukkitPlatformChunk implements PlatformChunk {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private static final boolean CUSTOMIZABLE_WORLD_HEIGHT = SERVER_VERSION.getProtocolVersion() >= 755;
    private static final Map<BlockData, Integer> blockDataToId = GrimAPI.INSTANCE.getPlatform() == Platform.FOLIA ? new ConcurrentHashMap<>() : new HashMap<>();
    private static final boolean isFlat = SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_13);
    private final @NotNull Chunk chunk;

    @Override
    public int getBlockID(int x, int y, int z) {
        if (isIllegalPosition(chunk.getWorld(), x, y, z)) {
            return Block.getId(Blocks.AIR.defaultBlockState());
        }

        org.bukkit.block.Block block = chunk.getBlock(x, y, z);

        return isFlat // Cache blockDataToID because Strings are expensive
                ? blockDataToId.computeIfAbsent(block.getBlockData(), data -> Block.getId(NmsBlockTags.toNmsState(data)))
                : getLegacyBlockID(block);
    }

    @SuppressWarnings({ "deprecation", "UnstableApiUsage" })
    private static int getLegacyBlockID(@NotNull org.bukkit.block.Block block) {
        return (block.getType().getId() << 4) | block.getData();
    }

    public static boolean isIllegalY(@NotNull World world, int y) {
        int minY = CUSTOMIZABLE_WORLD_HEIGHT ? world.getMinHeight() : 0;
        int maxY = CUSTOMIZABLE_WORLD_HEIGHT ? world.getMaxHeight() : 255;
        return minY > y || y > maxY;
    }

    public static boolean isIllegalPosition(World world, int x, int y, int z) {
        return isIllegalY(world, y) || x < 0 || x > 15 || z < 0 || z > 15;
    }
}
