package ac.cult.blocksim.data;

/** Generated 26.3 block handles; state lookup never resolves names. */
public final class BlockIds {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    public static final BlockDefinition AIR = REGISTRY.block(0);
    public static final BlockDefinition ANVIL = REGISTRY.block(12962);
    public static final BlockDefinition BAMBOO = REGISTRY.block(18636);
    public static final BlockDefinition BEACON = REGISTRY.block(11722);
    public static final BlockDefinition BUBBLE_COLUMN = REGISTRY.block(18651);
    public static final BlockDefinition CAULDRON = REGISTRY.block(11194);
    public static final BlockDefinition CAVE_VINES = REGISTRY.block(33607);
    public static final BlockDefinition CAVE_VINES_PLANT = REGISTRY.block(33659);
    public static final BlockDefinition CHEST = REGISTRY.block(5544);
    public static final BlockDefinition CHIPPED_ANVIL = REGISTRY.block(12966);
    public static final BlockDefinition CHORUS_PLANT = REGISTRY.block(16680);
    public static final BlockDefinition COBWEB = REGISTRY.block(2362);
    public static final BlockDefinition CRYING_OBSIDIAN = REGISTRY.block(25177);
    public static final BlockDefinition DAMAGED_ANVIL = REGISTRY.block(12970);
    public static final BlockDefinition END_GATEWAY = REGISTRY.block(16797);
    public static final BlockDefinition END_PORTAL = REGISTRY.block(11202);
    public static final BlockDefinition FARMLAND = REGISTRY.block(6875);
    public static final BlockDefinition FIRE = REGISTRY.block(4962);
    public static final BlockDefinition GLASS = REGISTRY.block(661);
    public static final BlockDefinition GLASS_PANE = REGISTRY.block(10065);
    public static final BlockDefinition GLOWSTONE = REGISTRY.block(8686);
    public static final BlockDefinition GLOW_LICHEN = REGISTRY.block(10251);
    public static final BlockDefinition HONEY_BLOCK = REGISTRY.block(25173);
    public static final BlockDefinition IRON_BARS = REGISTRY.block(9723);
    public static final BlockDefinition KELP = REGISTRY.block(18419);
    public static final BlockDefinition KELP_PLANT = REGISTRY.block(18445);
    public static final BlockDefinition LADDER = REGISTRY.block(7308);
    public static final BlockDefinition LAVA = REGISTRY.block(105);
    public static final BlockDefinition LECTERN = REGISTRY.block(24144);
    public static final BlockDefinition LIGHT = REGISTRY.block(14413);
    public static final BlockDefinition LILY_PAD = REGISTRY.block(10654);
    public static final BlockDefinition MOVING_PISTON = REGISTRY.block(3801);
    public static final BlockDefinition NETHER_PORTAL = REGISTRY.block(8687);
    public static final BlockDefinition OBSIDIAN = REGISTRY.block(4925);
    public static final BlockDefinition PALE_MOSS_CARPET = REGISTRY.block(35554);
    public static final BlockDefinition PISTON = REGISTRY.block(2379);
    public static final BlockDefinition PISTON_HEAD = REGISTRY.block(2387);
    public static final BlockDefinition PITCHER_CROP = REGISTRY.block(16781);
    public static final BlockDefinition PLAYER_HEAD = REGISTRY.block(12818);
    public static final BlockDefinition PLAYER_WALL_HEAD = REGISTRY.block(12835);
    public static final BlockDefinition POINTED_DRIPSTONE = REGISTRY.block(33571);
    public static final BlockDefinition POTENT_SULFUR = REGISTRY.block(28045);
    public static final BlockDefinition POWDER_SNOW = REGISTRY.block(30519);
    public static final BlockDefinition REDSTONE_WIRE = REGISTRY.block(6727);
    public static final BlockDefinition REINFORCED_DEEPSLATE = REGISTRY.block(35442);
    public static final BlockDefinition RESPAWN_ANCHOR = REGISTRY.block(25178);
    public static final BlockDefinition SCAFFOLDING = REGISTRY.block(24094);
    public static final BlockDefinition SEA_LANTERN = REGISTRY.block(14739);
    public static final BlockDefinition SEA_PICKLE = REGISTRY.block(18624);
    public static final BlockDefinition SHELF_MUSHROOM = REGISTRY.block(11227);
    public static final BlockDefinition SLIME_BLOCK = REGISTRY.block(14379);
    public static final BlockDefinition SNOW = REGISTRY.block(8589);
    public static final BlockDefinition SOUL_SAND = REGISTRY.block(8668);
    public static final BlockDefinition SOUL_SOIL = REGISTRY.block(8669);
    public static final BlockDefinition STICKY_PISTON = REGISTRY.block(2356);
    public static final BlockDefinition STONE = REGISTRY.block(1);
    public static final BlockDefinition STRAW_BED = REGISTRY.block(2289);
    public static final BlockDefinition SWEET_BERRY_BUSH = REGISTRY.block(24298);
    public static final BlockDefinition TRAPPED_CHEST = REGISTRY.block(12975);
    public static final BlockDefinition TURTLE_EGG = REGISTRY.block(18447);
    public static final BlockDefinition TWISTING_VINES = REGISTRY.block(24361);
    public static final BlockDefinition TWISTING_VINES_PLANT = REGISTRY.block(24387);
    public static final BlockDefinition VINE = REGISTRY.block(10123);
    public static final BlockDefinition WATER = REGISTRY.block(89);
    public static final BlockDefinition WEEPING_VINES = REGISTRY.block(24334);
    public static final BlockDefinition WEEPING_VINES_PLANT = REGISTRY.block(24360);

    private BlockIds() {}
    public static boolean is(int state, BlockDefinition block) { return REGISTRY.block(state) == block; }
}
