package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketListenerPriority;
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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

            // Configuration tags bind at finish_configuration, against the completed contents.
            assertEquals(new RegistryTags(Map.of()), read(fixture, ConnectionPhase.CONFIGURATION, tags));
            var values = fixture.connection.platform().modelValues();
            var staged = values.stage(HOST);
            values.publish(staged);
            var entries = staged.tags().tags().get(registry).entries();
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

    @Test
    void unattachedConnectionForwardsChunksItCannotProject() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11, false)) {
            assertNull(fixture.connection.player());
            var chunkType = fixture.registrar.runtime().typeForKey("clientbound.level_chunk_with_light");
            var observed = new ArrayList<Object>();
            fixture.registrar.register(
                    routes -> routes.sendRoute(chunkType, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                        observed.add(packet);
                    }));
            var chunk = frame(fixture, ConnectionPhase.PLAY, "level_chunk_with_light");
            chunk.writeInt(0).writeInt(0);
            Wire.writeVarInt(chunk, 0); // 1.21.5+ heightmaps are a typed list.
            var payload = Unpooled.buffer();
            payload.writeShort(4096);
            payload.writeByte(0);
            Wire.writeVarInt(payload, 1);
            payload.writeByte(0);
            Wire.writeVarInt(payload, 0);
            Wire.writeVarInt(chunk, payload.readableBytes());
            chunk.writeBytes(payload);
            payload.release();
            Wire.writeVarInt(chunk, 0);
            for (int i = 0; i < 6; i++) Wire.writeVarInt(chunk, 0);
            assertForwarded(fixture, chunk);
            assertEquals(List.of(), observed);
        }
    }

    @Test
    void unattachedConnectionForwardsBiomesAndPaintings() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11, false)) {
            receivePaintingNames(fixture, "minecraft:alban");
            var observed = consume(fixture, "clientbound.chunks_biomes", "clientbound.set_entity_data");
            var biomes = frame(fixture, ConnectionPhase.PLAY, "chunks_biomes");
            Wire.writeVarInt(biomes, 1);
            biomes.writeLong(0);
            Wire.writeVarInt(biomes, 0);
            assertForwarded(fixture, biomes);
            assertForwarded(fixture, paintingReference(fixture, ProtocolVersion.V1_21_11));
            assertEquals(List.of(), observed);
        }
    }

    @Test
    void unattachedModelConnectionForwardsPaintingMetadata() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false)) {
            var observed = consume(fixture, "clientbound.set_entity_data");
            assertForwarded(fixture, paintingReference(fixture, ProtocolVersion.V26_3));
            assertEquals(List.of(), observed);
        }
    }

    @Test
    void unresolvablePaintingIsReportedAndForwarded() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)) {
            receivePaintingNames(fixture, "test:undefined");
            var observed = consume(fixture, "clientbound.set_entity_data");
            var metadata = paintingReference(fixture, ProtocolVersion.V1_21_11);
            var expected = ByteBufUtil.getBytes(metadata);
            try (var failures = new ReportedFailures()) {
                assertTrue(fixture.channel.writeOutbound(metadata));
                assertEquals(1, failures.messages().size(), failures.messages().toString());
            }
            ByteBuf forwarded = fixture.channel.readOutbound();
            try {
                assertArrayEquals(expected, ByteBufUtil.getBytes(forwarded));
            } finally {
                forwarded.release();
            }
            assertTrue(fixture.channel.isActive());
            assertEquals(List.of(), observed);
        }
    }

    /** Data-less painting_variant contents, as a known pack sends them. */
    private static void receivePaintingNames(RecordReceiveFixture fixture, String... names) {
        fixture.phase(ConnectionPhase.CONFIGURATION);
        var registry = frame(fixture, ConnectionPhase.CONFIGURATION, "registry_data");
        identifier(registry, "minecraft:painting_variant");
        Wire.writeVarInt(registry, names.length);
        for (String name : names) {
            identifier(registry, name);
            registry.writeBoolean(false);
        }
        read(fixture, ConnectionPhase.CONFIGURATION, registry);
        fixture.phase(ConnectionPhase.PLAY);
    }

    /** The packets these routes observe; listener failures are reported, not thrown. */
    private static List<Object> consume(RecordReceiveFixture fixture, String... keys) {
        var observed = new ArrayList<Object>();
        fixture.registrar.register(routes -> {
            for (String key : keys)
                routes.sendRoute(
                        fixture.registrar.runtime().typeForKey(key),
                        PacketListenerPriority.NORMAL,
                        (event, player, packet) -> observed.add(packet));
        });
        return observed;
    }

    /** Forwarded unchanged because the value was declined, not because its decoding failed. */
    private static void assertForwarded(RecordReceiveFixture fixture, ByteBuf frame) {
        var expected = ByteBufUtil.getBytes(frame);
        try (var failures = new ReportedFailures()) {
            assertTrue(fixture.channel.writeOutbound(frame));
            failures.assertNone();
        }
        ByteBuf forwarded = fixture.channel.readOutbound();
        try {
            assertArrayEquals(expected, ByteBufUtil.getBytes(forwarded));
        } finally {
            forwarded.release();
        }
        assertTrue(fixture.channel.isActive());
    }

    /** Painting variant metadata naming wire registry entry 0. */
    private static ByteBuf paintingReference(RecordReceiveFixture fixture, ProtocolVersion version) {
        var metadata = frame(fixture, ConnectionPhase.PLAY, "set_entity_data");
        Wire.writeVarInt(metadata, 44);
        metadata.writeByte(8);
        Wire.writeVarInt(metadata, OlderBackendRegistriesTest.paintingSerializer(version));
        Wire.writeVarInt(metadata, 1);
        metadata.writeByte(255);
        return metadata;
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
