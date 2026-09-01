package ac.grim.grimac.utils.nmsutil;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class NmsBlockTags {
    private NmsBlockTags() {
    }

    public static BlockState toNmsState(BlockData data) {
        if (data instanceof CraftBlockData craftBlockData) {
            return craftBlockData.getState();
        }

        Block block = toNmsBlock(data == null ? null : data.getMaterial());
        return block == null ? Blocks.AIR.defaultBlockState() : block.defaultBlockState();
    }

    public static int getInt(BlockState state, IntegerProperty property, int fallback) {
        return state.hasProperty(property) ? state.getValue(property) : fallback;
    }

    public static boolean getBoolean(BlockState state, BooleanProperty property) {
        return state.hasProperty(property) && state.getValue(property);
    }

    public static boolean hasDirection(BlockState state, BlockFace face) {
        BooleanProperty property = directionProperty(face);
        return property != null && getBoolean(state, property);
    }

    public static BlockFace getFacing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return toBukkitFace(state.getValue(BlockStateProperties.FACING));
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return toBukkitFace(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return BlockFace.UP;
    }

    private static BlockFace toBukkitFace(Direction direction) {
        return switch (direction) {
            case DOWN -> BlockFace.DOWN;
            case UP -> BlockFace.UP;
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case WEST -> BlockFace.WEST;
            case EAST -> BlockFace.EAST;
        };
    }

    private static BooleanProperty directionProperty(BlockFace face) {
        return switch (face) {
            case DOWN -> BlockStateProperties.DOWN;
            case UP -> BlockStateProperties.UP;
            case NORTH -> BlockStateProperties.NORTH;
            case SOUTH -> BlockStateProperties.SOUTH;
            case WEST -> BlockStateProperties.WEST;
            case EAST -> BlockStateProperties.EAST;
            default -> null;
        };
    }

    public static Block toNmsBlock(Material material) {
        return isModern(material) ? CraftMagicNumbers.getBlock(material) : null;
    }

    public static Item toNmsItem(Material material) {
        return isModern(material) ? CraftMagicNumbers.getItem(material) : null;
    }

    private static boolean isModern(Material material) {
        return material != null && !material.isLegacy();
    }

    public static boolean isWater(BlockData data) {
        return toNmsState(data).getFluidState().getType().isSame(Fluids.WATER);
    }

    public static boolean isWaterSource(BlockData data) {
        FluidState fluidState = toNmsState(data).getFluidState();
        return fluidState.isSourceOfType(Fluids.WATER);
    }

    public static boolean isNoPlaceLiquid(Material material) {
        Block block = toNmsBlock(material);
        return block instanceof LiquidBlock liquidBlock
                && liquidBlock.defaultBlockState().getFluidState().isSource();
    }

    public static boolean isReplaceable(Material material) {
        Block block = toNmsBlock(material);
        return block == null || block.defaultBlockState().isAir() || block.defaultBlockState().canBeReplaced();
    }

    public static boolean isShapeExceedsCube(Material material) {
        Block block = toNmsBlock(material);
        return block != null && block.defaultBlockState().hasLargeCollisionShape();
    }

    public static boolean isConnectingBlock(Material material) {
        Block block = toNmsBlock(material);
        if (block == null) {
            return false;
        }
        BlockState state = block.defaultBlockState();

        return isStairs(state)
                || isWall(block, state)
                || isFence(block, state)
                || isFenceGate(block, state)
                || state.is(BlockTags.BARS)
                || block instanceof IronBarsBlock
                || state.hasProperty(BlockStateProperties.BELL_ATTACHMENT)
                || state.hasProperty(BlockStateProperties.TILT)
                || block instanceof BigDripleafStemBlock
                || material == Material.POINTED_DRIPSTONE
                || block instanceof ChorusPlantBlock
                || state.hasProperty(BlockStateProperties.CHEST_TYPE);
    }

    public static boolean hasBlockTag(Material material, TagKey<Block> tag) {
        Block block = toNmsBlock(material);
        return tag != null && block != null && block.defaultBlockState().is(tag);
    }

    public static Set<Material> blockValues(TagKey<Block> tag) {
        if (tag == null) {
            return Set.of();
        }

        Set<Material> resolved = Arrays.stream(Material.values())
                .filter(NmsBlockTags::isModern)
                .filter(Material::isBlock)
                .filter(material -> hasBlockTag(material, tag))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(resolved);
    }

    public static boolean isFence(Material material) {
        Block block = toNmsBlock(material);
        return block != null && isFence(block, block.defaultBlockState());
    }

    public static boolean isWall(Material material) {
        Block block = toNmsBlock(material);
        return block != null && isWall(block, block.defaultBlockState());
    }

    public static boolean isFenceGate(Material material) {
        Block block = toNmsBlock(material);
        return block != null && isFenceGate(block, block.defaultBlockState());
    }

    public static boolean isSlab(Material material) {
        Block block = toNmsBlock(material);
        return block != null && (block.defaultBlockState().is(BlockTags.SLABS) || block instanceof SlabBlock);
    }

    public static boolean isSlab(BlockState state) {
        return state != null && (state.is(BlockTags.SLABS) || state.getBlock() instanceof SlabBlock);
    }

    public static boolean isTrapdoor(Material material) {
        Block block = toNmsBlock(material);
        return block != null && (block.defaultBlockState().is(BlockTags.TRAPDOORS) || block instanceof TrapDoorBlock);
    }

    public static boolean isTrapdoor(BlockState state) {
        return state != null && (state.is(BlockTags.TRAPDOORS) || state.getBlock() instanceof TrapDoorBlock);
    }

    public static boolean isDoor(Material material) {
        Block block = toNmsBlock(material);
        return block != null && (block.defaultBlockState().is(BlockTags.DOORS) || block instanceof DoorBlock);
    }

    public static boolean isBed(Material material) {
        Block block = toNmsBlock(material);
        return block != null && (block.defaultBlockState().is(BlockTags.BEDS) || block instanceof BedBlock);
    }

    public static boolean isShulkerBox(Material material) {
        Block block = toNmsBlock(material);
        return block != null && (block.defaultBlockState().is(BlockTags.SHULKER_BOXES) || block instanceof ShulkerBoxBlock);
    }

    public static boolean isShulkerBox(BlockState state) {
        return state != null && (state.is(BlockTags.SHULKER_BOXES) || state.getBlock() instanceof ShulkerBoxBlock);
    }

    private static boolean isStairs(BlockState state) {
        return state.is(BlockTags.STAIRS);
    }

    private static boolean isFence(Block block, BlockState state) {
        return state.is(BlockTags.FENCES) || block instanceof FenceBlock;
    }

    private static boolean isWall(Block block, BlockState state) {
        return state.is(BlockTags.WALLS) || block instanceof WallBlock;
    }

    private static boolean isFenceGate(Block block, BlockState state) {
        return state.is(BlockTags.FENCE_GATES) || block instanceof FenceGateBlock;
    }

    public static boolean isPlaceableWaterBucket(Material material) {
        Fluid content = bucketContent(material);
        return content != null && content.isSame(Fluids.WATER);
    }

    public static Material transformBucketMaterial(Material material) {
        Fluid content = bucketContent(material);
        if (content == null) {
            return null;
        }
        if (content.isSame(Fluids.WATER)) {
            return Material.WATER;
        }
        if (content.isSame(Fluids.LAVA)) {
            return Material.LAVA;
        }
        return null;
    }

    public static boolean isCompostable(Material material) {
        Item item = toNmsItem(material);
        return item != null && ComposterBlock.COMPOSTABLES.containsKey(item);
    }

    private static Fluid bucketContent(Material material) {
        Item item = toNmsItem(material);
        return item instanceof BucketItem bucketItem ? bucketItem.getContent() : null;
    }
}
