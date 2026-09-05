package ac.cult.cultac.utils.blockplace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

final class PaperPatchedCakeUseResolver {
    private static final int PLACEMENT_FLAGS = Block.UPDATE_ALL_IMMEDIATE;
    private static final int MAX_UPDATE_DEPTH = 512;

    private PaperPatchedCakeUseResolver() {
    }

    static NmsBlockPlaceResolver.BlockUseResult trySimulate(
            PlacementWorldAdapter world,
            PlacementSnapshot snapshot,
            BlockState state,
            BlockPos pos
    ) {
        if (state.getBlock() == Blocks.CAKE) {
            return simulateCakeUse(world, snapshot, state, pos);
        }
        if (state.getBlock() instanceof CandleCakeBlock
                && snapshot.getItemStack().isEmpty()
                && candleCakeHit(snapshot)
                && state.getValue(BlockStateProperties.LIT)) {
            world.setBlock(pos, state.setValue(BlockStateProperties.LIT, false), PLACEMENT_FLAGS, MAX_UPDATE_DEPTH);
            return NmsBlockPlaceResolver.BlockUseResult.success(world.buildResult(pos).getChangedBlocks(), false);
        }
        if (state.getBlock() instanceof CandleCakeBlock && needsCakeEatingPatch(snapshot, state)) {
            return snapshot.getHand() == InteractionHand.MAIN_HAND
                    ? simulateVanillaCakeEat(world, snapshot, Blocks.CAKE.defaultBlockState(), pos, false)
                    : NmsBlockPlaceResolver.BlockUseResult.failed("off-hand candle cake use passed");
        }
        return null;
    }

    private static boolean needsCakeEatingPatch(PlacementSnapshot snapshot, BlockState state) {
        ItemStack itemStack = snapshot.getItemStack();
        if (itemStack.is(Items.FLINT_AND_STEEL) || itemStack.is(Items.FIRE_CHARGE)) {
            return false;
        }
        return !candleCakeHit(snapshot) || !itemStack.isEmpty() || !state.getValue(BlockStateProperties.LIT);
    }

    private static NmsBlockPlaceResolver.BlockUseResult simulateCakeUse(
            PlacementWorldAdapter world,
            PlacementSnapshot snapshot,
            BlockState state,
            BlockPos pos
    ) {
        ItemStack itemStack = snapshot.getItemStack();
        if (state.getValue(CakeBlock.BITES) == 0 && Block.byItem(itemStack.getItem()) instanceof CandleBlock candleBlock) {
            // Paper wraps vanilla cake changes in Bukkit events and ServerPlayer-only updates.
            // Mirror the client-side state change directly on the compensated world.
            world.setBlock(pos, CandleCakeBlock.byCandle(candleBlock), PLACEMENT_FLAGS, MAX_UPDATE_DEPTH);
            return NmsBlockPlaceResolver.BlockUseResult.success(world.buildResult(pos).getChangedBlocks(), true);
        }

        return snapshot.getHand() == InteractionHand.MAIN_HAND
                ? simulateVanillaCakeEat(world, snapshot, state, pos, true)
                : NmsBlockPlaceResolver.BlockUseResult.failed("off-hand cake use passed");
    }

    private static boolean candleCakeHit(PlacementSnapshot snapshot) {
        return snapshot.getClickLocation().y - snapshot.getClickedBlockPos().getY() > 0.5D;
    }

    private static NmsBlockPlaceResolver.BlockUseResult simulateVanillaCakeEat(
            PlacementWorldAdapter world,
            PlacementSnapshot snapshot,
            BlockState state,
            BlockPos pos,
            boolean emptyHandConsumesWhenFull
    ) {
        if (!canEatCake(snapshot)) {
            return emptyHandConsumesWhenFull && snapshot.getMainHandItemStack().isEmpty()
                    ? NmsBlockPlaceResolver.BlockUseResult.success(List.of(), false)
                    : NmsBlockPlaceResolver.BlockUseResult.failed("player cannot eat cake");
        }

        int bites = state.getValue(CakeBlock.BITES);
        if (bites < CakeBlock.MAX_BITES) {
            world.setBlock(pos, state.setValue(CakeBlock.BITES, bites + 1), Block.UPDATE_ALL, MAX_UPDATE_DEPTH);
        } else {
            world.removeBlock(pos, false);
        }
        return NmsBlockPlaceResolver.BlockUseResult.success(world.buildResult(pos).getChangedBlocks(), false);
    }

    private static boolean canEatCake(PlacementSnapshot snapshot) {
        return snapshot.isCreative() || snapshot.getFoodLevel() < 20;
    }
}
