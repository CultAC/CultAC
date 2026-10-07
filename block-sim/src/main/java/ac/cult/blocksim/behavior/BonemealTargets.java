package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Client isValidBonemealTarget branches from the pinned 26.3 block sources.
 * Dispatch is bound to generated ancestry; growth randomness and server callbacks never run. */
final class BonemealTargets {
    @FunctionalInterface private interface Target { boolean valid(SimLevel level, int state, BlockPos pos); }
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Target NEVER = (level, state, pos) -> false;
    private final Map<BlockDefinition, Target> byBlock = new IdentityHashMap<>();
    private final Set<String> nylium, coral;

    BonemealTargets(DataTables data) {
        nylium = data.tags().get("block:minecraft:nylium");
        coral = data.tags().get("block:minecraft:coral_blocks");
        var families = new HashMap<String, Target>();
        for (String family : new String[]{"AzaleaBlock", "MushroomBlock", "SaplingBlock"}) families.put(family, NEVER);
        for (String family : new String[]{"FlowerBedBlock", "ShortDryGrassBlock", "SmallDripleafBlock", "TallFlowerBlock"})
            families.put(family, (level, state, pos) -> true);
        families.put("BonemealableFeaturePlacerBlock", (level, state, pos) -> air(level, pos.relative(Direction.UP)));
        for (String family : new String[]{"BambooSaplingBlock", "GrassBlock", "NyliumBlock"})
            families.put(family, (level, state, pos) -> airInBounds(level, pos.relative(Direction.UP)));
        families.put("RootedDirtBlock", (level, state, pos) -> airInBounds(level, pos.relative(Direction.DOWN)));
        families.put("MangroveLeavesBlock", (level, state, pos) -> air(level, pos.relative(Direction.DOWN)));
        for (String family : new String[]{"CaveVinesBlock", "CaveVinesPlantBlock"})
            families.put(family, (level, state, pos) -> !BlockBehavior.bool(level, state, "berries"));
        for (String family : new String[]{"CocoaBlock", "CropBlock", "StemBlock", "SweetBerryBushBlock", "ShelfMushroomBlock"})
            families.put(family, BonemealTargets::belowMaximumAge);
        // TorchflowerCropBlock.getMaxAge is 2, while AGE_1 only stores 0 and 1;
        // the next server growth replaces the crop with the flower block.
        families.put("TorchflowerCropBlock", (level, state, pos) -> BlockBehavior.number(level, state, "age") < 2);
        families.put("MangrovePropaguleBlock", (level, state, pos) -> !BlockBehavior.bool(level, state, "hanging") || belowMaximumAge(level, state, pos));
        for (String family : new String[]{"BushBlock", "FireflyBushBlock"}) families.put(family, BonemealTargets::spreadableNeighbor);
        families.put("TallDryGrassBlock", (level, state, pos) -> spreadableNeighbor(level, level.registry().block("minecraft:short_dry_grass").defaultState(), pos));
        families.put("BambooStalkBlock", BonemealTargets::bamboo);
        families.put("BigDripleafBlock", (level, state, pos) -> dripleafGrowth(level, pos.relative(Direction.UP)));
        families.put("BigDripleafStemBlock", BonemealTargets::dripleafStem);
        for (String family : new String[]{"GrowingPlantHeadBlock", "GrowingPlantBodyBlock"})
            families.put(family, (level, state, pos) -> ((GrowingPlantBehavior) level.behavior(state)).isValidBonemealTarget(level, state, pos));
        families.put("HangingMossBlock", BonemealTargets::hangingMoss);
        families.put("MossyCarpetBlock", (level, state, pos) -> ((MossyCarpetBehavior) level.behavior(state)).canCreateTopper(level, state, pos));
        families.put("GlowLichenBlock", (level, state, pos) -> ((MultifaceBehavior) level.behavior(state)).canSpread(level, state, pos));
        families.put("NetherFungusBlock", (level, state, pos) -> inBounds(level, pos.relative(Direction.UP))
                && level.registry().block(level.stateAt(pos.relative(Direction.DOWN))).key()
                        .equals(level.registry().block(state).bindings().get("NetherFungusBlock.requiredBlock")));
        families.put("NetherrackBlock", this::netherrack);
        families.put("PitcherCropBlock", BonemealTargets::pitcher);
        families.put("SeaPickleBlock", (level, state, pos) -> BlockBehavior.bool(level, state, "waterlogged")
                && coral.contains(level.registry().block(level.stateAt(pos.relative(Direction.DOWN))).key()));
        families.put("SeagrassBlock", (level, state, pos) -> level.registry().block(level.stateAt(pos.relative(Direction.UP))).key().equals("minecraft:water"));
        families.put("TallGrassBlock", BonemealTargets::tallGrass);
        for (var block : data.registry().blocks()) {
            Target target = NEVER;
            for (String type : block.bindings().get("classHierarchy").split(",")) {
                var candidate = families.get(type.substring(type.lastIndexOf('.') + 1));
                if (candidate != null) { target = candidate; break; }
            }
            byBlock.put(block, target);
        }
    }

    boolean valid(SimLevel level, int state, BlockPos pos) { return byBlock.get(level.registry().block(state)).valid(level, state, pos); }
    private static boolean inBounds(SimLevel level, BlockPos pos) { return pos.y() >= level.minY() && pos.y() <= level.maxY(); }
    private static boolean air(SimLevel level, BlockPos pos) { return level.registry().facts(level.stateAt(pos)).has(StateFacts.AIR); }
    private static boolean airInBounds(SimLevel level, BlockPos pos) { return inBounds(level, pos) && air(level, pos); }
    private static boolean belowMaximumAge(SimLevel level, int state, BlockPos pos) {
        var values = level.registry().block(state).properties().stream().filter(property -> property.name().equals("age")).findFirst().orElseThrow().values();
        return BlockBehavior.number(level, state, "age") < Integer.parseInt(values.getLast());
    }
    private static boolean spreadableNeighbor(SimLevel level, int state, BlockPos pos) {
        for (Direction direction : HORIZONTAL) {
            var neighbor = pos.relative(direction);
            if (air(level, neighbor) && level.behavior(state).canSurvive(level, state, neighbor)) return true;
        }
        return false;
    }
    private static boolean bamboo(SimLevel level, int state, BlockPos pos) {
        int above = 0, below = 0;
        while (above < 16 && level.registry().sameBlock(state, level.stateAt(pos.relative(Direction.UP, above + 1)))) above++;
        while (below < 16 && level.registry().sameBlock(state, level.stateAt(pos.relative(Direction.DOWN, below + 1)))) below++;
        return above + below + 1 < 16 && BlockBehavior.number(level, level.stateAt(pos.relative(Direction.UP, above)), "stage") != 1
                && airInBounds(level, pos.relative(Direction.UP, above + 1));
    }
    private static boolean dripleafGrowth(SimLevel level, BlockPos pos) {
        if (!inBounds(level, pos)) return false;
        int state = level.stateAt(pos);
        String key = level.registry().block(state).key();
        return air(level, pos) || key.equals("minecraft:water") || key.equals("minecraft:small_dripleaf");
    }
    private static boolean dripleafStem(SimLevel level, int state, BlockPos pos) {
        var head = pos.relative(Direction.UP);
        while (level.registry().sameBlock(state, level.stateAt(head))) head = head.relative(Direction.UP);
        return level.registry().block(level.stateAt(head)).key().equals("minecraft:big_dripleaf") && dripleafGrowth(level, head.relative(Direction.UP));
    }
    private static boolean hangingMoss(SimLevel level, int state, BlockPos pos) {
        var growth = pos.relative(Direction.DOWN);
        while (level.registry().sameBlock(state, level.stateAt(growth))) growth = growth.relative(Direction.DOWN);
        return airInBounds(level, growth);
    }
    private boolean netherrack(SimLevel level, int state, BlockPos pos) {
        if (!level.registry().facts(level.stateAt(pos.relative(Direction.UP))).has(StateFacts.PROPAGATES_SKYLIGHT)) return false;
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
            if (nylium.contains(level.registry().block(level.stateAt(new BlockPos(pos.x() + x, pos.y() + y, pos.z() + z))).key())) return true;
        return false;
    }
    private static boolean pitcher(SimLevel level, int state, BlockPos pos) {
        if (!level.registry().value(state, "half").equals("lower")) {
            pos = pos.relative(Direction.DOWN); state = level.stateAt(pos);
            if (!level.registry().block(state).key().equals("minecraft:pitcher_crop") || !level.registry().value(state, "half").equals("lower")) return false;
        }
        if (!belowMaximumAge(level, state, pos) || level.rawBrightnessAt(pos) < 8 || !inBounds(level, pos.relative(Direction.UP))) return false;
        return BlockBehavior.number(level, state, "age") + 1 < 3 || air(level, pos.relative(Direction.UP))
                || level.registry().block(level.stateAt(pos.relative(Direction.UP))).key().equals("minecraft:pitcher_crop");
    }
    private static boolean tallGrass(SimLevel level, int state, BlockPos pos) {
        int grown = level.registry().block(level.registry().block(state).key().equals("minecraft:fern") ? "minecraft:large_fern" : "minecraft:tall_grass").defaultState();
        return level.behavior(grown).canSurvive(level, grown, pos) && airInBounds(level, pos.relative(Direction.UP));
    }
}
