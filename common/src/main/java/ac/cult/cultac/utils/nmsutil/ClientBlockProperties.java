package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.value.Direction;

/** Property reads shared by model-ID consumers and the remaining native boundary adapters. */
public final class ClientBlockProperties {
    private static final float[] EXPLOSION_RESISTANCE = explosionResistance();

    private ClientBlockProperties() {}

    public static float defaultDestroyTime(int state) {
        var registry = DataTables.defaults().registry();
        return registry.facts(registry.block(state).defaultState()).destroyTime();
    }

    public static float explosionResistance(int state) {
        return EXPLOSION_RESISTANCE[DataTables.defaults().registry().blockIndex(state)];
    }

    private static float[] explosionResistance() {
        var blocks = DataTables.defaults().registry().blocks();
        float[] result = new float[blocks.size()];
        for (int index = 0; index < result.length; index++)
            result[index] = Float.parseFloat(blocks.get(index).bindings().get("explosionResistance"));
        return result;
    }

    public static boolean isFence(int state) {
        return BlockTags.FENCES.test(state) || BlockFamilies.FENCE.test(state);
    }

    public static boolean isWall(int state) {
        return BlockTags.WALLS.test(state) || BlockFamilies.WALL.test(state);
    }

    public static boolean isFenceGate(int state) {
        return BlockTags.FENCE_GATES.test(state) || BlockFamilies.FENCE_GATE.test(state);
    }

    public static boolean isBed(int state) {
        return BlockTags.BEDS.test(state) || BlockFamilies.BED.test(state);
    }

    public static boolean isTrapdoor(int state) {
        return BlockTags.TRAPDOORS.test(state) || BlockFamilies.TRAP_DOOR.test(state);
    }

    public static boolean isSlab(int state) {
        return BlockTags.SLABS.test(state) || BlockFamilies.SLAB.test(state);
    }

    public static boolean isShulkerBox(int state) {
        return BlockTags.SHULKER_BOXES.test(state) || BlockFamilies.SHULKER_BOX.test(state);
    }

    public static boolean isConnectingBlock(int state) {
        int material = DataTables.defaults().registry().block(state).defaultState();
        return BlockTags.STAIRS.test(material)
                || isWall(material)
                || isFence(material)
                || isFenceGate(material)
                || BlockTags.BARS.test(material)
                || BlockFamilies.IRON_BARS.test(material)
                || BlockProps.BELL_ATTACHMENT.has(material)
                || BlockProps.TILT.has(material)
                || BlockFamilies.BIG_DRIPLEAF_STEM.test(material)
                || BlockIds.is(material, BlockIds.POINTED_DRIPSTONE)
                || BlockFamilies.CHORUS_PLANT.test(material)
                || BlockProps.CHEST_TYPE.has(material);
    }

    public static Direction facing(int state) {
        if (BlockProps.FACING.has(state)) return Direction.from3DDataValue(BlockProps.FACING.value(state));
        if (BlockProps.HORIZONTAL_FACING.has(state))
            return Direction.from3DDataValue(BlockProps.HORIZONTAL_FACING.value(state));
        return Direction.UP;
    }

    public static boolean hasDirection(int state, Direction face) {
        var property = switch (face) {
            case DOWN -> BlockProps.DOWN;
            case UP -> BlockProps.UP;
            case NORTH -> BlockProps.NORTH;
            case SOUTH -> BlockProps.SOUTH;
            case WEST -> BlockProps.WEST;
            case EAST -> BlockProps.EAST;
        };
        return property.has(state) && property.booleanValue(state);
    }
}
