package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GrowingPlantBehaviorTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private static int with(int state, String property, String value) { return DATA.registry().with(state, property, value); }

    @Test void removingTheGrowthNeighborWritesRootBeforeRandomHeadAndRetainsInCompletionOrder() {
        int body = state("twisting_vines_plant"), head = with(state("twisting_vines"), "age", "25");
        var top = POS.relative(Direction.UP);
        var level = level(Map.of(POS, body, top, head), true);
        assertTrue(level.setBlock(top, state("air"), 3));
        assertEquals(java.util.List.of(top, POS), level.writes().stream().map(w -> w.pos()).toList());
        assertEquals(with(state("twisting_vines"), "age", "17"), level.stateAt(POS));
        assertEquals(java.util.List.of(POS, top), new java.util.ArrayList<>(level.retainedStates().keySet()));
        assertEquals(body, level.retainedStates().get(POS));
    }

    @Test void declaredAgeIsBoundAcrossWritesAndDoesNotPermitChangesToBerriesOrMaxAge() {
        int body = with(state("cave_vines_plant"), "berries", "true");
        var level = level(Map.of(POS, body), false);
        int converted = level.behavior(body).updateShape(level, body, POS, Direction.DOWN, POS.relative(Direction.DOWN), state("air"));
        assertTrue(level.setBlock(POS, converted, 19));
        var sample = level.statePossibilitiesAt(POS);
        assertFalse(sample.exact());
        assertEquals("true", DATA.registry().value(sample.state(), "berries"));
        var bindings = new HashMap<java.util.UUID, Integer>();
        assertTrue(sample.matches(DATA.registry(), with(converted, "age", "24"), bindings));
        assertFalse(sample.matches(DATA.registry(), with(converted, "age", "23"), bindings));
        assertFalse(sample.matches(DATA.registry(), with(converted, "age", "25"), new HashMap<>()));
        assertFalse(sample.matches(DATA.registry(), with(with(converted, "age", "24"), "berries", "false"), new HashMap<>()));
        assertTrue(level.setBlock(POS, with(converted, "berries", "false"), 19));
        assertEquals(sample.randomPlantAge(), level.writes().get(1).oldPossibilities().randomPlantAge());
        assertEquals(sample.randomPlantAge(), level.writes().get(1).newPossibilities().randomPlantAge());
        assertTrue(level.setBlock(POS, with(level.stateAt(POS), "age", "25"), 19));
        assertTrue(level.statePossibilitiesAt(POS).exact());
    }

    @Test void samplesFromSeparateActionsCannotShareAnAgeVariable() {
        int head = state("twisting_vines");
        var first = level(Map.of(), false); var second = level(Map.of(), false);
        first.setBlock(POS, first.initialPlantAge(POS, head), 19);
        second.setBlock(POS, second.initialPlantAge(POS, head), 19);
        assertNotEquals(first.statePossibilitiesAt(POS).randomPlantAge(), second.statePossibilitiesAt(POS).randomPlantAge());
    }

    @Test void replacingTheSupportOnlySchedulesADestroyTickOnTheClient() {
        int head = with(state("twisting_vines"), "age", "25");
        var support = POS.relative(Direction.DOWN); var level = level(Map.of(POS, head, support, state("stone")), true);
        assertTrue(level.setBlock(support, state("air"), 3));
        assertEquals(head, level.stateAt(POS));
        assertEquals(1, level.writes().size());
    }

    @Test void convertingCaveVineHeadToBodyPreservesBerriesButRemovesTheAgeVariable() {
        int head = with(with(state("cave_vines"), "age", "22"), "berries", "true");
        int neighbor = state("cave_vines_plant"); var level = level(Map.of(POS, head), true);
        int converted = level.behavior(head).updateShape(level, head, POS, Direction.DOWN, POS.relative(Direction.DOWN), neighbor);
        assertEquals(with(state("cave_vines_plant"), "berries", "true"), converted);
    }

    @Test void explicitAgeZeroRemovesRandomnessAndDeclaresOnlyTheSourceConditionalWrite() {
        int head = state("twisting_vines"); var level = level(Map.of(POS.relative(Direction.DOWN), state("stone")), false);
        assertTrue(level.setBlock(POS, level.initialPlantAge(POS, head), 11));
        var initial = level.writes().getFirst().newPossibilities();
        level.setBlockFromItemProperties(POS, with(head, "age", "0"), true);
        assertEquals(2, level.writes().size());
        var override = level.writes().get(1);
        assertTrue(level.statePossibilitiesAt(POS).exact());
        assertEquals(0, override.condition().excludedAge());
        assertFalse(override.condition().isPresent(Map.of(initial.randomPlantAge(), 0)));
        assertTrue(override.condition().isPresent(Map.of(initial.randomPlantAge(), 24)));
        assertEquals(state("air"), level.retainedStates().get(POS));
    }

    @Test void anAgeOutsideTheInitialValueSetOrAChangedBerryPropertyAlwaysWrites() {
        int head = state("cave_vines");
        for (int age : new int[]{0, 25}) {
            var level = level(Map.of(POS.relative(Direction.UP), state("stone")), false);
            assertTrue(level.setBlock(POS, level.initialPlantAge(POS, head), 11));
            int modified = with(head, "age", Integer.toString(age));
            if (age == 0) modified = with(modified, "berries", "true");
            level.setBlockFromItemProperties(POS, modified, true);
            assertEquals(2, level.writes().size());
            assertNull(level.writes().get(1).condition());
            assertTrue(level.statePossibilitiesAt(POS).exact());
            assertEquals(modified, level.stateAt(POS));
        }
    }

    private static SimLevel level(Map<BlockPos, Integer> states, boolean knownAge) {
        SimWorldView view = new SimWorldView() {
            public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
            public boolean isLoaded(BlockPos pos) { return true; }
            public boolean isSectionEmpty(BlockPos pos) { return states.isEmpty(); }
            public int minY() { return -64; }
            public int height() { return 384; }
            public boolean isWithinBorder(ac.cult.blocksim.engine.BlockPos pos) { return true; }
            public boolean creakingActiveAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
            public boolean waterEvaporatesAt(ac.cult.blocksim.engine.BlockPos pos) { return false; }
        public ac.cult.blocksim.engine.BlockEntityData blockEntityAt(ac.cult.blocksim.engine.BlockPos pos) { return null; }
        };
        return new SimLevel(view, DATA.registry(), new BehaviorRegistry(DATA), SimLevel.CLIENT_CHAIN_LIMIT, knownAge ? () -> 17 : null);
    }
}
