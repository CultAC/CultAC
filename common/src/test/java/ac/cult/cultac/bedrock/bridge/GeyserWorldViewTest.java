package ac.cult.cultac.bedrock.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.blocksim.environment.DimensionData;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import ac.cult.cultac.utils.latency.ClientWorldView.Heights;
import ac.cult.cultac.utils.latency.CompensatedWorld.ClientboundDimensionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeyserWorldViewTest {
    private static final PaintingSize KEBAB = new PaintingSize(1, 1);
    /** Geyser 2.12: vanilla biomes and motives map, and unmapped biomes fall back by dimension. */
    private static final GeyserWorldRules GEYSER = new GeyserWorldRules(
            false,
            ClientWorldDefaults.defaults().entries(ClientWorldRegistries.BIOMES).keySet(),
            Map.of("minecraft:kebab", KEBAB, "minecraft:pointer", new PaintingSize(4, 4)),
            KEBAB,
            true);

    private static List<ClientWorldRegistries.Definition> vanilla() {
        return ClientWorldRegistries.modelDefaults().registries().get(ClientWorldRegistries.DIMENSIONS);
    }

    private static DimensionData type(String key, List<ClientWorldRegistries.Definition> dimensions) {
        var definition = dimensions.stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst()
                .orElseThrow();
        return new DimensionData(
                definition.key(), definition.value().data(), definition.value().json());
    }

    /** The overworld's type with other heights and skybox, as a datapack defines one. */
    private static NbtValue.Compound custom(int minY, int height, String skybox) {
        var fields = new HashMap<>(ClientWorldDefaults.defaults()
                .resolve(ClientWorldRegistries.DIMENSIONS, "minecraft:overworld")
                .data()
                .values());
        fields.put("min_y", new NbtValue.Numeric(NbtValue.Kind.INT, minY));
        fields.put("height", new NbtValue.Numeric(NbtValue.Kind.INT, height));
        fields.put("logical_height", new NbtValue.Numeric(NbtValue.Kind.INT, height));
        if (skybox != null) fields.put("skybox", new NbtValue.Text(skybox));
        return new NbtValue.Compound(fields);
    }

    private static ClientWorldRegistries.Definition definition(String key, NbtValue.Compound data) {
        var json =
                ac.cult.blocksim.environment.WorldRegistryJson.dimension(data).toString();
        return new ClientWorldRegistries.Definition(key, new ClientWorldDefaults.Entry(data, json));
    }

    private static List<ClientWorldRegistries.Definition> with(ClientWorldRegistries.Definition... extra) {
        var dimensions = new ArrayList<>(List.of(extra));
        dimensions.addAll(vanilla());
        return dimensions;
    }

    @Test
    void vanillaLevelsHaveTheirBedrockDimensionsHeights() {
        var view = new GeyserWorldView(() -> GEYSER);
        var dimensions = vanilla();
        assertEquals(new Heights(-64, 320), view.heights(type("minecraft:overworld", dimensions), dimensions));
        assertEquals(new Heights(-64, 320), view.heights(type("minecraft:overworld_caves", dimensions), dimensions));
        assertEquals(new Heights(0, 128), view.heights(type("minecraft:the_nether", dimensions), dimensions));
        assertEquals(new Heights(0, 256), view.heights(type("minecraft:the_end", dimensions), dimensions));
    }

    @Test
    void overworldSpansTheFirstLoginsOverworldLikeTypesWithinGeysersBounds() {
        var view = new GeyserWorldView(() -> GEYSER);
        var dimensions = with(
                definition("earth:tall", custom(-128, 768, null)),
                // A nether-like type does not extend the overworld.
                definition("earth:deep", custom(-2032, 4064, "none")));
        var tall = type("earth:tall", dimensions);
        assertEquals(new Heights(-128, 512), view.heights(tall, dimensions));
        assertEquals(new Heights(-128, 512), view.heights(type("minecraft:overworld", dimensions), dimensions));
        assertEquals(new Heights(0, 128), view.heights(type("earth:deep", dimensions), dimensions));
        // GeyserSession#connect runs once; a later configuration does not resize the Bedrock overworld.
        assertEquals(new Heights(-128, 512), view.heights(type("minecraft:overworld", vanilla()), vanilla()));
    }

    @Test
    void geysersNetherOptionSendsNetherLikeLevelsAsTheEnd() {
        var rules = new GeyserWorldRules(true, GEYSER.biomes(), GEYSER.paintings(), KEBAB, true);
        var view = new GeyserWorldView(() -> rules);
        var dimensions = with(definition("test:caves", custom(0, 256, "none")));
        assertEquals(new Heights(0, 256), view.heights(type("minecraft:the_nether", dimensions), dimensions));
        assertEquals(new Heights(0, 256), view.heights(type("test:caves", dimensions), dimensions));
        // Still nether-like for its fallback biome, as BiomeTranslator checks that first.
        assertEquals("minecraft:nether_wastes", view.biome("test:unmapped", type("test:caves", dimensions)));
    }

    @Test
    void unmappedBiomesFallBackAsTheRunningGeyserDoes() {
        var dimensions = with(
                definition("test:nether_like", custom(0, 256, "none")),
                definition("test:end_like", custom(0, 256, "end")));
        var current = new GeyserWorldView(() -> GEYSER);
        assertEquals("minecraft:plains", current.biome("minecraft:plains", type("minecraft:the_nether", dimensions)));
        assertEquals("minecraft:ocean", current.biome("test:unmapped", type("minecraft:overworld", dimensions)));
        assertEquals("minecraft:ocean", current.biome("test:unmapped", null));
        assertEquals(
                "minecraft:nether_wastes", current.biome("test:unmapped", type("minecraft:the_nether", dimensions)));
        assertEquals("minecraft:nether_wastes", current.biome("test:unmapped", type("test:nether_like", dimensions)));
        assertEquals("minecraft:the_end", current.biome("test:unmapped", type("minecraft:the_end", dimensions)));
        assertEquals("minecraft:the_end", current.biome("test:unmapped", type("test:end_like", dimensions)));

        var older = new GeyserWorldView(
                () -> new GeyserWorldRules(false, GEYSER.biomes(), GEYSER.paintings(), KEBAB, false));
        assertEquals("minecraft:ocean", older.biome("test:unmapped", type("minecraft:the_nether", dimensions)));
        assertEquals("minecraft:ocean", older.biome("test:unmapped", type("test:end_like", dimensions)));
    }

    @Test
    void bedrockBiomesKeepTheModelsDefinition() {
        var view = new GeyserWorldView(() -> GEYSER);
        var ocean = ClientWorldDefaults.defaults().resolve(ClientWorldRegistries.BIOMES, "minecraft:ocean");
        var plains = ClientWorldDefaults.defaults().resolve(ClientWorldRegistries.BIOMES, "minecraft:plains");
        assertSame(plains, view.biomeDefinition("minecraft:plains", ocean));
        assertSame(ocean, view.biomeDefinition("test:only_received", ocean));
    }

    @Test
    void paintingsWithoutABedrockMotiveShowAsKebab() {
        var view = new GeyserWorldView(() -> GEYSER);
        assertEquals(new PaintingSize(4, 4), view.painting("minecraft:pointer", new PaintingSize(1, 1)));
        assertEquals(KEBAB, view.painting("test:mural", new PaintingSize(3, 2)));
        assertEquals(KEBAB, view.painting("minecraft:not_a_motive", new PaintingSize(2, 2)));

        var floodgateOnly = new GeyserWorldView(() -> GeyserWorldRules.WITHOUT_GEYSER);
        assertEquals(new PaintingSize(4, 4), floodgateOnly.painting("minecraft:pointer", null));
        assertEquals(KEBAB, floodgateOnly.painting("test:mural", new PaintingSize(3, 2)));
        assertEquals("minecraft:ocean", floodgateOnly.biome("test:unmapped", type("minecraft:the_nether", vanilla())));
    }

    @Test
    void bedrockConnectionResolvesDatapackShiftedDimensionsAndCustomPaintings() {
        var state = new ClientComponentRegistries(new GeyserWorldView(() -> GEYSER));
        var dimensions = new ArrayList<RegistryEntry>();
        // A datapack type sorts before the vanilla ones, shifting their registry IDs.
        dimensions.add(new RegistryEntry("earth:tall", BinaryNbt.write(custom(-128, 640, null))));
        for (var vanilla : vanilla()) dimensions.add(new RegistryEntry(vanilla.key(), null));
        state.appendBedrockWorldContents(new RegistryData(ClientWorldRegistries.DIMENSIONS, dimensions));
        var mural = new NbtValue.Compound(Map.of(
                "width", new NbtValue.Numeric(NbtValue.Kind.INT, 3),
                "height", new NbtValue.Numeric(NbtValue.Kind.INT, 2),
                "asset_id", new NbtValue.Text("test:mural")));
        state.appendBedrockWorldContents(new RegistryData(
                ClientWorldRegistries.PAINTINGS,
                List.of(
                        new RegistryEntry("test:mural", BinaryNbt.write(mural)),
                        new RegistryEntry("minecraft:pointer", null))));
        var world = state.worldRegistries(ClientWorldRegistries::modelDefaults);
        state.publishBedrockWorld(state.stageBedrockWorld(world, ProtocolVersion.V26_3));

        var tall = world.dimension(0).dimension();
        assertEquals("earth:tall", tall.key());
        assertEquals(640, tall.height());
        assertEquals("minecraft:overworld", world.dimension(1).dimension().key());
        assertEquals(new Heights(-128, 512), world.heights(tall));
        assertEquals(KEBAB, world.painting("test:mural"));
        assertEquals(KEBAB, world.initialPainting());
        assertEquals(new PaintingSize(4, 4), world.painting("minecraft:pointer"));
        String biome = world.biome("test:unmapped", tall);
        assertEquals("minecraft:ocean", biome);
        assertTrue(world.canRain(biome, new BlockPos(0, 64, 0), 63));
    }

    @Test
    void clientHoldsOnlySectionsWithinItsHeights() {
        var nether = new ClientboundDimensionData("minecraft:the_nether", 0, 256, 0, 128);
        assertEquals(16, nether.sectionCount());
        assertTrue(nether.clientHoldsSection(7));
        assertFalse(nether.clientHoldsSection(8));
        var java = new ClientboundDimensionData("minecraft:the_nether", 0, 256);
        assertTrue(java.clientHoldsSection(15));
    }
}
