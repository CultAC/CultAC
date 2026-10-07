package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivedBiomesTest {
    @Test
    void receivedPalettesClampVerticallyReplaceAndUsePlainsForMissingClientChunks() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.clientBiomeZoomSeed(55);
            world.ensureValidationChunkLoaded(0, 0);
            var names = ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults();
            int desert = names.id("minecraft:worldgen/biome", "minecraft:desert");
            int plains = names.id("minecraft:worldgen/biome", "minecraft:plains");
            int forest = names.id("minecraft:worldgen/biome", "minecraft:forest");
            int[][] palettes = new int[world.getLastClientboundSectionCount()][64];
            for (var palette : palettes) Arrays.fill(palette, desert);
            Arrays.fill(palettes[0], plains);
            Arrays.fill(palettes[palettes.length - 1], forest);
            world.applyClientBiomes("minecraft:overworld", 0, 0, palettes);
            var view = new BlockSimulatorWorldView(
                    world,
                    player.checkManager.getListener(ac.cult.cultac.events.packets.PacketWorldBorder.class),
                    ModelBlockStates.project(ProtocolVersion.V26_3, ProtocolVersion.V26_3, ProtocolVersion.V26_3));
            var center = new ac.cult.blocksim.engine.BlockPos(8, 64, 8);
            assertEquals("minecraft:desert", view.biomeKeyAt(center));
            assertFalse(player.getWorldRegistries().canRain(view.biomeKeyAt(center), center, 63));
            var rainPos = new ac.cult.cultac.protocol.value.BlockPos(8, 64, 8);
            world.applyClientWeather(ac.cult.cultac.protocol.value.GameEventType.RAIN_LEVEL_CHANGE, 1.0F);
            assertFalse(world.clientIsRainingAt(rainPos));
            assertEquals("minecraft:plains", view.biomeKeyAt(new ac.cult.blocksim.engine.BlockPos(8, -1000, 8)));
            assertEquals("minecraft:forest", view.biomeKeyAt(new ac.cult.blocksim.engine.BlockPos(8, 1000, 8)));
            assertEquals("minecraft:plains", view.biomeKeyAt(new ac.cult.blocksim.engine.BlockPos(40, 64, 40)));
            for (var palette : palettes) Arrays.fill(palette, forest);
            // The caller's palette arrays cannot mutate a received snapshot.
            assertEquals("minecraft:desert", view.biomeKeyAt(center));
            world.applyClientBiomes("minecraft:overworld", 0, 0, palettes);
            assertEquals("minecraft:forest", view.biomeKeyAt(center));
            assertTrue(player.getWorldRegistries().canRain(view.biomeKeyAt(center), center, 63));
            assertTrue(world.clientIsRainingAt(rainPos));
            world.applyClientWeather(ac.cult.cultac.protocol.value.GameEventType.RAIN_LEVEL_CHANGE, 0.2F);
            assertTrue(world.clientIsRainingAt(rainPos)); // Native Level compares this float with double 0.2.
            world.applyClientWeather(
                    ac.cult.cultac.protocol.value.GameEventType.RAIN_LEVEL_CHANGE, Math.nextDown(0.2F));
            assertFalse(world.clientIsRainingAt(rainPos));
            world.applyClientWeather(ac.cult.cultac.protocol.value.GameEventType.RAIN_LEVEL_CHANGE, Float.NaN);
            assertFalse(world.clientIsRainingAt(rainPos));
            world.applyClientWeather(ac.cult.cultac.protocol.value.GameEventType.STOP_RAINING, 0.0F);
            assertTrue(world.clientIsRainingAt(rainPos)); // Wire id 2 sets the native level to 1.
            world.applyClientWeather(ac.cult.cultac.protocol.value.GameEventType.START_RAINING, 1.0F);
            assertFalse(world.clientIsRainingAt(rainPos)); // Wire id 1 sets the native level to 0.
            world.applyClientWeather(ac.cult.cultac.protocol.value.GameEventType.RAIN_LEVEL_CHANGE, 1.0F);
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            world.applyBlockChangeRawDANGER(8, 65, 8, stone);
            assertFalse(world.clientIsRainingAt(rainPos));
            world.applyBlockChangeRawDANGER(8, 65, 8, 0);
            assertTrue(world.clientIsRainingAt(rainPos));
            var skyMask = new java.util.BitSet();
            skyMask.set((64 - world.getMinHeight()) / 16 + 1);
            byte[] sky = new byte[2048], block = new byte[2048];
            Arrays.fill(sky, (byte) 0xEE);
            Arrays.fill(block, (byte) 0xFF);
            world.applyLight(
                    "minecraft:overworld",
                    0,
                    0,
                    new ac.cult.cultac.network.packet.LightValues(
                            skyMask,
                            skyMask,
                            new java.util.BitSet(),
                            new java.util.BitSet(),
                            List.of(sky),
                            List.of(block)));
            assertFalse(world.clientIsRainingAt(rainPos)); // Block light cannot make rain pass a roof.
            Arrays.fill(sky, (byte) 0xFF);
            Arrays.fill(block, (byte) 0);
            world.applyLight(
                    "minecraft:overworld",
                    0,
                    0,
                    new ac.cult.cultac.network.packet.LightValues(
                            skyMask,
                            skyMask,
                            new java.util.BitSet(),
                            new java.util.BitSet(),
                            List.of(sky),
                            List.of(block)));
            assertTrue(world.clientIsRainingAt(rainPos));
            world.setDimension("minecraft:overworld", dimension);
            assertTrue(world.clientIsRainingAt(rainPos));
            world.onClientLogin();
            assertFalse(world.clientIsRainingAt(rainPos));
            for (var palette : palettes) Arrays.fill(palette, desert);
            world.applyClientBiomes("minecraft:the_nether", 0, 0, palettes);
            assertEquals("minecraft:forest", view.biomeKeyAt(center));

            var state = new ClientComponentRegistries();
            state.appendTags(new RegistryTags(Map.of(
                    "minecraft:worldgen/biome", new RegistryTags.Payload(Map.of("test:received", List.of(forest))))));
            var first = state.blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, DataTables.defaults());
            assertEquals(java.util.Set.of("minecraft:forest"), first.tags().get("worldgen/biome:test:received"));
            assertEquals(java.util.Set.of(), first.tags().get("worldgen/biome:minecraft:is_overworld"));
            state.appendTags(new RegistryTags(Map.of("minecraft:worldgen/biome", RegistryTags.Payload.EMPTY)));
            var cleared = state.blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, DataTables.defaults());
            assertFalse(cleared.tags().containsKey("worldgen/biome:test:received"));
            assertEquals(java.util.Set.of("minecraft:forest"), first.tags().get("worldgen/biome:test:received"));

            state.appendTags(new RegistryTags(Map.of(
                    "minecraft:worldgen/biome", new RegistryTags.Payload(Map.of("test:received", List.of(forest))))));
            state.beginConfiguration();
            state.appendConfigurationRegistryData(new RegistryData(
                    "minecraft:worldgen/biome", List.of(new WireValueDecoder.RegistryEntry("minecraft:plains", null))));
            state.finishConfiguration(ProtocolVersion.V26_3, ProtocolVersion.V26_3, ProtocolVersion.V26_3);
            var replacement =
                    state.blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, DataTables.defaults());
            assertFalse(replacement.tags().containsKey("worldgen/biome:test:received"));
            assertEquals(java.util.Set.of(), replacement.tags().get("worldgen/biome:minecraft:is_overworld"));

            // Omitting default block tags must also change actual survival behavior.
            var snowPos = new ac.cult.blocksim.engine.BlockPos(8, 65, 8);
            world.updateBlock(
                    new ac.cult.cultac.protocol.value.BlockPos(8, 64, 8),
                    DataTables.defaults().registry().block("minecraft:ice").defaultState());
            var defaults = DataTables.defaults();
            int snow = defaults.registry().block("minecraft:snow").defaultState();
            var original = new ac.cult.blocksim.engine.SimLevel(
                    view, defaults.registry(), new ac.cult.blocksim.behavior.BehaviorRegistry(defaults));
            assertFalse(original.behavior(snow).canSurvive(original, snow, snowPos));
            state.appendTags(new RegistryTags(Map.of("minecraft:block", RegistryTags.Payload.EMPTY)));
            var rebound = state.blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, defaults)
                    .apply(defaults);
            var changed = new ac.cult.blocksim.engine.SimLevel(
                    view, rebound.registry(), new ac.cult.blocksim.behavior.BehaviorRegistry(rebound));
            assertTrue(changed.behavior(snow).canSurvive(changed, snow, snowPos));
            assertFalse(original.behavior(snow).canSurvive(original, snow, snowPos));
        }
    }
}
