package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidsTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String block) { return DATA.registry().block("minecraft:" + block).defaultState(); }
    private static int with(int state, String property, String value) { return DATA.registry().with(state, property, value); }
    private static SimFluidState fluid(String block) { return SimFluidState.of(DATA.registry().facts(state(block))); }

    @Test void ordinaryWaterloggingSucceedsWithoutWritingOnTheClient() {
        for (String block : new String[]{"oak_slab", "oak_stairs", "oak_fence", "dried_ghast"}) {
            var world = new World(); int dry = with(state(block), "waterlogged", "false");
            world.states.put(POS, dry); var level = level(world); var behavior = level.behavior(dry);
            assertTrue(behavior.canPlaceLiquid(level, dry, POS, "minecraft:water", false), block);
            assertTrue(behavior.placeLiquid(level, dry, POS, fluid("water")), block);
            assertEquals(dry, level.stateAt(POS), block);
            assertTrue(level.writes().isEmpty(), block);
            assertFalse(behavior.placeLiquid(level, dry, POS, fluid("lava")), block);
            assertFalse(behavior.placeLiquid(level, with(dry, "waterlogged", "true"), POS, fluid("water")), block);
        }
    }

    @Test void fillingLitCandlesAndCampfiresPredictsExtinguishingAndWaterloggingTogether() {
        for (String block : new String[]{"candle", "campfire", "soul_campfire"}) {
            for (boolean lit : new boolean[]{false, true}) {
                var world = new World(); int initial = with(with(state(block), "waterlogged", "false"), "lit", Boolean.toString(lit));
                world.states.put(POS, initial); var level = level(world);
                assertTrue(level.behavior(initial).placeLiquid(level, initial, POS, fluid("water")), block);
                assertEquals("true", DATA.registry().value(level.stateAt(POS), "waterlogged"), block);
                assertEquals("false", DATA.registry().value(level.stateAt(POS), "lit"), block);
                assertEquals(1, level.writes().size(), block);
                assertEquals(initial, level.writes().getFirst().oldState(), block);
            }
        }
    }

    @Test void collectingWaterWritesTheDryStateAndReturnsOneWaterBucket() {
        var world = new World(); int wet = with(state("oak_slab"), "waterlogged", "true");
        int dry = with(wet, "waterlogged", "false"); world.states.put(POS, wet);
        var level = level(world);
        assertEquals("minecraft:water_bucket", level.behavior(wet).pickupBlock(level, wet, POS, false));
        assertEquals(dry, level.stateAt(POS));
        assertEquals(1, level.writes().size());
        assertNull(level.behavior(dry).pickupBlock(level, dry, POS, false));
        assertEquals(1, level.writes().size());
    }

    @Test void liquidPickupRequiresASourceBlockButSnowAndBubbleColumnsDoNot() {
        for (String block : new String[]{"water", "lava"}) {
            for (int amount = 0; amount <= 15; amount++) {
                var world = new World(); int initial = with(state(block), "level", Integer.toString(amount));
                world.states.put(POS, initial); var level = level(world);
                String bucket = level.behavior(initial).pickupBlock(level, initial, POS, false);
                assertEquals(amount == 0 ? "minecraft:" + block + "_bucket" : null, bucket);
                assertEquals(amount == 0 ? state("air") : initial, level.stateAt(POS));
            }
        }
        for (String block : new String[]{"powder_snow", "bubble_column"}) {
            var world = new World(); int initial = state(block); world.states.put(POS, initial); var level = level(world);
            assertEquals(block.equals("powder_snow") ? "minecraft:powder_snow_bucket" : "minecraft:water_bucket",
                level.behavior(initial).pickupBlock(level, initial, POS, false));
            assertEquals(state("air"), level.stateAt(POS));
        }
    }

    @Test void doubleSlabsAndWaterPlantsRejectLiquidAndBarrierPickupRequiresCreative() {
        var world = new World(); var level = level(world);
        int doubleSlab = with(state("oak_slab"), "type", "double");
        for (int block : new int[]{doubleSlab, state("seagrass"), state("tall_seagrass"), state("kelp"), state("kelp_plant")}) {
            assertFalse(level.behavior(block).canPlaceLiquid(level, block, POS, "minecraft:water", true));
            assertFalse(level.behavior(block).placeLiquid(level, block, POS, fluid("water")));
        }
        int barrier = with(state("barrier"), "waterlogged", "true"); world.states.put(POS, barrier);
        assertFalse(level.behavior(barrier).canPlaceLiquid(level, barrier, POS, "minecraft:water", false));
        assertNull(level.behavior(barrier).pickupBlock(level, barrier, POS, false));
        assertEquals(barrier, level.stateAt(POS));
        assertEquals("minecraft:water_bucket", level.behavior(barrier).pickupBlock(level, barrier, POS, true));
    }

    @Test void destroyingAWaterloggedBlockRestoresItsFluidAndAirDoesNothing() {
        var world = new World(); int wet = with(state("oak_slab"), "waterlogged", "true"); world.states.put(POS, wet);
        var level = level(world);
        assertTrue(level.destroyBlock(POS, 512));
        assertEquals(state("water"), level.stateAt(POS));
        assertFalse(level.destroyBlock(POS.relative(Direction.EAST), 512));
        assertEquals(1, level.writes().size());
    }

    @Test void collectingWaterRemovesUnsupportedPlantsAfterTheDryWrite() {
        for (String block : new String[]{"big_dripleaf", "big_dripleaf_stem", "mangrove_propagule", "small_dripleaf", "glow_lichen", "sculk_vein"}) {
            var world = new World(); int wet = with(state(block), "waterlogged", "true");
            world.states.put(POS, wet); var level = level(world);
            assertEquals("minecraft:water_bucket", level.behavior(wet).pickupBlock(level, wet, POS, false), block);
            assertEquals(state("air"), level.stateAt(POS), block);
            assertEquals(2, level.writes().size(), block);
            assertEquals(with(wet, "waterlogged", "false"), level.writes().getFirst().newState(), block);
            assertEquals(wet, level.retainedStates().get(POS), block);
        }
    }

    @Test void aSubmergedSmallDripleafOnGrassLosesItsWaterSupportWhenPickedUp() {
        var world = new World(); int wet = with(state("small_dripleaf"), "waterlogged", "true");
        world.states.put(POS, wet); world.states.put(POS.relative(Direction.DOWN), state("grass_block"));
        var level = level(world);
        assertTrue(level.behavior(wet).canSurvive(level, wet, POS));
        assertEquals("minecraft:water_bucket", level.behavior(wet).pickupBlock(level, wet, POS, false));
        assertEquals(state("air"), level.stateAt(POS));
        assertEquals(2, level.writes().size());
    }

    @Test void multifacePlantsRequireAtLeastOneSupportedFaceAndCheckEveryActiveFace() {
        var world = new World(); int initial = with(state("glow_lichen"), "waterlogged", "true");
        world.states.put(POS, initial); world.states.put(POS.relative(Direction.WEST), state("stone"));
        var level = level(world);
        assertFalse(level.behavior(initial).canSurvive(level, initial, POS));
        int west = with(initial, "west", "true");
        assertTrue(level.behavior(west).canSurvive(level, west, POS));
        assertFalse(level.behavior(west).canSurvive(level, with(west, "east", "true"), POS));
    }

    private static SimLevel level(World world) { return new SimLevel(world, DATA.registry(), new BehaviorRegistry(DATA)); }
    private static final class World implements SimWorldView {
        final Map<BlockPos, Integer> states = new HashMap<>();
        public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public boolean isSectionEmpty(BlockPos pos) { return false; }
        public int minY() { return -64; }
        public int height() { return 384; }
        public boolean isWithinBorder(ac.cult.blocksim.engine.BlockPos pos) { return true; }
        public boolean creakingActiveAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public boolean waterEvaporatesAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public ac.cult.blocksim.engine.BlockEntityData blockEntityAt(ac.cult.blocksim.engine.BlockPos pos) { return null; }
    }
}
