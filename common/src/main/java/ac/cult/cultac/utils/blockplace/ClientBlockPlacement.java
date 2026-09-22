package ac.cult.cultac.utils.blockplace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

/** Vanilla client placement branches patched by server forks to read live world configuration. */
final class ClientBlockPlacement {
    private static final TagKey<Block> CACTUS_SUPPORT = cactusSupport();

    private ClientBlockPlacement() {
    }

    static @Nullable BlockState state(Block block, BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        if (block instanceof SculkShriekerBlock) {
            // MCP SculkShriekerBlock#getStateForPlacement: player placement never enables summoning.
            return block.defaultBlockState().setValue(SculkShriekerBlock.WATERLOGGED,
                    context.getLevel().getFluidState(pos).getType() == Fluids.WATER);
        }
        if (block instanceof CampfireBlock) {
            // MCP CampfireBlock#getStateForPlacement and isSmokeSource.
            boolean water = context.getLevel().getFluidState(pos).getType() == Fluids.WATER;
            return block.defaultBlockState().setValue(CampfireBlock.WATERLOGGED, water)
                    .setValue(CampfireBlock.LIT, !water)
                    .setValue(CampfireBlock.SIGNAL_FIRE, context.getLevel().getBlockState(pos.below()).getBlock() == Blocks.HAY_BLOCK)
                    .setValue(CampfireBlock.FACING, context.getHorizontalDirection());
        }
        if (block instanceof CoralBlock) {
            // MCP CoralBlock#getStateForPlacement only additionally schedules its server death tick.
            // Detached client worlds never run scheduled block ticks.
            return block.defaultBlockState();
        }
        return null;
    }

    static boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!(state.getBlock() instanceof CactusBlock)) return state.canSurvive(level, pos);
        // MCP CactusBlock#canSurvive. Leaf 1.21.3 adds a WorldBorder.world config read here.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (level.getBlockState(neighbor).isSolid() || level.getFluidState(neighbor).is(FluidTags.LAVA)) return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return (below.getBlock() == state.getBlock() || below.is(CACTUS_SUPPORT)) && !level.getBlockState(pos.above()).liquid();
    }

    @SuppressWarnings("unchecked")
    private static TagKey<Block> cactusSupport() {
        try {
            java.lang.reflect.Field field;
            try {
                field = BlockTags.class.getField("SUPPORTS_CACTUS");
            } catch (NoSuchFieldException olderRuntime) {
                field = BlockTags.class.getField("SAND");
            }
            return (TagKey<Block>) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
