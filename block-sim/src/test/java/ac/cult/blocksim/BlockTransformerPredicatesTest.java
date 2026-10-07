package ac.cult.blocksim;

import ac.cult.blocksim.behavior.ConsumableBehavior;
import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.interaction.*;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockTransformerPredicatesTest {
    private static final DataTables DATA = DataTables.defaults();
    private static final ItemRegistry ITEMS = new ItemRegistry(DATA);
    private static final BlockSimulator SIM = BlockSimulator.create(DATA, ITEMS, InteractionRegistries.defaults(),
            (context, state, shape) -> true, new ConsumableBehavior(context -> { throw new AssertionError(); }));
    private static final MiningSpeed.State MINING = new MiningSpeed.State(true, false, 0, 1, .2, -1, -1, -1);

    @Test void boundsApplyTheOffsetAndOnlyRestrictBuildHeight() {
        var world = new World();
        world.minY = 64;
        world.height = 16;
        var bottom = new BlockPos(29_999_999, 64, 0);
        assertTransform(transform(world, bottom, "{\"type\":\"inside_world_bounds\",\"offset\":[16,0,0]}"), true);
        assertTransform(transform(world, bottom, "{\"type\":\"inside_world_bounds\",\"offset\":[0,-1,0]}"), false);
        var top = new BlockPos(0, 79, 0);
        assertTransform(transform(world, top, "{\"type\":\"inside_world_bounds\"}"), true);
        assertTransform(transform(world, top, "{\"type\":\"inside_world_bounds\",\"offset\":[0,1,0]}"), false);
    }

    @Test void heightRangesUseInclusiveAnchorsAndTheReceivedSeaLevel() {
        var world = new World();
        for (String anchor : new String[]{"{\"absolute\":63}", "{\"above_bottom\":127}",
                "{\"below_top\":256}", "{\"relative_to_sea_level\":8}"}) {
            String predicate = "{\"type\":\"height_range\",\"min_inclusive\":" + anchor + ",\"max_inclusive\":" + anchor + "}";
            for (int y : new int[]{62, 63, 64}) assertTransform(transform(world, new BlockPos(0, y, 0), predicate), y == 63);
        }
        world.seaLevel = 54;
        assertTransform(transform(world, new BlockPos(0, 63, 0),
                "{\"type\":\"height_range\",\"min_inclusive\":{\"relative_to_sea_level\":8},\"max_inclusive\":{\"relative_to_sea_level\":8}}"), false);
    }

    @Test void obstructionQueriesTheClickedBlockEvenWhenThePredicateHasAnOffset() {
        var world = new World();
        var pos = new BlockPos(2, 64, -3);
        String predicate = "{\"type\":\"unobstructed\",\"offset\":[1,2,3]}";
        assertTransform(transform(world, pos, predicate), true);
        assertEquals(new Box(2, 64, -3, 3, 65, -2), world.obstruction);
        world.obstructed = true;
        assertTransform(transform(world, pos, predicate), false);
    }

    @Test void volumesIncludeBothBoundsAndShortCircuitBeforeAnUnloadedBlock() {
        var world = new World();
        var pos = new BlockPos(0, 64, 0);
        String predicate = """
                {"type":"volume_match","min":[1,0,0],"max":[2,1,1],
                 "match":{"type":"matching_blocks","blocks":"stone","offset":[0,1,0]}}
                """;
        for (int x = 1; x <= 2; x++) for (int y = 65; y <= 66; y++) for (int z = 0; z <= 1; z++)
            world.states.put(new BlockPos(x, y, z), state("stone"));
        assertTransform(transform(world, pos, predicate), true);
        world.states.remove(new BlockPos(2, 66, 1));
        assertTransform(transform(world, pos, predicate), false);
        world.states.remove(new BlockPos(1, 65, 0));
        world.unloaded.add(new BlockPos(2, 66, 1));
        assertTransform(transform(world, pos, predicate), false);
    }

    @Test void biomePredicatesUseReceivedKeysAndReboundTagMemberships() {
        var world = new World();
        var pos = new BlockPos(0, 64, 0);
        assertTransform(transform(world, pos, "{\"type\":\"matching_biomes\",\"biomes\":\"plains\"}"), true);
        assertTransform(transform(world, pos, "{\"type\":\"matching_biomes\",\"biomes\":\"#minecraft:is_overworld\"}"), true);
        world.biome = "test:custom";
        assertTransform(transform(world, pos, "{\"type\":\"matching_biomes\",\"biomes\":[\"plains\",\"test:custom\"]}"), true);
        assertTransform(transform(world, pos, "{\"type\":\"matching_biomes\",\"biomes\":\"#minecraft:is_overworld\"}"), false);
        var tags = ac.cult.blocksim.data.HolderSets.Overlay.differingFrom(DATA,
                Map.of("worldgen/biome:minecraft:is_overworld", Set.of("test:custom")));
        assertTransform(transform(world, pos, "{\"type\":\"matching_biomes\",\"biomes\":\"#minecraft:is_overworld\"}", tags), true);
        assertTransform(transform(world, pos, "{\"type\":\"matching_biomes\",\"biomes\":\"#minecraft:is_overworld\"}"), false);
    }

    private static SimResult transform(World world, BlockPos pos, String predicate) {
        return transform(world, pos, predicate, ac.cult.blocksim.data.HolderSets.Overlay.EMPTY);
    }

    private static SimResult transform(World world, BlockPos pos, String predicate, ac.cult.blocksim.data.HolderSets.Overlay tags) {
        world.states.put(pos, state("dirt"));
        var stack = ITEMS.stack("minecraft:stick", 3);
        stack.components(stack.components().with("minecraft:block_transformer", JsonParser.parseString("""
                [{"update_from_neighbors":false,"block_state_provider":{"type":"rule_based",
                  "rules":[{"if_true":%s,"then":{"id":"stone"}}]}}]
                """.formatted(predicate))));
        var owner = new SimPlayer(new SimPlayer.State(new Vec3(pos.x() + .5, pos.y() + 1, pos.z() + .5),
                0, 0, false, false, true, true, SimPlayer.GameMode.SURVIVAL), stack, ITEMS.empty());
        return SIM.simulate(new SimAction.UseOn(Hand.MAIN_HAND,
                new BlockHit(pos, Direction.UP, owner.state().position(), false)),
                new SimInput(world, owner, new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty()), tags));
    }

    private static void assertTransform(SimResult result, boolean expected) {
        assertNull(result.decline());
        assertEquals(expected ? InteractionKind.SUCCESS : InteractionKind.PASS, result.interaction().kind());
        assertEquals(expected ? 2 : 3, result.player().hand(Hand.MAIN_HAND).count());
        if (expected) {
            assertEquals(1, result.writes().size());
            assertEquals(state("stone"), result.writes().getFirst().newState());
        } else assertTrue(result.writes().isEmpty());
    }

    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }

    private static final class World implements SimWorldView {
        private final Map<BlockPos, Integer> states = new HashMap<>();
        private final Set<BlockPos> unloaded = new HashSet<>();
        private int minY = -64, height = 384, seaLevel = 55;
        private boolean obstructed;
        private String biome = "minecraft:plains";
        private Box obstruction;
        public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
        public boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos); }
        public boolean isSectionEmpty(BlockPos pos) { return false; }
        public int minY() { return minY; }
        public int height() { return height; }
        public int seaLevel() { return seaLevel; }
        public String biomeKeyAt(BlockPos pos) { return biome; }
        public boolean isWithinBorder(BlockPos pos) { return true; }
        public boolean creakingActiveAt(BlockPos pos) { return false; }
        public boolean waterEvaporatesAt(BlockPos pos) { return false; }
        public BlockEntityData blockEntityAt(BlockPos pos) { return null; }
        public boolean isUnobstructed(VoxelShape shape) { obstruction = shape.boxes().getFirst(); return !obstructed; }
    }
}
