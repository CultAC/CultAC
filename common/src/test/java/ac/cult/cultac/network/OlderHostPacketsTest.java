package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.PalettedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A host older than the model, as on Paper 1.21.x and older Velocity clients, decodes into model records. */
class OlderHostPacketsTest {
    private static final ProtocolVersion HOST = ProtocolVersion.V1_21_3;
    private static final String BIOME = "minecraft:worldgen/biome";

    @Test
    void receivedTagsOmitUnknownWireMembersAndRetainEmptyTagsLikeVanilla() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            String registry = "minecraft:game_event";
            var source = ModelRegistryData.load(HOST).registry(registry);
            var model = ModelRegistryNamesState.defaults();
            // Via's game-event tag rewriter preserves IDs from newer backends. ID 60
            // caused a live 1.21.3 Velocity configuration disconnect with Paper 26.3.
            assertTrue(source.size() <= 60);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            var tags = frame(fixture, ConnectionPhase.CONFIGURATION, "update_tags");
            Wire.writeVarInt(tags, 1);
            identifier(tags, registry);
            Wire.writeVarInt(tags, 2);
            identifier(tags, "test:mixed");
            Wire.writeVarInt(tags, 5);
            for (int id : new int[] {0, 60, -1, source.size() - 1, Integer.MAX_VALUE}) Wire.writeVarInt(tags, id);
            identifier(tags, "test:empty");
            Wire.writeVarInt(tags, 1);
            Wire.writeVarInt(tags, source.size());

            var decoded = (RegistryTags) read(fixture, ConnectionPhase.CONFIGURATION, tags);
            var entries = decoded.tags().get(registry).entries();
            assertEquals(
                    List.of(model.id(registry, source.name(0)), model.id(registry, source.name(source.size() - 1))),
                    entries.get("test:mixed"));
            assertEquals(List.of(), entries.get("test:empty"));
            assertThrows(ac.cult.cultac.protocol.MalformedPacketException.class, () -> source.name(60));
        }
    }

    @Test
    void worldInventoryAndEntityPacketsDecodeIntoModelIds() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            var source = ModelRegistryData.load(HOST);
            int obsidian = source.blockStateId("minecraft:obsidian");
            int modelObsidian =
                    DataTables.defaults().registry().block("minecraft:obsidian").defaultState();
            assertNotEquals(obsidian, modelObsidian);

            fixture.phase(ConnectionPhase.CONFIGURATION);
            var registry = frame(fixture, ConnectionPhase.CONFIGURATION, "registry_data");
            identifier(registry, BIOME);
            Wire.writeVarInt(registry, 3);
            for (var name : List.of("minecraft:plains", "minecraft:desert", "minecraft:forest")) {
                identifier(registry, name);
                registry.writeBoolean(false);
            }
            read(fixture, ConnectionPhase.CONFIGURATION, registry);
            // A model-only family without model values reads the older wire with its own codec.
            var features = frame(fixture, ConnectionPhase.CONFIGURATION, "update_enabled_features");
            Wire.writeVarInt(features, 1);
            identifier(features, "minecraft:vanilla");
            assertInstanceOf(
                    WorldPackets.EnabledFeatures.class, read(fixture, ConnectionPhase.CONFIGURATION, features));
            fixture.phase(ConnectionPhase.PLAY);

            var world = fixture.player.compensatedWorld;
            var dimension = fixture.player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            int sections = world.getLastClientboundSectionCount();
            var chunk = frame(fixture, ConnectionPhase.PLAY, "level_chunk_with_light");
            chunk.writeInt(0).writeInt(0);
            chunk.writeByte(10).writeByte(0); // Pre-1.21.5 heightmaps are an NBT compound.
            var payload = Unpooled.buffer();
            for (int section = 0; section < sections; section++) {
                payload.writeShort(section == 0 ? 4096 : 0);
                // 1.21.2-1.21.4 single-value containers still carry an empty length prefix.
                payload.writeByte(0);
                Wire.writeVarInt(payload, section == 0 ? obsidian : 0);
                Wire.writeVarInt(payload, 0);
                payload.writeByte(0);
                Wire.writeVarInt(payload, 2);
                Wire.writeVarInt(payload, 0);
            }
            Wire.writeVarInt(chunk, payload.readableBytes());
            chunk.writeBytes(payload);
            payload.release();
            Wire.writeVarInt(chunk, 0);
            for (int i = 0; i < 6; i++) Wire.writeVarInt(chunk, 0); // Light masks and arrays.
            var decoded = (WorldPackets.Chunk) read(fixture, ConnectionPhase.PLAY, chunk);
            var model = Unpooled.wrappedBuffer(decoded.sections());
            int forest = ModelRegistryNamesState.defaults().id(BIOME, "minecraft:forest");
            int biomes = ModelRegistryNamesState.defaults().size(BIOME);
            model.skipBytes(4);
            assertEquals(modelObsidian, PalettedSection.readBlocks(model).get(4095));
            assertEquals(forest, PalettedSection.readBiomes(model, biomes)[63]);
            model.release();

            int item = source.registry("minecraft:item").id("minecraft:obsidian");
            var content = frame(fixture, ConnectionPhase.PLAY, "container_set_content");
            Wire.writeVarInt(content, 0);
            Wire.writeVarInt(content, 7);
            Wire.writeVarInt(content, 2);
            Wire.writeVarInt(content, 3);
            Wire.writeVarInt(content, item);
            Wire.writeVarInt(content, 0);
            Wire.writeVarInt(content, 0);
            Wire.writeVarInt(content, 0);
            Wire.writeVarInt(content, 0);
            var inventory = (InventoryPackets.Content) read(fixture, ConnectionPhase.PLAY, content);
            assertEquals(7, inventory.stateId());
            assertEquals("minecraft:obsidian", inventory.items().get(0).itemKey());
            assertEquals(3, inventory.items().get(0).count());
            assertTrue(inventory.items().get(1).isEmpty());
            assertTrue(inventory.carriedItem().isEmpty());

            var metadata = frame(fixture, ConnectionPhase.PLAY, "set_entity_data");
            Wire.writeVarInt(metadata, 42);
            metadata.writeByte(0);
            Wire.writeVarInt(metadata, 0);
            metadata.writeByte(2);
            metadata.writeByte(255);
            var entity = (EntityMetadata) read(fixture, ConnectionPhase.PLAY, metadata);
            assertEquals(42, entity.id());
            assertEquals(2, ((Number) entity.packedItems().get(0).value()).intValue());
        }
    }

    private static ByteBuf frame(RecordReceiveFixture fixture, ConnectionPhase phase, String name) {
        var frame = Unpooled.buffer();
        int id = fixture.data.packets(phase, PacketDirection.CLIENTBOUND).id("minecraft:" + name);
        assertTrue(id >= 0, name);
        Wire.writeVarInt(frame, id);
        return frame;
    }

    private static Object read(RecordReceiveFixture fixture, ConnectionPhase phase, ByteBuf frame) {
        try {
            var values = fixture.connection
                    .packets()
                    .read(phase, PacketDirection.CLIENTBOUND, frame, fixture.connection, type -> true);
            assertEquals(1, values.size());
            return values.getFirst().packet();
        } finally {
            frame.release();
        }
    }

    private static void identifier(ByteBuf output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Wire.writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }
}
