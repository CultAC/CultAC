package ac.cult.blocksim;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataTablesTest {
    @Test void everyWireStateAndPropertyTransitionMatchesVanillaReport() throws Exception {
        var data = DataTables.load("26.3");
        try (var reportStream = getClass().getResourceAsStream("/block-sim/26.3/blocks.json.gz")) {
            assertNotNull(reportStream);
            var report = JsonParser.parseReader(new InputStreamReader(new GZIPInputStream(reportStream), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(report.size(), data.registry().blocks().size());
            int count = 0;
            for (var blockEntry : report.entrySet()) {
                var block = data.registry().block(blockEntry.getKey());
                for (JsonElement stateElement : blockEntry.getValue().getAsJsonObject().getAsJsonArray("states")) {
                    var state = stateElement.getAsJsonObject();
                    int id = state.get("id").getAsInt();
                    assertEquals(block.key(), data.registry().block(id).key());
                    if (state.has("default")) assertEquals(id, block.defaultState());
                    int rebuilt = block.defaultState();
                    if (state.has("properties")) for (var property : state.getAsJsonObject("properties").entrySet()) {
                        String value = property.getValue().getAsString();
                        assertEquals(value, data.registry().value(id, property.getKey()), () -> block.key() + " state " + id);
                        rebuilt = data.registry().with(rebuilt, property.getKey(), value);
                    }
                    assertEquals(id, rebuilt, () -> block.key() + " state " + id);
                    count++;
                }
            }
            assertEquals(count, data.registry().stateCount());
        }
    }

    @Test void sourceEstablishedFactsCoverFluidsShapesToolsAndTags() throws Exception {
        var data = DataTables.load("26.3");
        var registry = data.registry();
        assertEquals("4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d", data.vanillaSha256());
        assertTrue(data.enabledFeatures().contains("minecraft:vanilla"));
        int stone = registry.block("minecraft:stone").defaultState();
        assertEquals(1.5f, registry.facts(stone).destroyTime());
        assertEquals(0.6f, registry.facts(stone).friction());
        assertEquals(1.0f, registry.facts(stone).speedFactor());
        assertEquals(1.0f, registry.facts(stone).jumpFactor());
        assertEquals(StateFacts.PushReaction.PUSH_PULL, registry.facts(stone).pushReaction());
        assertTrue(registry.facts(stone).has(StateFacts.FULL_COLLISION));
        assertFalse(registry.facts(stone).has(StateFacts.BLOCK_ENTITY));
        assertTrue(registry.facts(registry.block("minecraft:chest").defaultState()).has(StateFacts.BLOCK_ENTITY));
        assertEquals(0.98f, registry.facts(registry.block("minecraft:ice").defaultState()).friction());
        assertEquals(0.4f, registry.facts(registry.block("minecraft:soul_sand").defaultState()).speedFactor());
        assertEquals(0.5f, registry.facts(registry.block("minecraft:honey_block").defaultState()).jumpFactor());
        assertTrue(registry.facts(stone).has(StateFacts.CORRECT_TOOL));
        assertEquals(0x3ffff, registry.facts(stone).sturdyBits());
        assertEquals(1, registry.facts(stone).collision().size());
        assertTrue(registry.facts(registry.block("minecraft:air").defaultState()).collision().isEmpty());
        int water = registry.block("minecraft:water").defaultState();
        assertEquals(8, registry.facts(water).fluidAmount());
        assertEquals(water, registry.facts(water).fluidBlock());
        int slab = registry.with(registry.block("minecraft:oak_slab").defaultState(), "waterlogged", "true");
        assertEquals("minecraft:water", registry.facts(slab).fluid());
        assertTrue(data.tags().get("block:minecraft:snow").contains("minecraft:snow_block"));
        assertTrue(data.tags().get("worldgen/biome:minecraft:is_overworld").contains("minecraft:plains"));
        assertFalse(data.tags().get("worldgen/biome:minecraft:is_nether").contains("minecraft:plains"));
        assertTrue(data.items().stream().anyMatch(item -> item.key().equals("minecraft:diamond_pickaxe")
            && item.defaultComponentsJson().contains("minecraft:tool")));
        assertTrue(registry.facts(registry.block("minecraft:shulker_box").defaultState()).dynamicBits() != 0);
    }

    @Test void bundledWorldDefinitionsResolveDimensionTagsAndDriveTheNightClockBoundary() throws Exception {
        var defaults = ac.cult.blocksim.environment.ClientWorldDefaults.defaults();
        var overworld = defaults.resolve("dimension_type", "overworld");
        var dimension = new ac.cult.blocksim.environment.DimensionData("overworld", overworld.data(), overworld.json());
        assertEquals(-64, dimension.minY());
        assertEquals(384, dimension.height());
        assertTrue(dimension.hasSkyLight());
        var nether = defaults.resolve("dimension_type", "the_nether");
        assertTrue(new ac.cult.blocksim.environment.DimensionData("the_nether", nether.data(), nether.json()).hasFastLava());
        var members = defaults.tags("timeline").get("minecraft:in_overworld");
        assertNotNull(members);
        var timelines = members.stream().map(key -> ac.cult.blocksim.data.nbt.NbtJson.encode(defaults.resolve("timeline", key).data())).toList();
        var biome = ac.cult.blocksim.data.nbt.NbtJson.encode(defaults.resolve("worldgen/biome", "plains").data()).getAsJsonObject();
        var clocks = new ac.cult.blocksim.engine.ClientClocks();
        clocks.handleUpdates(0, java.util.Map.of("minecraft:overworld", new ac.cult.blocksim.engine.ClientClocks.State(18000, 0, 0)));
        var metadata = new ac.cult.blocksim.environment.EnvironmentData(dimension.attributes(), java.util.Map.of(5, biome.getAsJsonObject("attributes")), timelines);
        var environment = metadata.create(clocks);
        assertTrue(environment.at(5).creakingActive());
        clocks.handleUpdates(1, java.util.Map.of("minecraft:overworld", new ac.cult.blocksim.engine.ClientClocks.State(23401, 0, 0)));
        environment.tick();
        assertFalse(environment.at(5).creakingActive());
    }
}
