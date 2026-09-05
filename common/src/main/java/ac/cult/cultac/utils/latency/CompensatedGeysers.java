package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-visible potent-sulfur block entity tickers, in vanilla ticker order.
 * LinkedHashMap is intentional: block entity launch impulses are sequential and
 * their per-geyser velocity caps make packet/insertion order observable.
 */
public final class CompensatedGeysers {
    private final Map<Long, BlockPos> tickers = new LinkedHashMap<>();

    public void replaceChunk(int chunkX, int chunkZ, List<BlockPos> positions) {
        removeChunk(chunkX, chunkZ);
        for (BlockPos position : positions) {
            tickers.put(position.asLong(), position.immutable());
        }
    }

    public void updateBlock(BlockPos position, BlockState oldState, BlockState newState) {
        boolean hadTicker = hasTicker(oldState);
        boolean hasTicker = hasTicker(newState);
        if (hadTicker == hasTicker) {
            return;
        }
        if (hasTicker) {
            tickers.put(position.asLong(), position.immutable());
        } else {
            tickers.remove(position.asLong());
        }
    }

    public List<BlockPos> getTickersInOrder(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos position : tickers.values()) {
            if (position.getX() >= minX && position.getX() <= maxX
                    && position.getY() >= minY && position.getY() <= maxY
                    && position.getZ() >= minZ && position.getZ() <= maxZ) {
                result.add(position);
            }
        }
        return result;
    }

    public void removeChunk(int chunkX, int chunkZ) {
        tickers.values().removeIf(position -> position.getX() >> 4 == chunkX && position.getZ() >> 4 == chunkZ);
    }

    public void clear() {
        tickers.clear();
    }

    public static boolean hasTicker(BlockState state) {
        if (state == null) {
            return false;
        }
        BlockData data = SpigotConversionUtil.fromNmsBlockState(state);
        return "POTENT_SULFUR".equals(data.getMaterial().name())
                && !data.getAsString(false).contains("potent_sulfur_state=dry");
    }
}
