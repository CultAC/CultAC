package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.populator.BlockRegistryPopulator;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GeyserBlockStateMappingsTest {
    @Test public void realGeyserPalettesResolveSandWaterAndEveryJavaBlock() throws Exception {
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
            BlockRegistries.CUSTOM_BLOCKS.set(new org.geysermc.geyser.api.block.custom.CustomBlockData[]{skull.getCustomBlockData()});
            BlockRegistryPopulator.populate(BlockRegistryPopulator.Stage.INIT_JAVA);
            BlockRegistryPopulator.populate(BlockRegistryPopulator.Stage.INIT_BEDROCK);
            assertFalse(BlockRegistries.BLOCKS.get().isEmpty());
            // The test server is newer than Geyser and has no ViaVersion platform.
            // Resolve the common vanilla states by name to test real palette inversion.
            int[] javaIds = new int[Block.BLOCK_STATE_REGISTRY.size()];
            for (var state : BlockRegistries.BLOCK_STATES.get()) {
                var serverState = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK, state.toString(), false).blockState();
                javaIds[Block.getId(serverState)] = state.javaId();
            }
            for (var mappings : BlockRegistries.BLOCKS.get().values()) {
                var palette = GeyserBlockStateMappings.invert(mappings, javaIds);
                assertEquals(Blocks.SAND.defaultBlockState(), GeyserBlockStateMappings.resolve(palette,
                        mappings.getBedrockBlock(org.geysermc.geyser.level.block.Blocks.SAND.defaultBlockState())));
                assertEquals(Blocks.WATER.defaultBlockState(), GeyserBlockStateMappings.resolve(palette, mappings.getBedrockWater()));
                assertEquals(Blocks.AIR.defaultBlockState(), GeyserBlockStateMappings.resolve(palette, mappings.getBedrockAir()));
                for (var door : Blocks.OAK_DOOR.getStateDefinition().getPossibleStates()) {
                    var decoded = GeyserBlockStateMappings.resolve(palette, mappings.getBedrockBlock(javaIds[Block.getId(door)]));
                    assertEquals(door.toString(), door.getValue(BlockStateProperties.OPEN), decoded.getValue(BlockStateProperties.OPEN));
                    assertEquals(door.toString(), door.getValue(BlockStateProperties.HORIZONTAL_FACING), decoded.getValue(BlockStateProperties.HORIZONTAL_FACING));
                    assertEquals(door.toString(), door.getValue(BlockStateProperties.DOOR_HINGE), decoded.getValue(BlockStateProperties.DOOR_HINGE));
                    assertEquals(door.toString(), door.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF), decoded.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF));
                }
                for (var definition : mappings.getJavaToBedrockBlocks()) {
                    var state = GeyserBlockStateMappings.resolve(palette, definition);
                    assertTrue(Block.getId(state) >= 0);
                    if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                        assertFalse(state.getValue(BlockStateProperties.WATERLOGGED));
                    }
                }
                for (var definition : mappings.getJavaToVanillaBedrockBlocks()) {
                    assertNotNull(GeyserBlockStateMappings.resolve(palette, definition));
                }
                for (var frame : mappings.getItemFrames().values()) {
                    assertTrue(GeyserBlockStateMappings.resolve(palette, frame).isAir());
                }
                assertEquals(Blocks.PLAYER_HEAD.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 7),
                        GeyserBlockStateMappings.resolve(palette,
                                mappings.getCustomBlockStateDefinitions().get(skull.getFloorBlockState(7))));
                assertEquals(Blocks.PLAYER_WALL_HEAD.defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.WEST),
                        GeyserBlockStateMappings.resolve(palette,
                                mappings.getCustomBlockStateDefinitions().get(skull.getWallBlockState(90))));
            }
        } finally {
            instance.set(null, previous);
            BlockRegistries.CUSTOM_SKULLS.set(previousSkulls);
            BlockRegistries.CUSTOM_BLOCKS.set(previousCustomBlocks);
        }
    }
}
