package ac.grim.grimac.utils.blockplace;

import ac.grim.grimac.utils.latency.CompensatedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public interface PlacementBlockAccess {
    BlockState getBlockStateAt(BlockPos pos);

    FluidState getFluidIfLoaded(BlockPos pos);

    boolean isChunkLoaded(int chunkX, int chunkZ);

    static PlacementBlockAccess fromCompensatedWorld(CompensatedWorld compensatedWorld) {
        return new PlacementBlockAccess() {
            @Override
            public BlockState getBlockStateAt(BlockPos pos) {
                return compensatedWorld.getBlockStateAt(pos);
            }

            @Override
            public FluidState getFluidIfLoaded(BlockPos pos) {
                return compensatedWorld.getFluidIfLoaded(pos);
            }

            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return compensatedWorld.isChunkLoaded(chunkX, chunkZ);
            }
        };
    }
}
