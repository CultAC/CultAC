package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Expected support and write rules come from the cited 26.3 behavior sources. */
class SupportPlacementTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private static int with(int state, String key, String value) { return DATA.registry().with(state, key, value); }

    @Test void seaPicklesAcceptAPartialCollisionFaceButRailsRequireRigidSupport() {
        var level = level(Map.of(POS.relative(Direction.DOWN), state("oak_fence")));
        assertTrue(level.behavior(state("sea_pickle")).canSurvive(level, state("sea_pickle"), POS));
        assertFalse(level.behavior(state("rail")).canSurvive(level, state("rail"), POS));
        level = level(Map.of(POS.relative(Direction.DOWN), state("stone")));
        assertTrue(level.behavior(state("rail")).canSurvive(level, state("rail"), POS));
    }

    @Test void mushroomsUseSolidRenderRatherThanLegacySolidity() {
        var level = level(Map.of(POS.relative(Direction.DOWN), state("glass")));
        assertTrue(DATA.registry().facts(state("glass")).has(ac.cult.blocksim.data.StateFacts.SOLID));
        assertFalse(level.behavior(state("brown_mushroom")).canSurvive(level, state("brown_mushroom"), POS));
        level = level(Map.of(POS.relative(Direction.DOWN), state("stone")));
        assertTrue(level.behavior(state("brown_mushroom")).canSurvive(level, state("brown_mushroom"), POS));
    }

    @Test void railSupportLossSchedulesNoClientRemoval() {
        var below = POS.relative(Direction.DOWN);
        var level = level(Map.of(POS, state("rail"), below, state("stone")));
        assertTrue(level.setBlock(below, state("air"), 3));
        assertEquals(state("rail"), level.stateAt(POS));
        assertEquals(1, level.writes().size());
    }

    @Test void sporeBlossomNeedsCeilingCenterSupportAndRejectsFlowingWater() {
        int blossom = state("spore_blossom");
        var level = level(Map.of(POS.relative(Direction.UP), state("stone")));
        assertTrue(level.behavior(blossom).canSurvive(level, blossom, POS));
        level = level(Map.of(POS.relative(Direction.UP), state("stone"), POS, with(state("water"), "level", "1")));
        assertFalse(level.behavior(blossom).canSurvive(level, blossom, POS));
        level = level(Map.of());
        assertFalse(level.behavior(blossom).canSurvive(level, blossom, POS));
    }

    @Test void smallDripleafUpperHalfIsServerOnlyWhileSunflowerWritesItOnClient() {
        var items = new ItemRegistry(DATA); var stack = items.stack("minecraft:small_dripleaf", 1);
        var level = level(Map.of(POS, state("small_dripleaf")));
        level.behavior(state("small_dripleaf")).setPlacedBy(level, state("small_dripleaf"), POS, null, stack);
        assertTrue(level.writes().isEmpty());
        level = level(Map.of(POS, state("sunflower")));
        level.behavior(state("sunflower")).setPlacedBy(level, state("sunflower"), POS, null, items.stack("minecraft:sunflower", 1));
        assertEquals(with(state("sunflower"), "half", "upper"), level.stateAt(POS.relative(Direction.UP)));
        assertEquals(1, level.writes().size());
    }

    @Test void poweredTrapdoorStartsOpenAndManualToggleLeavesPoweredTrue() {
        var level = level(Map.of(POS.relative(Direction.DOWN), state("stone"), POS.relative(Direction.EAST), state("redstone_block")));
        var items = new ItemRegistry(DATA); var stack = items.stack("minecraft:oak_trapdoor", 1);
        var player = new SimPlayer(new SimPlayer.State(new Vec3(0.5, 64, 0.5), 90, 0, false, false, true, false, SimPlayer.GameMode.SURVIVAL), stack, items.empty());
        var hit = new BlockHit(POS.relative(Direction.DOWN), Direction.UP, new Vec3(0.5, 64, 0.5), false);
        var context = new PlacementContext(level, player, Hand.MAIN_HAND, stack, hit);
        int placed = level.behavior(state("oak_trapdoor")).placementState(DATA.registry().block("minecraft:oak_trapdoor"), context);
        assertEquals("true", DATA.registry().value(placed, "open"));
        assertEquals("true", DATA.registry().value(placed, "powered"));
        assertEquals("east", DATA.registry().value(placed, "facing"));
        assertTrue(level.setBlock(POS, placed, 11));
        var use = new UseContext(level, player, Hand.MAIN_HAND, stack, new BlockHit(POS, Direction.UP, new Vec3(0.5, 65, 0.5), false));
        assertEquals(SimInteraction.SUCCESS, level.behavior(placed).useWithoutItem(placed, use));
        assertEquals("false", DATA.registry().value(level.stateAt(POS), "open"));
        assertEquals("true", DATA.registry().value(level.stateAt(POS), "powered"));
        assertEquals(SimInteraction.PASS, level.behavior(state("iron_trapdoor")).useWithoutItem(state("iron_trapdoor"), use));
    }

    private static SimLevel level(Map<BlockPos, Integer> states) {
        SimWorldView view = new SimWorldView() {
            public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
            public boolean isLoaded(BlockPos pos) { return true; }
            public boolean isSectionEmpty(BlockPos pos) { return false; }
            public int rawBrightnessAt(BlockPos pos) { return 0; }
            public int minY() { return -64; }
            public int height() { return 384; }
            public boolean isWithinBorder(ac.cult.blocksim.engine.BlockPos pos) { return true; }
            public boolean creakingActiveAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
            public boolean waterEvaporatesAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public ac.cult.blocksim.engine.BlockEntityData blockEntityAt(ac.cult.blocksim.engine.BlockPos pos) { return null; }
        };
        return new SimLevel(view, DATA.registry(), new BehaviorRegistry(DATA));
    }
}
