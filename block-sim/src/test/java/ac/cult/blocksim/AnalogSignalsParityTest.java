package ac.cult.blocksim;

import static ac.cult.blocksim.AnalogSignalsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.AnalogProofRegistries;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.AnalogSignals;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.ParentAnalogSignals;
import ac.cult.blocksim.engine.SimLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Current AnalogSignals against the parent oracle: public outputs and per-instance binding ownership. */
class AnalogSignalsParityTest {
    private static final List<String> SMALL = List.of("minecraft:stone", "minecraft:cake", "minecraft:jukebox", "minecraft:chest");

    private record Pair(AnalogSignals current, ParentAnalogSignals parent) {
        static Pair of(DataTables data, ItemRegistry items, InteractionRegistries registries) {
            return new Pair(new AnalogSignals(data, items, registries), new ParentAnalogSignals(data, items, registries));
        }
        static Pair defaults() { return of(DATA, ITEMS, REGISTRIES); }
        boolean hasOutput(SimLevel level, int state) {
            boolean value = current.hasOutput(level, state);
            assertEquals(parent.hasOutput(level, state), value, () -> "hasOutput " + level.registry().serialize(state));
            return value;
        }
        String output(SimLevel level, int state, BlockPos pos) {
            String value = outcome(() -> current.output(level, state, pos, Direction.NORTH));
            assertEquals(outcome(() -> parent.output(level, state, pos, Direction.NORTH)), value, () -> "output " + level.registry().serialize(state));
            return value;
        }
        String at(World world, SimLevel level, BlockPos pos) { return output(level, world.stateAt(pos), pos); }
    }

    @Test void everyDefaultStateMatchesParentWithoutBlockEntities() {
        var world = new World(); var level = level(world); var pair = Pair.defaults();
        int mapped = 0, nonZero = 0;
        for (int state = 0; state < DATA.registry().stateCount(); state++) {
            world.states.put(POS, state);
            if (pair.hasOutput(level, state)) mapped++;
            if (!pair.output(level, state, POS).equals("v:0")) nonZero++;
        }
        assertTrue(mapped > 1000, "mapped states " + mapped);
        assertTrue(nonZero > 100, "non-zero outputs " + nonZero);
        assertFalse(pair.hasOutput(level, state("stone")));
        assertTrue(pair.hasOutput(level, state("chest")) && pair.hasOutput(level, state("jukebox")) && pair.hasOutput(level, state("cauldron")));
    }

    @Test void propertyKindsReportNonDefaultValues() {
        var world = new World(); var level = level(world); var pair = Pair.defaults();
        int statue = statueState();
        Object[][] cases = {
            {with(state("cake"), "bites", "3"), "v:8"}, {state("candle_cake"), "v:14"},
            {with(state("composter"), "level", "5"), "v:5"}, {with(state("water_cauldron"), "level", "2"), "v:2"},
            {with(state("beehive"), "honey_level", "4"), "v:4"}, {with(state("end_portal_frame"), "eye", "true"), "v:15"},
            {with(state("copper_bulb"), "lit", "true"), "v:15"}, {with(state("respawn_anchor"), "charges", "3"), "v:11"},
            {state("lava_cauldron"), "v:3"}, {with(state("detector_rail"), "powered", "true"), "v:7"},
            {with(statue, "copper_golem_pose", "sitting"), "v:2"}, {with(statue, "copper_golem_pose", "star"), "v:4"},
            {state("chiseled_bookshelf"), "v:0"}, {state("cauldron"), "v:0"}, {state("creaking_heart"), "v:0"},
        };
        for (Object[] row : cases) {
            int state = (Integer) row[0];
            world.states.put(POS, state);
            assertTrue(pair.hasOutput(level, state), () -> DATA.registry().serialize(state));
            assertEquals(row[1], pair.output(level, state, POS), () -> DATA.registry().serialize(state));
        }
        world.detectorRailOutput = 0;
        int rail = with(state("detector_rail"), "powered", "true");
        world.states.put(POS, rail);
        assertEquals("v:0", pair.output(level, rail, POS));
    }

    @Test void containerAndChestOutputsMatchParent() {
        var world = new World(); var level = level(world); var pair = Pair.defaults();
        put(world, POS, state("hopper"), entity("minecraft:hopper", "{\"Items\":[{\"Slot\":0,\"id\":\"minecraft:stone\",\"count\":64}]}"));
        assertEquals("v:3", pair.at(world, level, POS));
        put(world, POS, state("furnace"), entity("minecraft:furnace", "{\"Items\":[{\"Slot\":2,\"id\":\"minecraft:stone\",\"count\":32}]}"));
        assertEquals("v:3", pair.at(world, level, POS));
        put(world, POS, state("decorated_pot"), entity("minecraft:decorated_pot", "{\"item\":{\"id\":\"minecraft:stone\",\"count\":1}}"));
        assertEquals("v:1", pair.at(world, level, POS));
        put(world, POS, state("shulker_box"), entity("minecraft:shulker_box", items(0, 13)));
        assertEquals("v:7", pair.at(world, level, POS));
        put(world, POS, state("barrel"), entity("minecraft:barrel", "{}"));
        assertEquals("v:0", pair.at(world, level, POS));
        put(world, POS, state("chest"), entity("minecraft:chest", items(0, 10)));
        assertEquals("v:6", pair.at(world, level, POS));

        var east = POS.relative(Direction.EAST);
        int left = with(with(state("chest"), "facing", "north"), "type", "left");
        int right = with(left, "type", "right");
        put(world, POS, left, entity("minecraft:chest", items(0, 20)));
        put(world, east, right, entity("minecraft:chest", items(0, 7)));
        assertEquals("v:8", pair.at(world, level, POS));
        assertEquals("v:8", pair.at(world, level, east));
        world.entities.remove(east);
        assertEquals("v:11", pair.at(world, level, POS));
        put(world, east, right, entity("minecraft:chest", items(0, 7)));
        world.cats.add(east);
        assertEquals("v:0", pair.at(world, level, POS));
        world.cats.clear();
        world.states.put(POS.relative(Direction.UP), state("stone"));
        assertEquals("v:0", pair.at(world, level, POS));
        assertEquals("v:0", pair.at(world, level, east));
        world.states.remove(POS.relative(Direction.UP));
        world.entities.remove(POS);
        assertEquals("v:0", pair.at(world, level, POS));
    }

    @Test void entityKindsMatchParent() {
        var world = new World(); var level = level(world); var pair = Pair.defaults();
        put(world, POS, state("command_block"), entity("minecraft:command_block", "{\"SuccessCount\":6}"));
        assertEquals("v:6", pair.at(world, level, POS));
        int sensor = state("sculk_sensor");
        put(world, POS, with(sensor, "sculk_sensor_phase", "active"), entity("minecraft:sculk_sensor", "{\"last_vibration_frequency\":9}"));
        assertEquals("v:9", pair.at(world, level, POS));
        put(world, POS, with(sensor, "sculk_sensor_phase", "cooldown"), entity("minecraft:sculk_sensor", "{\"last_vibration_frequency\":9}"));
        assertEquals("v:0", pair.at(world, level, POS));
        put(world, POS, state("crafter"), entity("minecraft:crafter",
            "{\"disabled_slots\":[0,1],\"Items\":[{\"Slot\":1,\"id\":\"minecraft:stone\",\"count\":1},{\"Slot\":4,\"id\":\"minecraft:stone\",\"count\":2}]}"));
        assertEquals("v:3", pair.at(world, level, POS));
        put(world, POS, with(state("lectern"), "has_book", "true"), entity("minecraft:lectern", "{\"page_count\":3,\"Page\":1,\"has_book_content\":1}"));
        assertEquals("v:8", pair.at(world, level, POS));
        put(world, POS, state("lectern"), entity("minecraft:lectern", "{\"page_count\":3,\"Page\":1,\"has_book_content\":1}"));
        assertEquals("v:0", pair.at(world, level, POS));
        put(world, POS, state("jukebox"), jukebox("minecraft:music_disc_13"));
        assertEquals("v:1", pair.at(world, level, POS));
        put(world, POS, state("jukebox"), entity("minecraft:jukebox", "{}"));
        assertEquals("v:0", pair.at(world, level, POS));
    }

    @Test void sameRegistryKeepsEachInstancesItemsAndInteractions() {
        var smallStone = itemsWithStoneStack(16);
        var songs = songOutput("minecraft:13", 9);
        var hopperWorld = new World();
        put(hopperWorld, POS, state("hopper"), entity("minecraft:hopper", "{\"Items\":[{\"Slot\":0,\"id\":\"minecraft:stone\",\"count\":16}]}"));
        var jukeboxWorld = new World();
        put(jukeboxWorld, POS, state("jukebox"), jukebox("minecraft:music_disc_13"));
        var hopper = level(hopperWorld); var jukebox = level(jukeboxWorld);

        var first = Pair.of(DATA, ITEMS, REGISTRIES);
        var received = Pair.of(DATA, smallStone, songs);
        var second = Pair.of(DATA, ITEMS, REGISTRIES);
        var itemsOnly = Pair.of(DATA, smallStone, REGISTRIES);
        var songsOnly = Pair.of(DATA.withEnabledFeatures(Set.of("cult:analog-proof")), ITEMS, songs);
        assertNotSame(DATA, DATA.withEnabledFeatures(Set.of("cult:analog-proof")));
        for (int pass = 0; pass < 2; pass++) {
            for (var pair : List.of(first, second)) {
                assertEquals("v:1", pair.at(hopperWorld, hopper, POS));
                assertEquals("v:1", pair.at(jukeboxWorld, jukebox, POS));
            }
            assertEquals("v:3", received.at(hopperWorld, hopper, POS));
            assertEquals("v:9", received.at(jukeboxWorld, jukebox, POS));
            assertEquals("v:3", itemsOnly.at(hopperWorld, hopper, POS));
            assertEquals("v:1", itemsOnly.at(jukeboxWorld, jukebox, POS));
            assertEquals("v:1", songsOnly.at(hopperWorld, hopper, POS));
            assertEquals("v:9", songsOnly.at(jukeboxWorld, jukebox, POS));
            Pair.of(DATA, smallStone, songs);
        }
        var nullRegistries = Pair.of(DATA, ITEMS, null);
        assertTrue(nullRegistries.hasOutput(jukebox, state("jukebox")));
        assertEquals("v:1", nullRegistries.at(hopperWorld, hopper, POS));
        assertEquals("e:java.lang.NullPointerException", nullRegistries.at(jukeboxWorld, jukebox, POS));
    }

    @Test void distinctRegistryClassifiesItsOwnDefinitions() {
        var custom = tables(SMALL, Map.of("minecraft:cake", "x.y.LavaCauldronBlock"), Set.of());
        var reg = custom.registry();
        int customCake = reg.block("minecraft:cake").defaultState(), customStone = reg.block("minecraft:stone").defaultState();
        var customWorld = new World(customStone); var customLevel = bareLevel(customWorld, reg);
        var defaultWorld = new World(); var defaultLevel = level(defaultWorld);
        int cake = with(state("cake"), "bites", "6");

        var before = Pair.defaults();
        var own = Pair.of(custom, ITEMS, REGISTRIES);
        var after = Pair.defaults();
        for (var pair : List.of(before, after)) {
            defaultWorld.states.put(POS, cake);
            assertEquals("v:2", pair.output(defaultLevel, cake, POS));
            for (int state = 0; state < reg.stateCount(); state++) assertFalse(pair.hasOutput(customLevel, state));
        }
        assertEquals("v:3", own.output(customLevel, reg.with(customCake, "bites", "6"), POS));
        assertFalse(own.hasOutput(customLevel, customStone));
        assertTrue(own.hasOutput(customLevel, reg.block("minecraft:jukebox").defaultState()));
        assertTrue(own.hasOutput(customLevel, reg.block("minecraft:chest").defaultState()));
        for (int state = 0; state < DATA.registry().stateCount(); state++) assertFalse(own.hasOutput(defaultLevel, state));
    }

    @Test void equalButDistinctDefinitionsAreNotClassified() {
        var copy = equalCopy();
        assertNotSame(DATA.registry(), copy.registry());
        var original = DATA.registry().block("minecraft:chest"); var equal = copy.registry().block("minecraft:chest");
        assertEquals(original, equal); assertNotSame(original, equal);
        var copyLevel = bareLevel(new World(), copy.registry()); var defaultLevel = bareLevel(new World(), DATA.registry());
        var defaults = Pair.defaults(); var copied = Pair.of(copy, ITEMS, REGISTRIES);
        int mapped = 0;
        for (int state = 0; state < DATA.registry().stateCount(); state++) {
            assertFalse(defaults.hasOutput(copyLevel, state));
            assertFalse(copied.hasOutput(defaultLevel, state));
            boolean own = defaults.hasOutput(defaultLevel, state);
            assertEquals(own, copied.hasOutput(copyLevel, state));
            if (own) mapped++;
        }
        assertTrue(mapped > 1000);
    }

    @Test void registriesSharingDefinitionInstancesShareClassification() {
        var shared = wrap(AnalogProofRegistries.sameDefinitions(DATA.registry(), FACTS));
        assertNotSame(DATA.registry(), shared.registry());
        assertSame(DATA.registry().block("minecraft:chest"), shared.registry().block("minecraft:chest"));
        var sharedLevel = bareLevel(new World(), shared.registry()); var defaultLevel = bareLevel(new World(), DATA.registry());
        var defaults = Pair.defaults(); var other = Pair.of(shared, ITEMS, REGISTRIES);
        for (int state = 0; state < DATA.registry().stateCount(); state++) {
            boolean own = defaults.hasOutput(defaultLevel, state);
            assertEquals(own, defaults.hasOutput(sharedLevel, state));
            assertEquals(own, other.hasOutput(defaultLevel, state));
        }
    }

    @Test void familyPrecedenceFollowsHierarchyOrder() {
        assertEquals("true v:3", stoneAs("a.b.LavaCauldronBlock,a.b.AbstractCauldronBlock,a.b.Block"));
        assertEquals("true v:0", stoneAs("a.b.AbstractCauldronBlock,a.b.LavaCauldronBlock"));
        assertEquals("true v:14", stoneAs("CandleCakeBlock"));
        assertEquals("true v:14", stoneAs("a.b.c.CandleCakeBlock,a.ChestBlock"));
        assertEquals("true v:14", stoneAs(",a.CandleCakeBlock"));
        assertEquals("true v:14", stoneAs("a.CandleCakeBlock,"));
        assertEquals("true v:0", stoneAs("x.ChiseledBookShelfBlock,x.CandleCakeBlock"));
    }

    @Test void unmappedAndAbsentHierarchyMatchesHaveNoOutput() {
        assertEquals("false v:0", stoneAs(""));
        assertEquals("false v:0", stoneAs("a.b.StoneBlock,a.b.Block,a.b.state.BlockBehaviour"));
        assertEquals("false v:0", stoneAs("a.NotCandleCakeBlock"));
        assertEquals("false v:0", stoneAs("a.CandleCakeBlock.Inner"));
        assertEquals("false v:0", stoneAs("a.candlecakeblock"));
        assertEquals("false v:0", stoneAs("a.b.CandleCakeBlock$Inner"));
    }

    @Test void mappedKindWithoutItsPropertyFailsLikeParent() {
        assertEquals("true e:java.lang.IllegalArgumentException:Block minecraft:stone has no property level", stoneAs("a.ComposterBlock"));
        assertEquals("true e:java.lang.IllegalArgumentException:Block minecraft:stone has no property bites", stoneAs("a.CakeBlock"));
    }

    @Test void missingHierarchyFailsConstructionWithoutPoisoningLaterInstances() {
        var broken = tables(SMALL, Map.of(), Set.of("minecraft:jukebox"));
        var valid = tables(SMALL, Map.of(), Set.of());
        for (int round = 0; round < 2; round++) {
            assertThrows(NullPointerException.class, () -> new ParentAnalogSignals(broken, ITEMS, REGISTRIES));
            assertThrows(NullPointerException.class, () -> new AnalogSignals(broken, ITEMS, REGISTRIES));
            var reg = valid.registry();
            var pair = Pair.of(valid, ITEMS, REGISTRIES);
            var level = bareLevel(new World(reg.block("minecraft:stone").defaultState()), reg);
            assertTrue(pair.hasOutput(level, reg.block("minecraft:jukebox").defaultState()));
            assertTrue(pair.hasOutput(level, reg.block("minecraft:cake").defaultState()));
            var brokenLevel = bareLevel(new World(broken.registry().block("minecraft:stone").defaultState()), broken.registry());
            for (int state = 0; state < broken.registry().stateCount(); state++) assertFalse(pair.hasOutput(brokenLevel, state));
            assertTrue(Pair.defaults().hasOutput(level(new World()), state("jukebox")));
        }
    }

    @Test void argumentFailuresKeepConstructorEvaluationOrder() {
        var parentNoItems = assertThrows(NullPointerException.class, () -> new ParentAnalogSignals(null, null, REGISTRIES));
        var currentNoItems = assertThrows(NullPointerException.class, () -> new AnalogSignals(null, null, REGISTRIES));
        assertEquals("java.util.Objects", parentNoItems.getStackTrace()[0].getClassName());
        assertEquals("java.util.Objects", currentNoItems.getStackTrace()[0].getClassName());
        var parentNoData = assertThrows(NullPointerException.class, () -> new ParentAnalogSignals(null, ITEMS, REGISTRIES));
        var currentNoData = assertThrows(NullPointerException.class, () -> new AnalogSignals(null, ITEMS, REGISTRIES));
        assertNotEquals("java.util.Objects", parentNoData.getStackTrace()[0].getClassName());
        assertNotEquals("java.util.Objects", currentNoData.getStackTrace()[0].getClassName());
        var brokenNoItems = tables(SMALL, Map.of(), Set.of("minecraft:jukebox"));
        var current = assertThrows(NullPointerException.class, () -> new AnalogSignals(brokenNoItems, null, REGISTRIES));
        assertEquals("java.util.Objects", current.getStackTrace()[0].getClassName());
    }

    @Test void concurrentConstructionSeesCompleteClassification() throws Exception {
        var copy = equalCopy();
        DataTables[] tables = {DATA, copy};
        SimLevel[] levels = {bareLevel(new World(), DATA.registry()), bareLevel(new World(), copy.registry())};
        boolean[] expected = new boolean[DATA.registry().stateCount()];
        var parent = new ParentAnalogSignals(DATA, ITEMS, REGISTRIES);
        for (int state = 0; state < expected.length; state++) expected[state] = parent.hasOutput(levels[0], state);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (int thread = 0; thread < 4; thread++) {
                int offset = thread;
                results.add(pool.submit(() -> {
                    start.await();
                    int checked = 0;
                    for (int i = 0; i < 16; i++) {
                        int which = (i + offset) & 1;
                        var signals = new AnalogSignals(tables[which], ITEMS, REGISTRIES);
                        boolean[] actual = new boolean[expected.length];
                        for (int state = 0; state < actual.length; state++) actual[state] = signals.hasOutput(levels[which], state);
                        if (!Arrays.equals(expected, actual)) throw new AssertionError("Incomplete classification for table " + which + " iteration " + i);
                        checked++;
                    }
                    return checked;
                }));
            }
            start.countDown();
            for (var result : results) assertEquals(16, result.get(2, TimeUnit.MINUTES));
        } finally {
            pool.shutdownNow();
        }
    }

    private static String stoneAs(String hierarchy) {
        var data = tables(List.of("minecraft:stone"), Map.of("minecraft:stone", hierarchy), Set.of());
        int stone = data.registry().block("minecraft:stone").defaultState();
        var world = new World(stone);
        var pair = Pair.of(data, ITEMS, REGISTRIES);
        return pair.hasOutput(bareLevel(world, data.registry()), stone) + " " + pair.output(bareLevel(world, data.registry()), stone, POS);
    }
    private static int statueState() {
        for (var block : DATA.registry().blocks()) {
            for (String family : block.bindings().get("classHierarchy").split(",")) {
                if (family.endsWith(".CopperGolemStatueBlock")) return block.defaultState();
            }
        }
        throw new AssertionError("No CopperGolemStatueBlock in 26.3 data");
    }
    private static void put(World world, BlockPos pos, int state, BlockEntityData entity) {
        world.states.put(pos, state);
        world.entities.put(pos, entity);
    }
    private static String items(int firstSlot, int fullStacks) {
        var text = new StringBuilder("{\"Items\":[");
        for (int i = 0; i < fullStacks; i++) {
            if (i > 0) text.append(',');
            text.append("{\"Slot\":").append(firstSlot + i).append(",\"id\":\"minecraft:stone\",\"count\":64}");
        }
        return text.append("]}").toString();
    }
    private static BlockEntityData jukebox(String disc) {
        return entity("minecraft:jukebox", "{\"RecordItem\":{\"id\":\"" + disc + "\",\"count\":1}}");
    }
}
