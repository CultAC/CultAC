package ac.grim.grimac.utils.blockplace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ScaffoldingBlockItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Thin access boundary around client behavior patched by Paper.
 * MCP-Reborn c59f05e: BlockItem.java:41-45; StandingAndWallBlockItem.java:23-44;
 * ConcretePowderBlock.java:44-76; FenceGateBlock.java:147-168; RepeaterBlock.java:40-48;
 * ComparatorBlock.java:131-140; ComposterBlock.java:273-303; ButtonBlock.java:88-115.
 */
final class NmsClientInteraction {
    // wallBlock was public before 26.2 and is protected now; read it reflectively.
    private static final java.lang.reflect.Field STANDING_AND_WALL_BLOCK = resolveWallBlockField();

    private NmsClientInteraction() {
    }

    private static java.lang.reflect.Field resolveWallBlockField() {
        try {
            java.lang.reflect.Field field = StandingAndWallBlockItem.class.getDeclaredField("wallBlock");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Block wallBlock(StandingAndWallBlockItem item) {
        try {
            return (Block) STANDING_AND_WALL_BLOCK.get(item);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static @Nullable BlockState placementState(BlockItem item, BlockPlaceContext context) {
        // MushroomBlock.java:83-86 consults client light, which Grim does not retain.
        // Decline prediction instead of fabricating light or reading the live world.
        if (item.getBlock() instanceof MushroomBlock) {
            return null;
        }
        if (item.getBlock() instanceof ConcretePowderBlock) {
            return concretePowderState(item.getBlock(), context);
        }
        if (item instanceof StandingAndWallBlockItem standingAndWall) {
            return standingAndWallState(standingAndWall, context);
        }
        if (item instanceof GameMasterBlockItem && context.getPlayer() != null
                && !context.getPlayer().canUseGameMasterBlocks()) {
            return null;
        }

        BlockState state = item.getBlock().getStateForPlacement(context);
        return state != null
                && (item instanceof ScaffoldingBlockItem || state.canSurvive(context.getLevel(), context.getClickedPos()))
                && context.getLevel().isUnobstructed(state, context.getClickedPos(), CollisionContext.empty())
                ? state
                : null;
    }

    private static @Nullable BlockState standingAndWallState(StandingAndWallBlockItem item, BlockPlaceContext context) {
        BlockState wallState = wallBlock(item).getStateForPlacement(context);
        Direction attachment = item instanceof HangingSignItem ? Direction.UP : Direction.DOWN;
        for (Direction direction : context.getNearestLookingDirections()) {
            if (direction == attachment.getOpposite()) continue;
            BlockState candidate = direction == attachment ? item.getBlock().getStateForPlacement(context) : wallState;
            if (candidate != null && canPlaceStandingAndWall(item, candidate, context)) {
                return context.getLevel().isUnobstructed(candidate, context.getClickedPos(), CollisionContext.empty())
                        ? candidate : null;
            }
        }
        return null;
    }

    private static boolean canPlaceStandingAndWall(
            StandingAndWallBlockItem item,
            BlockState state,
            BlockPlaceContext context
    ) {
        return (!(item instanceof HangingSignItem) || !(state.getBlock() instanceof WallHangingSignBlock wall)
                || wall.canPlace(state, context.getLevel(), context.getClickedPos()))
                && state.canSurvive(context.getLevel(), context.getClickedPos());
    }

    private static BlockState concretePowderState(Block powder, BlockPlaceContext context) {
        BlockState replaced = context.getLevel().getBlockState(context.getClickedPos());
        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN && !replaced.getFluidState().is(FluidTags.WATER)) continue;
            BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(direction));
            if (neighbor.getFluidState().is(FluidTags.WATER)
                    && !neighbor.isFaceSturdy(context.getLevel(), context.getClickedPos(), direction.getOpposite())) {
                return concreteFor(powder).defaultBlockState();
            }
        }
        return replaced.getFluidState().is(FluidTags.WATER) ? concreteFor(powder).defaultBlockState() : powder.defaultBlockState();
    }

    private static Block concreteFor(Block powder) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(powder);
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(
                id.getPath().substring(0, id.getPath().length() - "_powder".length())));
    }

    static boolean place(BlockItem item, BlockPlaceContext context, BlockState state) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (item instanceof DoubleHighBlockItem) {
            level.setBlock(pos.above(), level.isWaterAt(pos.above())
                    ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 27);
        }
        boolean placed = level.setBlock(pos, state, item instanceof BedItem ? 26 : 11);
        if (placed && item instanceof DoubleHighBlockItem) {
            BlockState upper = state.getBlock() instanceof DoublePlantBlock plant
                    ? DoublePlantBlock.copyWaterloggedFrom(level, pos.above(),
                    plant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER))
                    : state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                    DoubleBlockHalf.UPPER);
            level.setBlock(pos.above(), upper, 3);
        }
        return placed;
    }

    static @Nullable Block strippedBlock(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(
                id.getNamespace(), "stripped_" + id.getPath())).orElse(null);
    }

    static boolean clientConsumesHeldUse(Item item) {
        // MCP-Reborn c59f05e: SnowballItem.java:24-42, EggItem.java:24-42,
        // EnderpearlItem.java:22-40, ExperienceBottleItem.java:22-40,
        // ThrowablePotionItem.java:23-31, and WindChargeItem.java:27-53.
        return item instanceof SnowballItem
                || item instanceof EggItem
                || item instanceof EnderpearlItem
                || item instanceof ExperienceBottleItem
                || item instanceof ThrowablePotionItem
                || item instanceof WindChargeItem;
    }

    static @Nullable InteractionResult paperPatchedBlockUse(BlockState state, Level level, BlockPos pos) {
        if (state.getBlock() instanceof JukeboxBlock && state.getValue(JukeboxBlock.HAS_RECORD)) {
            return InteractionResult.SUCCESS;
        }
        if (!(state.getBlock() instanceof ButtonBlock button)) return null;
        if (state.getValue(ButtonBlock.POWERED)) return InteractionResult.CONSUME;
        button.press(state, level, pos, null);
        return InteractionResult.SUCCESS;
    }

    static @Nullable InteractionResult playerFreeBlockUse(
            BlockState state,
            Level level,
            BlockPos pos,
            PlacementSnapshot snapshot
    ) {
        Block block = state.getBlock();
        if (block instanceof FenceGateBlock) {
            Direction facing = snapshot.getHorizontalDirection();
            if (!state.getValue(FenceGateBlock.OPEN)
                    && state.getValue(FenceGateBlock.FACING) == facing.getOpposite()) {
                state = state.setValue(FenceGateBlock.FACING, facing);
            }
            level.setBlock(pos, state.cycle(FenceGateBlock.OPEN), 10);
            return InteractionResult.SUCCESS;
        }
        if (snapshot.getGameMode() == org.bukkit.GameMode.ADVENTURE
                || snapshot.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return block instanceof RepeaterBlock || block instanceof ComparatorBlock
                    ? InteractionResult.PASS : null;
        }
        if (block instanceof RepeaterBlock) {
            level.setBlock(pos, state.cycle(RepeaterBlock.DELAY), 3);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof ComparatorBlock) {
            level.setBlock(pos, state.cycle(ComparatorBlock.MODE), 2);
            return InteractionResult.SUCCESS;
        }
        if (block instanceof ComposterBlock && state.getValue(ComposterBlock.LEVEL) == 8) {
            level.setBlock(pos, state.setValue(ComposterBlock.LEVEL, 0), 3);
            return InteractionResult.SUCCESS;
        }
        return null;
    }

    static @Nullable InteractionResult playerFreeEmptyHandUse(
            BlockState state,
            Level level,
            BlockHitResult hitResult
    ) {
        Block block = state.getBlock();
        if (!(block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof LeverBlock)) {
            return null;
        }
        return state.useWithoutItem(level, null, hitResult);
    }

    static @Nullable InteractionResult playerDependentClientNoop(BlockState state, PlacementSnapshot snapshot) {
        if (state.getBlock() instanceof EnderChestBlock) {
            return InteractionResult.SUCCESS;
        }
        if (state.getBlock() instanceof DaylightDetectorBlock
                && snapshot.getGameMode() != org.bukkit.GameMode.ADVENTURE
                && snapshot.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            return InteractionResult.SUCCESS;
        }
        return null;
    }
}
