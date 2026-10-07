package ac.cult.blocksim.data;

import java.util.HashSet;
import java.util.List;

/** Block class and interface membership compiled once from the generated bindings. */
public enum BlockFamilies {
    BED("BedBlock"), BIG_DRIPLEAF_STEM("BigDripleafStemBlock"), BUBBLE_COLUMN("BubbleColumnBlock"),
    CHORUS_PLANT("ChorusPlantBlock"), DOOR("DoorBlock"), ENTITY("EntityBlock"), FENCE("FenceBlock"),
    FENCE_GATE("FenceGateBlock"), GAME_MASTER("GameMasterBlock"), GROWING_PLANT_HEAD("GrowingPlantHeadBlock"),
    HONEY("HoneyBlock"), ICE("IceBlock"), IRON_BARS("IronBarsBlock"), LEAVES("LeavesBlock"),
    LIQUID("LiquidBlock"), PISTON_BASE("piston.PistonBaseBlock"), PISTON_HEAD("piston.PistonHeadBlock"),
    POWDER_SNOW("PowderSnowBlock"), SCAFFOLDING("ScaffoldingBlock"), SHULKER_BOX("ShulkerBoxBlock"),
    SLAB("SlabBlock"), SNOW_LAYER("SnowLayerBlock"), STAINED_GLASS("StainedGlassBlock"), STAIR("StairBlock"),
    SWEET_BERRY_BUSH("SweetBerryBushBlock"), TRAP_DOOR("TrapDoorBlock"), WALL("WallBlock"), WEB("WebBlock");

    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final int[] MEMBERSHIP = membership();
    private final String className;
    BlockFamilies(String className) { this.className = "net.minecraft.world.level.block." + className; }

    public boolean test(int state) { return (MEMBERSHIP[REGISTRY.blockIndex(state)] & (1 << ordinal())) != 0; }

    private static int[] membership() {
        int[] result = new int[REGISTRY.blocks().size()];
        for (int index = 0; index < result.length; index++) {
            var bindings = REGISTRY.blocks().get(index).bindings();
            var classes = new HashSet<>(List.of(bindings.get("classHierarchy").split(",")));
            classes.addAll(List.of(bindings.get("classInterfaces").split(",")));
            for (var family : values()) if (classes.contains(family.className)) result[index] |= 1 << family.ordinal();
        }
        return result;
    }
}
