package ac.cult.cultac.bedrock.player;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import java.util.Set;

/** Projects Bedrock's independently updated block layers into the shared world cache. */
public record BedrockBlockLayers(int primary, int extra) {
    private static final Set<String> WATER =
            DataTables.defaults().tags().getOrDefault("fluid:minecraft:water", Set.of());

    public static BedrockBlockLayers fromJava(int state) {
        // Geyser BlockRegistryPopulator also puts aquatic plants and bubble columns
        // in layer 1; they have intrinsic water rather than a WATERLOGGED property.
        boolean waterlogged = !BlockIds.is(state, BlockIds.WATER) && isWater(state);
        return new BedrockBlockLayers(
                dry(state), waterlogged ? BlockIds.WATER.defaultState() : BlockIds.AIR.defaultState());
    }

    public static int dry(int state) {
        return BlockProps.WATERLOGGED.has(state) ? BlockProps.WATERLOGGED.with(state, false) : state;
    }

    public BedrockBlockLayers withLayer(int layer, int state) {
        return switch (layer) {
            case 0 -> new BedrockBlockLayers(dry(state), extra);
            case 1 -> new BedrockBlockLayers(primary, state);
            default -> throw new IllegalArgumentException("Unknown Bedrock block layer " + layer);
        };
    }

    private static boolean isAir(int state) {
        return DataTables.defaults().registry().facts(state).has(StateFacts.AIR);
    }

    private static boolean isWater(int state) {
        return WATER.contains(DataTables.defaults().registry().facts(state).fluid());
    }

    public int combined() {
        if (isAir(primary)) return isAir(extra) ? primary : extra;
        if (BlockProps.WATERLOGGED.has(primary)) {
            return BlockProps.WATERLOGGED.with(primary, isWater(extra));
        }
        return primary;
    }
}
