package ac.cult.cultac.bedrock.player;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Projects Bedrock's independently updated block layers into the shared world cache. */
public record BedrockBlockLayers(BlockState primary, BlockState extra) {
    public static BedrockBlockLayers fromJava(BlockState state) {
        // Geyser BlockRegistryPopulator also puts aquatic plants and bubble columns
        // in layer 1; they have intrinsic water rather than a WATERLOGGED property.
        boolean waterlogged = !state.is(Blocks.WATER) && state.getFluidState().is(FluidTags.WATER);
        return new BedrockBlockLayers(dry(state),
                waterlogged ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    public static BlockState dry(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
                ? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
    }

    public BedrockBlockLayers withLayer(int layer, BlockState state) {
        return switch (layer) {
            case 0 -> new BedrockBlockLayers(dry(state), extra);
            case 1 -> new BedrockBlockLayers(primary, state);
            default -> throw new IllegalArgumentException("Unknown Bedrock block layer " + layer);
        };
    }

    public BlockState combined() {
        if (primary.isAir()) return extra.isAir() ? primary : extra;
        if (primary.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return primary.setValue(BlockStateProperties.WATERLOGGED, extra.getFluidState().is(FluidTags.WATER));
        }
        return primary;
    }
}
