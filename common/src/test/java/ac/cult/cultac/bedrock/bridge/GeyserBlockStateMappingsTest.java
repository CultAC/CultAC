package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineBlockStateParser;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.populator.BlockRegistryPopulator;
import org.junit.Test;

public class GeyserBlockStateMappingsTest {
    @Test
    public void realGeyserPalettesResolveSandWaterAndEveryJavaBlock() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var geyser = mock(GeyserImpl.class, RETURNS_DEEP_STUBS);
        when(geyser.getBootstrap().getResourceOrThrow(anyString())).thenAnswer(call -> {
            var stream = GeyserImpl.class.getClassLoader().getResourceAsStream(call.getArgument(0));
            assertNotNull(stream);
            return stream;
        });
        var instance = GeyserImpl.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, geyser);
        var previousSkulls = BlockRegistries.CUSTOM_SKULLS.get();
        var previousCustomBlocks = BlockRegistries.CUSTOM_BLOCKS.get();
        try {
            org.geysermc.geyser.level.block.Blocks.AIR.javaId();
            var skull = new org.geysermc.geyser.registry.type.CustomSkull("cultac_palette_test");
            BlockRegistries.CUSTOM_SKULLS.set(new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>());
            BlockRegistries.CUSTOM_SKULLS.register(skull.getSkinHash(), skull);
            BlockRegistries.CUSTOM_BLOCKS.set(
                    new org.geysermc.geyser.api.block.custom.CustomBlockData[] {skull.getCustomBlockData()});
            BlockRegistryPopulator.populate(BlockRegistryPopulator.Stage.INIT_JAVA);
            BlockRegistryPopulator.populate(BlockRegistryPopulator.Stage.INIT_BEDROCK);
            assertFalse(BlockRegistries.BLOCKS.get().isEmpty());
            // The test server is newer than Geyser and has no ViaVersion platform.
            // Resolve the common vanilla states by name to test real palette inversion.
            var registry = DataTables.defaults().registry();
            int[] javaIds = new int[registry.stateCount()];
            for (var state : BlockRegistries.BLOCK_STATES.get()) {
                int serverState = OfflineBlockStateParser.parse(state.toString());
                javaIds[serverState] = state.javaId();
            }
            for (var mappings : BlockRegistries.BLOCKS.get().values()) {
                var palette = GeyserBlockStateMappings.invert(mappings, javaIds);
                assertEquals(
                        registry.block("minecraft:sand").defaultState(),
                        GeyserBlockStateMappings.resolve(
                                palette,
                                mappings.getBedrockBlock(
                                        org.geysermc.geyser.level.block.Blocks.SAND.defaultBlockState())));
                assertEquals(
                        registry.block("minecraft:water").defaultState(),
                        GeyserBlockStateMappings.resolve(palette, mappings.getBedrockWater()));
                assertEquals(
                        registry.block("minecraft:air").defaultState(),
                        GeyserBlockStateMappings.resolve(palette, mappings.getBedrockAir()));
                var doorBlock = registry.block("minecraft:oak_door");
                for (int door = doorBlock.firstState();
                        door < doorBlock.firstState() + doorBlock.stateCount();
                        door++) {
                    int decoded = GeyserBlockStateMappings.resolve(palette, mappings.getBedrockBlock(javaIds[door]));
                    for (String property : new String[] {"open", "facing", "hinge", "half"}) {
                        assertEquals(
                                registry.serialize(door),
                                registry.value(door, property),
                                registry.value(decoded, property));
                    }
                }
                for (var definition : mappings.getJavaToBedrockBlocks()) {
                    var state = GeyserBlockStateMappings.resolve(palette, definition);
                    assertTrue(state >= 0
                            && state
                                    < ac.cult.blocksim.data.DataTables.defaults()
                                            .registry()
                                            .stateCount());
                    if (ac.cult.blocksim.data.BlockProps.WATERLOGGED.has(state)) {
                        assertFalse(ac.cult.blocksim.data.BlockProps.WATERLOGGED.booleanValue(state));
                    }
                }
                for (var definition : mappings.getJavaToVanillaBedrockBlocks()) {
                    int state = GeyserBlockStateMappings.resolve(palette, definition);
                    assertTrue(state >= 0
                            && state
                                    < ac.cult.blocksim.data.DataTables.defaults()
                                            .registry()
                                            .stateCount());
                }
                for (var frame : mappings.getItemFrames().values()) {
                    assertTrue(ac.cult.blocksim.data.DataTables.defaults()
                            .registry()
                            .facts(GeyserBlockStateMappings.resolve(palette, frame))
                            .has(ac.cult.blocksim.data.StateFacts.AIR));
                }
                assertEquals(
                        registry.with(registry.block("minecraft:player_head").defaultState(), "rotation", "7"),
                        GeyserBlockStateMappings.resolve(
                                palette,
                                mappings.getCustomBlockStateDefinitions().get(skull.getFloorBlockState(7))));
                assertEquals(
                        registry.with(
                                registry.block("minecraft:player_wall_head").defaultState(), "facing", "west"),
                        GeyserBlockStateMappings.resolve(
                                palette,
                                mappings.getCustomBlockStateDefinitions().get(skull.getWallBlockState(90))));
            }
        } finally {
            instance.set(null, previous);
            BlockRegistries.CUSTOM_SKULLS.set(previousSkulls);
            BlockRegistries.CUSTOM_BLOCKS.set(previousCustomBlocks);
        }
    }
}
