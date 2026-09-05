package ac.cult.cultac.utils.blockplace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Version-neutral handle for the detached NMS placement level.
 *
 * <p>{@link Level}'s abstract binary contract changes between supported Paper
 * releases, so concrete subclasses are compiled against the Paper generation
 * they implement. Placement logic talks only through this stable handle.</p>
 */
public interface PlacementWorldAdapter {
    Level level();

    PlacementResult buildResult(BlockPos primaryPlacedPosition);

    default BlockState getBlockState(BlockPos pos) {
        return level().getBlockState(pos);
    }

    default boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        return level().setBlock(pos, state, flags, recursionLeft);
    }

    default boolean removeBlock(BlockPos pos, boolean moved) {
        return level().removeBlock(pos, moved);
    }

    default boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        return level().destroyBlock(pos, dropBlock, entity, recursionLeft);
    }
}
