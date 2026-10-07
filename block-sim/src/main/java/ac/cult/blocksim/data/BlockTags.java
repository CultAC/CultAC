package ac.cult.blocksim.data;

/** Immutable model tag memberships compiled once from the generated defaults. */
public enum BlockTags {
    CLIMBABLE("climbable"), STAIRS("stairs"), FENCES("fences"), BARS("bars"), SHULKER_BOXES("shulker_boxes"),
    WALLS("walls"), FENCE_GATES("fence_gates"), BEDS("beds"), TRAPDOORS("trapdoors"),
    STRIDER_WARM_BLOCKS("strider_warm_blocks"), CANDLES("candles"), SLABS("slabs"), DOORS("doors"),
    FALL_DAMAGE_RESETTING("fall_damage_resetting"), BLOCKS_FLUID_FLOW("blocks_fluid_flow");

    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final int[] MEMBERSHIP = membership();
    private final String key;

    BlockTags(String key) { this.key = "block:minecraft:" + key; }

    public boolean test(int state) { return (MEMBERSHIP[REGISTRY.blockIndex(state)] & (1 << ordinal())) != 0; }

    private static int[] membership() {
        int[] result = new int[REGISTRY.blocks().size()];
        var tags = DataTables.defaults().tags();
        for (var tag : values()) {
            var members = tags.getOrDefault(tag.key, java.util.Set.of());
            for (int index = 0; index < result.length; index++)
                if (members.contains(REGISTRY.blocks().get(index).key())) result[index] |= 1 << tag.ordinal();
        }
        return result;
    }
}
