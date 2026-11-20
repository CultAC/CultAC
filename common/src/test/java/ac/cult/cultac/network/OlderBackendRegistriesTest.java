package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsF;
import ac.cult.cultac.events.packets.PacketModelRegistryNames;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.codec.WireRegistryState;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import ac.cult.cultac.utils.latency.PalettedSection;
import ac.cult.cultac.utils.math.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** An older backend's datapack biomes and paintings are real client state, carried through every consumer. */
class OlderBackendRegistriesTest {
    private static final ProtocolVersion HOST = ProtocolVersion.V1_21_11;
    private static final String BIOME = ClientWorldRegistries.BIOMES;
    private static final String PAINTING = ClientWorldRegistries.PAINTINGS;
    private static final String COLD = "example:cold_evaporating";
    private static final String WARM = "example:warm";
    /** Enough appended biomes that the model's global biome palette needs another bit. */
    private static final int FILLERS = 70;

    @Test
    void datapackBiomesKeepTheirReceivedClimateAttributesAndTags() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            var received = new ArrayList<Object[]>();
            received.add(new Object[] {"minecraft:plains", null});
            for (int i = 0; i < FILLERS; i++) received.add(new Object[] {"example:filler_" + i, biome(1, 0.8F, false)});
            received.add(new Object[] {COLD, biome(1, -0.5F, true)});
            int coldWire = received.size() - 1;
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, registry(fixture, BIOME, received));
            send(fixture, tags(fixture, BIOME, "example:cold", coldWire));
            finishConfiguration(fixture);

            var names = fixture.connection.require(RegistryNames.class);
            int defaults = ClientWorldRegistries.modelDefaults()
                    .registries()
                    .get(BIOME)
                    .size();
            int cold = names.id(BIOME, COLD);
            assertEquals(defaults + FILLERS, cold);
            assertTrue(32 - Integer.numberOfLeadingZeros(names.size(BIOME) - 1)
                    > 32 - Integer.numberOfLeadingZeros(defaults - 1));
            var registries = fixture.player.getWorldRegistries();
            assertEquals(
                    COLD,
                    registries.snapshot().registries().get(BIOME).get(cold).key());
            assertEquals(List.of(COLD), registries.snapshot().tags().get(BIOME).get("example:cold"));

            var player = fixture.player;
            var world = player.compensatedWorld;
            var dimension = registries.dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.clientBiomeZoomSeed(55);
            world.ensureValidationChunkLoaded(0, 0);
            int sections = world.getLastClientboundSectionCount();
            int plains = names.id(BIOME, "minecraft:plains");

            var chunks = new ArrayList<WorldPackets.Chunk>();
            var updates = new ArrayList<WorldPackets.ChunkBiomes>();
            fixture.registrar.register(routes -> {
                routes.sendRoute(
                        fixture.registrar.runtime().typeForKey("clientbound.level_chunk_with_light"),
                        PacketListenerPriority.NORMAL,
                        (event, ignored, packet) -> chunks.add((WorldPackets.Chunk) packet));
                routes.sendRoute(
                        fixture.registrar.runtime().typeForKey("clientbound.chunks_biomes"),
                        PacketListenerPriority.NORMAL,
                        (event, ignored, packet) -> updates.add((WorldPackets.ChunkBiomes) packet));
            });

            var chunk = frame(fixture, ConnectionPhase.PLAY, "level_chunk_with_light");
            chunk.writeInt(0).writeInt(0);
            Wire.writeVarInt(chunk, 0); // 1.21.5+ heightmaps are a typed list.
            var payload = Unpooled.buffer();
            for (int section = 0; section < sections; section++) {
                payload.writeShort(0);
                payload.writeByte(0);
                Wire.writeVarInt(payload, 0);
                globalBiomes(payload, coldWire, received.size());
            }
            Wire.writeVarInt(chunk, payload.readableBytes());
            chunk.writeBytes(payload);
            payload.release();
            Wire.writeVarInt(chunk, 0);
            for (int i = 0; i < 6; i++) Wire.writeVarInt(chunk, 0);
            send(fixture, chunk);
            assertEquals(1, chunks.size());
            world.applyClientBiomes("minecraft:overworld", 0, 0, chunkBiomes(chunks.getFirst(), sections, names));
            assertColdBiome(player, cold);

            world.applyClientBiomes("minecraft:overworld", 0, 0, uniform(sections, plains));
            assertEquals("minecraft:plains", world.clientBiomeKeyAt(new BlockPos(8, 64, 8)));
            var update = frame(fixture, ConnectionPhase.PLAY, "chunks_biomes");
            Wire.writeVarInt(update, 1);
            update.writeLong(0);
            var biomes = Unpooled.buffer();
            for (int section = 0; section < sections; section++) globalBiomes(biomes, coldWire, received.size());
            Wire.writeVarInt(update, biomes.readableBytes());
            update.writeBytes(biomes);
            biomes.release();
            send(fixture, update);
            assertEquals(1, updates.size());
            var bytes = Unpooled.wrappedBuffer(
                    updates.getFirst().chunks().getFirst().sections());
            int[][] palettes = new int[sections][];
            for (int section = 0; section < sections; section++)
                palettes[section] = PalettedSection.readBiomes(bytes, names.size(BIOME));
            bytes.release();
            world.applyClientBiomes("minecraft:overworld", 0, 0, palettes);
            assertColdBiome(player, cold);
        }
    }

    @Test
    void datapackPaintingsSurviveTagsOnlyReconfiguration() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(
                    fixture,
                    registry(
                            fixture,
                            PAINTING,
                            List.of(
                                    new Object[] {"example:mural", painting(1, 1)},
                                    new Object[] {"example:wide", painting(3, 2)},
                                    new Object[] {"minecraft:alban", null},
                                    new Object[] {"minecraft:aztec", painting(2, 2)})));
            send(fixture, tags(fixture, PAINTING, "minecraft:placeable", 0));
            finishConfiguration(fixture);

            var registries = fixture.player.getWorldRegistries();
            assertEquals(new PaintingSize(1, 1), registries.initialPainting());
            assertEquals(new PaintingSize(3, 2), registries.painting("example:wide"));
            assertEquals(new PaintingSize(2, 2), registries.painting("minecraft:aztec"));
            assertEquals(List.of(new PaintingSize(1, 1)), registries.placeablePaintings());

            send(fixture, frame(fixture, ConnectionPhase.PLAY, "start_configuration"));
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, tags(fixture, PAINTING, "minecraft:placeable", 0));
            finishConfiguration(fixture);
            registries = fixture.player.getWorldRegistries();
            assertEquals(new PaintingSize(3, 2), registries.painting("example:wide"));
            assertEquals(new PaintingSize(2, 2), registries.painting("minecraft:aztec"));
            assertEquals(List.of(new PaintingSize(1, 1)), registries.placeablePaintings());

            var decoded = new ArrayList<EntityMetadata>();
            fixture.registrar.register(routes -> routes.sendRoute(
                    fixture.registrar.runtime().typeForKey("clientbound.set_entity_data"),
                    PacketListenerPriority.NORMAL,
                    (event, ignored, packet) -> decoded.add((EntityMetadata) packet)));
            var metadata = frame(fixture, ConnectionPhase.PLAY, "set_entity_data");
            Wire.writeVarInt(metadata, 44);
            metadata.writeByte(8);
            Wire.writeVarInt(metadata, paintingSerializer(HOST));
            Wire.writeVarInt(metadata, 2); // Wire entry 1, example:wide.
            metadata.writeByte(255);
            send(fixture, metadata);
            assertEquals(
                    new PaintingSize(3, 2),
                    decoded.getFirst().packedItems().getFirst().value());

            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -2;
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            var wall = new BlockPos(1, 64, 1);
            world.updateBlock(
                    wall,
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            hang(player, wall);
            assertTrue(player.getInventory().getHeldItem().isEmpty(), "A placeable 1x1 mural fits one wall block");

            send(fixture, frame(fixture, ConnectionPhase.PLAY, "start_configuration"));
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, tags(fixture, PAINTING, "minecraft:placeable", 1));
            finishConfiguration(fixture);
            assertEquals(
                    List.of(new PaintingSize(3, 2)),
                    fixture.player.getWorldRegistries().placeablePaintings());
            hang(player, wall);
            assertEquals(1, player.getInventory().getHeldItem().getCount(), "Only the 3x2 variant is placeable");
        }
    }

    @Test
    void withdrawnConnectionStartsEachConfigurationAfresh() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            // An exempt or fail-open player is withdrawn but keeps its transport, which still decodes registries.
            assertTrue(CultAPI.INSTANCE.getPlayerDataManager().remove(fixture.user));
            var received = (WireRegistryState) fixture.connection
                    .platform()
                    .modelValues()
                    .packetValues(HOST, fixture.connection)
                    .sourceRegistries();
            for (int configuration = 0; configuration < 3; configuration++) {
                reconfigure(fixture);
                send(fixture, registry(fixture, PAINTING, murals()));
                finishConfiguration(fixture);
                assertEquals(murals().size(), received.size(PAINTING));
            }
        }
    }

    @Test
    void loginDiscardsThePreviousLevelAcrossARegistryReplacement() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(
                    fixture,
                    registry(
                            fixture,
                            BIOME,
                            List.of(
                                    new Object[] {"minecraft:plains", null},
                                    new Object[] {"example:warm", biome(1, 0.8F, false)},
                                    new Object[] {COLD, biome(1, -0.5F, true)})));
            finishConfiguration(fixture);
            var player = fixture.player;
            var world = player.compensatedWorld;
            var overworld = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", overworld.dimension());
            world.setDimension("minecraft:overworld", overworld);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(1, 0);
            var names = fixture.connection.require(RegistryNames.class);
            int previousCold = names.id(BIOME, COLD);
            world.applyClientBiomes(
                    "minecraft:overworld", 1, 0, uniform(world.getLastClientboundSectionCount(), previousCold));
            var neighbour = new BlockPos(24, 64, 8);
            assertEquals(COLD, world.clientBiomeKeyAt(neighbour));

            reconfigure(fixture);
            send(fixture, coldBiomes(fixture));
            finishConfiguration(fixture);
            names = fixture.connection.require(RegistryNames.class);
            assertTrue(previousCold >= names.size(BIOME), "The previous generation's ID is out of range");

            login(
                    player,
                    names.id(ClientWorldRegistries.DIMENSIONS, "minecraft:overworld"),
                    "minecraft:overworld",
                    GameMode.SURVIVAL);
            assertNull(world.getChunk(1, 0), "The fresh client level has no previous columns");
            assertNull(world.getChunk(0, 0));
            assertEquals("minecraft:plains", world.clientBiomeKeyAt(neighbour));
        }
    }

    @Test
    void oldLevelTasksDoNothingAfterLogin() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.registrar.register(routes ->
                    routes.registerSendListener(PacketListenerPriority.HIGH, new PacketWorldReaderTwentySix()));
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, coldBiomes(fixture));
            finishConfiguration(fixture);
            var player = fixture.player;
            var world = player.compensatedWorld;
            var overworld = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", overworld.dimension());
            world.setDimension("minecraft:overworld", overworld);
            int sections = world.getLastClientboundSectionCount();
            send(fixture, column(fixture, 0, sections, 1));
            send(fixture, column(fixture, 2, sections, 1));
            player.latencyUtils.handleNettySyncTransaction(player.lastTransactionSent.get());
            assertEquals(COLD, world.clientBiomeKeyAt(new BlockPos(8, 64, 8)));
            assertNotNull(world.getChunk(2, 0));

            // The client has not answered the proofs that carry these.
            int pending = player.lastTransactionSent.get() + 1;
            player.lastTransactionSent.set(pending);
            send(fixture, column(fixture, 1, sections, 1));
            send(fixture, biomeUpdate(fixture, sections, 1));
            var forget = frame(fixture, ConnectionPhase.PLAY, "forget_level_chunk");
            forget.writeLong(2);
            send(fixture, forget);
            send(fixture, stoneAt(fixture, 8, 64, 8));
            player.packetEntityReplication.addEntity(7, EntityTypeIds.SHULKER, new Vec3(8, 64, 8), 0, 0, 0, pending);
            assertNull(world.getChunk(1, 0));
            assertNull(player.compensatedEntities.getEntity(7));

            reconfigure(fixture);
            send(fixture, registry(fixture, BIOME, List.<Object[]>of(new Object[] {"minecraft:plains", null})));
            finishConfiguration(fixture);
            var names = fixture.connection.require(RegistryNames.class);
            int receipt = sendLogin(
                    player,
                    names.id(ClientWorldRegistries.DIMENSIONS, "minecraft:overworld"),
                    "minecraft:overworld",
                    GameMode.SURVIVAL);
            // One pong runs the old tasks, then the LOGIN receipt reset, then the new backend's columns.
            int fresh = pending + 1;
            assertEquals(fresh, receipt);
            player.lastTransactionSent.set(fresh);
            send(fixture, column(fixture, 0, sections, 0));
            send(fixture, column(fixture, 2, sections, 0));
            player.lastTransactionReceived.set(fresh);
            player.latencyUtils.handleNettySyncTransaction(fresh);

            assertNull(world.getChunk(1, 0), "An old level's column stays out of the new level");
            assertNull(player.compensatedEntities.getEntity(7), "An old level's spawn stays out of the new level");
            assertFalse(
                    world.hasRecentClientCollisionChanges(new SimpleCollisionBox(7, 63, 7, 10, 66, 10)),
                    "An old level's block update leaves no collision change in the new level");
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:air").defaultState(),
                    world.getBlockStateIdAt(new BlockPos(8, 64, 8)));
            assertEquals("minecraft:plains", world.clientBiomeKeyAt(new BlockPos(8, 64, 8)));
            assertNotNull(world.getChunk(2, 0));
        }
    }

    @Test
    void tagsOnlyConfigurationReplacesSuppliedTagSets() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, registry(fixture, PAINTING, murals()));
            send(fixture, coldBiomes(fixture));
            send(
                    fixture,
                    tags(
                            fixture,
                            new TagSet(PAINTING, "minecraft:placeable", 1),
                            new TagSet(BIOME, "example:cold", 1)));
            finishConfiguration(fixture);
            var player = fixture.player;
            var wall = wall(player);
            assertEquals(
                    List.of(new PaintingSize(3, 2)), player.getWorldRegistries().placeablePaintings());

            reconfigure(fixture);
            send(fixture, tags(fixture, new TagSet("minecraft:fluid", "example:unrelated")));
            finishConfiguration(fixture);
            var registries = player.getWorldRegistries();
            assertEquals(List.of(new PaintingSize(3, 2)), registries.placeablePaintings());
            assertEquals(List.of(COLD), registries.snapshot().tags().get(BIOME).get("example:cold"));
            hang(player, wall);
            assertEquals(1, player.getInventory().getHeldItem().getCount(), "Only the retained 3x2 is placeable");

            reconfigure(fixture);
            finishConfiguration(fixture);
            assertEquals(
                    List.of(new PaintingSize(3, 2)), player.getWorldRegistries().placeablePaintings());

            reconfigure(fixture);
            send(fixture, tags(fixture, new TagSet(PAINTING, null)));
            finishConfiguration(fixture);
            registries = player.getWorldRegistries();
            assertEquals(List.of(), registries.placeablePaintings());
            assertEquals(List.of(COLD), registries.snapshot().tags().get(BIOME).get("example:cold"));
            hang(player, wall);
            assertEquals(1, player.getInventory().getHeldItem().getCount(), "A cleared tag places nothing");

            reconfigure(fixture);
            send(fixture, tags(fixture, new TagSet(PAINTING, "minecraft:placeable", 0)));
            finishConfiguration(fixture);
            assertEquals(
                    List.of(new PaintingSize(1, 1)), player.getWorldRegistries().placeablePaintings());
            hang(player, wall);
            assertTrue(player.getInventory().getHeldItem().isEmpty(), "The 1x1 mural fits one wall block");
        }
    }

    @Test
    void freshPaintingContentsStartUntagged() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, registry(fixture, PAINTING, murals()));
            send(fixture, tags(fixture, new TagSet("minecraft:fluid", "example:unrelated")));
            finishConfiguration(fixture);
            var player = fixture.player;
            var wall = wall(player);
            assertEquals(List.of(), player.getWorldRegistries().placeablePaintings());
            assertEquals(RegistryTags.Payload.EMPTY, player.registryState.tagPayload(PAINTING));
            hang(player, wall);
            assertEquals(1, player.getInventory().getHeldItem().getCount(), "Omitted painting tags place nothing");

            reconfigure(fixture);
            send(fixture, registry(fixture, PAINTING, murals()));
            send(fixture, tags(fixture, new TagSet(PAINTING, null)));
            finishConfiguration(fixture);
            assertEquals(List.of(), player.getWorldRegistries().placeablePaintings());
            hang(player, wall);
            assertEquals(1, player.getInventory().getHeldItem().getCount(), "Whole-empty painting tags place nothing");

            reconfigure(fixture);
            send(fixture, registry(fixture, PAINTING, murals()));
            send(fixture, tags(fixture, new TagSet(PAINTING, "minecraft:placeable", 0)));
            finishConfiguration(fixture);
            assertEquals(
                    List.of(new PaintingSize(1, 1)), player.getWorldRegistries().placeablePaintings());
            hang(player, wall);
            assertTrue(player.getInventory().getHeldItem().isEmpty(), "The supplied 1x1 mural fits one wall block");
        }
    }

    @Test
    void configurationTagsBindAgainstTheCompletedContents() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, registry(fixture, PAINTING, List.<Object[]>of(new Object[] {"minecraft:alban", null})));
            send(fixture, registry(fixture, BIOME, List.<Object[]>of(new Object[] {"minecraft:plains", null})));
            finishConfiguration(fixture);

            reconfigure(fixture);
            send(
                    fixture,
                    tags(
                            fixture,
                            new TagSet(PAINTING, "minecraft:placeable", 0),
                            new TagSet(BIOME, "example:cold", 0)));
            send(
                    fixture,
                    registry(fixture, PAINTING, List.of(new Object[] {"example:wide", painting(3, 2)}, new Object[] {
                        "minecraft:alban", null
                    })));
            send(fixture, tags(fixture, new TagSet(PAINTING, "minecraft:placeable", 0, 2)));
            send(fixture, registry(fixture, PAINTING, List.<Object[]>of(new Object[] {"example:mural", painting(1, 1)
            })));
            send(fixture, registry(fixture, BIOME, List.of(new Object[] {COLD, biome(1, -0.5F, true)}, new Object[] {
                "minecraft:plains", null
            })));
            finishConfiguration(fixture);

            var registries = fixture.player.getWorldRegistries();
            assertEquals(List.of(new PaintingSize(3, 2), new PaintingSize(1, 1)), registries.placeablePaintings());
            assertEquals(List.of(COLD), registries.snapshot().tags().get(BIOME).get("example:cold"));
        }
    }

    @Test
    void ignoredEmptyTagsPublishTheReceivedGeneration() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, coldBiomes(fixture));
            finishConfiguration(fixture);

            reconfigure(fixture);
            send(
                    fixture,
                    registry(
                            fixture,
                            BIOME,
                            List.of(
                                    new Object[] {"minecraft:plains", null},
                                    new Object[] {WARM, biome(1, 0.8F, false)},
                                    new Object[] {COLD, biome(1, -0.5F, true)})));
            // 1.21.11 has no timeline registry; the client ignores this payload before looking one up.
            send(
                    fixture,
                    tags(
                            fixture,
                            new TagSet(ClientWorldRegistries.TIMELINES, null),
                            new TagSet(BIOME, "example:cold", 2)));
            finishConfiguration(fixture);

            var names = fixture.connection.require(RegistryNames.class);
            int defaults = ClientWorldRegistries.modelDefaults()
                    .registries()
                    .get(BIOME)
                    .size();
            assertEquals(defaults, names.id(BIOME, WARM));
            assertEquals(defaults + 1, names.id(BIOME, COLD));
            var biomes = fixture.player.getWorldRegistries().snapshot();
            assertEquals(WARM, biomes.registries().get(BIOME).get(defaults).key());
            assertEquals(COLD, biomes.registries().get(BIOME).get(defaults + 1).key());
            assertEquals(List.of(COLD), biomes.tags().get(BIOME).get("example:cold"));
        }
    }

    @Test
    void failedGenerationPublishesModelDefaults() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            listen(fixture);
            fixture.phase(ConnectionPhase.CONFIGURATION);
            send(fixture, coldBiomes(fixture));
            send(fixture, tags(fixture, BIOME, "example:cold", 1));
            finishConfiguration(fixture);
            int defaults = ClientWorldRegistries.modelDefaults()
                    .registries()
                    .get(BIOME)
                    .size();
            assertEquals(
                    defaults, fixture.connection.require(RegistryNames.class).id(BIOME, COLD));

            reconfigure(fixture);
            send(
                    fixture,
                    registry(
                            fixture,
                            BIOME,
                            List.of(
                                    new Object[] {"minecraft:plains", null},
                                    new Object[] {WARM, biome(1, 0.8F, false)},
                                    new Object[] {COLD, biome(1, -0.5F, true)})));
            send(fixture, tags(fixture, BIOME, "example:cold", 2));
            // Members of a registry the 1.21.11 source cannot name: binding fails after the contents arrived.
            send(fixture, tags(fixture, ClientWorldRegistries.TIMELINES, "example:any", 0));
            var failures =
                    sendReporting(fixture, frame(fixture, ConnectionPhase.CONFIGURATION, "finish_configuration"));
            fixture.phase(ConnectionPhase.PLAY);
            assertEquals(1, failures.size(), failures.toString());

            var names = fixture.connection.require(RegistryNames.class);
            assertEquals(defaults, names.size(BIOME));
            assertTrue(names.id(BIOME, COLD) < 0);
            var player = fixture.player;
            var registries = player.getWorldRegistries();
            assertEquals(
                    ClientWorldRegistries.modelDefaults().registries().get(BIOME).stream()
                            .map(ClientWorldRegistries.Definition::key)
                            .toList(),
                    registries.snapshot().registries().get(BIOME).stream()
                            .map(ClientWorldRegistries.Definition::key)
                            .toList());
            assertNull(player.registryState.tagPayload(BIOME));

            var updates = new ArrayList<Object>();
            fixture.registrar.register(routes -> routes.sendRoute(
                    fixture.registrar.runtime().typeForKey("clientbound.chunks_biomes"),
                    PacketListenerPriority.NORMAL,
                    (event, ignored, packet) -> updates.add(packet)));
            var overworld = registries.dimension("minecraft:overworld");
            player.compensatedWorld.setLastClientboundDimension("minecraft:overworld", overworld.dimension());
            int sections = player.compensatedWorld.getLastClientboundSectionCount();
            failures = sendReporting(fixture, biomeUpdate(fixture, sections, 2));
            assertEquals(1, failures.size(), "The replaced entry resolves to nothing, not a stale biome");
            assertEquals(List.of(), updates);
        }
    }

    @Test
    void failedGenerationBindsDimensionTypesByName() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            var player = failedGenerationWithACustomDimension(fixture);
            var registries = player.getWorldRegistries();
            assertEquals(
                    "minecraft:overworld", registries.dimension(1).dimension().key());
            assertEquals(
                    "minecraft:the_nether", registries.dimension(2).dimension().key());
            assertThrows(IllegalArgumentException.class, () -> registries.dimension(0));

            fixture.registrar.register(routes ->
                    routes.registerSendListener(PacketListenerPriority.HIGH, new PacketWorldReaderTwentySix()));
            player.gamemode = GameMode.CREATIVE;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            player.compensatedEntities.addEntity(7, EntityTypeIds.SHULKER, new Vec3(1, 64, 1), 0, 0, 0);
            var flagging = player.checkManager.getListener(BadPacketsF.class);
            // Alerts are suppressed per backend across the JVM; a fresh name isolates this test's occurrence.
            Mockito.when(fixture.connection.platform().backendName()).thenReturn("test-" + System.nanoTime());
            try (var reports = new ReportedFailures()) {
                login(player, 0, "example:arena", GameMode.SURVIVAL);
                login(player, 0, "example:arena", GameMode.SURVIVAL);
                reports.assertNone();
                assertEquals(1, reports.warnings().size(), "Once per backend and type: " + reports.warnings());
            }
            assertEquals(GameMode.SURVIVAL, player.gamemode);
            assertNull(player.compensatedWorld.getChunk(0, 0), "The level resets though its type is unknown");
            assertNull(player.compensatedEntities.getEntity(7));
            assertTrue(player.compensatedWorld.isLevelUnresolved());
            assertTrue(player.isDisabled(), "Checks pause while the level is unresolved");
            assertFalse(flagging.flag("unresolved level"));

            // The backend's columns for that level, framed with the previous level's section count.
            send(fixture, column(fixture, 0, player.compensatedWorld.getLastClientboundSectionCount(), 0));
            player.lastTransactionReceived.set(player.lastTransactionSent.get());
            player.latencyUtils.handleNettySyncTransaction(player.lastTransactionSent.get());
            assertNull(player.compensatedWorld.getChunk(0, 0), "No column is installed with another level's geometry");

            // Respawning into the lobby world changes worlds from the recorded unresolved one.
            int applied = respawn(player, 1, "minecraft:overworld", GameMode.SURVIVAL);
            assertFalse(player.compensatedWorld.isClientboundUnresolved());
            assertTrue(player.isDisabled(), "Checks stay paused until the client applies the respawn");
            acknowledge(player, applied);
            assertEquals(40, player.compensatedWorld.clientSeaLevel(), "A new world takes the respawn's sea level");
            assertFalse(player.compensatedWorld.isLevelUnresolved());
            assertFalse(player.isDisabled(), "Checks resume once the level resolves");
            assertEquals(
                    registries.dimension(1).dimension().height() / 16,
                    player.compensatedWorld.getLastClientboundSectionCount());
        }
    }

    @Test
    void unresolvedRespawnBeforeLoginLeavesLevelChecked() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            var player = failedGenerationWithACustomDimension(fixture);
            login(player, 1, "minecraft:overworld", GameMode.SURVIVAL);
            int applied = respawn(player, 0, "example:arena", GameMode.CREATIVE);
            login(player, 1, "example:lobby", GameMode.SURVIVAL);
            acknowledge(player, applied);

            var world = player.compensatedWorld;
            assertFalse(world.isClientboundUnresolved());
            assertFalse(world.isLevelUnresolved());
            assertEquals("example:lobby", world.getVisibleDimension());
            assertEquals("example:lobby", world.getLastClientboundDimension().dimension());
            assertEquals(GameMode.SURVIVAL, player.gamemode, "The old respawn changes nothing");
            assertNotEquals(40, world.clientSeaLevel());
            assertFalse(player.isDisabled());
        }
    }

    @Test
    void resolvedRespawnBeforeLoginLeavesLevelPaused() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            var player = failedGenerationWithACustomDimension(fixture);
            login(player, 1, "minecraft:overworld", GameMode.SURVIVAL);
            int applied = respawn(player, 1, "example:arena", GameMode.CREATIVE);
            login(player, 0, "example:void", GameMode.SURVIVAL);
            acknowledge(player, applied);

            var world = player.compensatedWorld;
            assertTrue(world.isClientboundUnresolved());
            assertTrue(world.isLevelUnresolved());
            assertEquals("example:void", world.getVisibleDimension());
            assertEquals("example:void", world.getLastClientboundDimension().dimension());
            assertEquals(GameMode.SURVIVAL, player.gamemode, "The old respawn changes nothing");
            assertNotEquals(40, world.clientSeaLevel());
            assertTrue(player.isDisabled());
            assertFalse(player.checkManager.getListener(BadPacketsF.class).flag("unresolved level"));
        }
    }

    /** A failed generation whose dimension types are [example:void (unreadable), overworld, the_nether]. */
    private static CultPlayer failedGenerationWithACustomDimension(RecordReceiveFixture fixture) {
        listen(fixture);
        fixture.phase(ConnectionPhase.CONFIGURATION);
        send(
                fixture,
                registry(
                        fixture,
                        ClientWorldRegistries.DIMENSIONS,
                        List.of(
                                new Object[] {
                                    "example:void",
                                    new NbtValue.Compound(
                                            Map.of("attributes", new NbtValue.Numeric(NbtValue.Kind.INT, 1)))
                                },
                                new Object[] {"minecraft:overworld", null},
                                new Object[] {"minecraft:the_nether", null})));
        send(fixture, registry(fixture, BIOME, List.<Object[]>of(new Object[] {"minecraft:plains", null})));
        var failures = sendReporting(fixture, frame(fixture, ConnectionPhase.CONFIGURATION, "finish_configuration"));
        fixture.phase(ConnectionPhase.PLAY);
        assertEquals(1, failures.size(), failures.toString());
        return fixture.player;
    }

    private static ByteBuf coldBiomes(RecordReceiveFixture fixture) {
        return registry(fixture, BIOME, List.of(new Object[] {"minecraft:plains", null}, new Object[] {
            COLD, biome(1, -0.5F, true)
        }));
    }

    /** Sends a respawn whose state applies at the returned, still unanswered, transaction. */
    private static int respawn(CultPlayer player, int dimensionType, String world, GameMode mode) {
        int applied = player.lastTransactionSent.get() + 1;
        var respawn = new ClientboundRespawn(new PlayerSpawnInfo(dimensionType, world, mode, 40, 0), (byte) 0);
        new PacketPlayerRespawn()
                .onRespawn(
                        new PacketSendEvent<>(
                                player.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, respawn, false),
                        player,
                        respawn);
        player.lastTransactionSent.set(applied);
        return applied;
    }

    /** A late pong answering everything up to {@code transaction}. */
    private static void acknowledge(CultPlayer player, int transaction) {
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    @Test
    void aSecondFailedGenerationKeepsDimensionTypesBoundByName() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(HOST)) {
            failedGenerationWithACustomDimension(fixture);
            reconfigure(fixture);
            // A tags-only configuration whose tags cannot be bound fails again, with no dimension contents.
            send(fixture, tags(fixture, ClientWorldRegistries.TIMELINES, "example:any", 0));
            var failures =
                    sendReporting(fixture, frame(fixture, ConnectionPhase.CONFIGURATION, "finish_configuration"));
            fixture.phase(ConnectionPhase.PLAY);
            assertEquals(1, failures.size(), failures.toString());

            var registries = fixture.player.getWorldRegistries();
            assertThrows(IllegalArgumentException.class, () -> registries.dimension(0));
            assertEquals(
                    "minecraft:overworld", registries.dimension(1).dimension().key());
            assertEquals(
                    "minecraft:the_nether", registries.dimension(2).dimension().key());
            var names = fixture.connection.require(RegistryNames.class);
            assertEquals(0, names.id(ClientWorldRegistries.DIMENSIONS, "example:void"));
            assertEquals(1, names.id(ClientWorldRegistries.DIMENSIONS, "minecraft:overworld"));
        }
    }

    private static void login(CultPlayer player, int dimensionType, String world, GameMode mode) {
        int receipt = sendLogin(player, dimensionType, world, mode);
        // The ping after LOGIN proves the client replaced its level.
        player.lastTransactionReceived.set(receipt);
        player.latencyUtils.handleNettySyncTransaction(receipt);
    }

    /** Sends a LOGIN and returns its receipt ping, unacknowledged. */
    private static int sendLogin(CultPlayer player, int dimensionType, String world, GameMode mode) {
        var login = new ClientboundLogin(1, true, new PlayerSpawnInfo(dimensionType, world, mode));
        var event = new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, login, false);
        int receipt = player.lastTransactionSent.get() + 1;
        new PacketPlayerRespawn().onLogin(event, player, login);
        event.getTasksAfterSend().forEach(Runnable::run);
        return receipt;
    }

    private static void assertColdBiome(CultPlayer player, int cold) {
        var world = player.compensatedWorld;
        var pos = new BlockPos(8, 64, 8);
        assertEquals(COLD, world.clientBiomeKeyAt(pos));
        var view = new BlockSimulatorWorldView(
                world,
                player.checkManager.getListener(PacketWorldBorder.class),
                ModelBlockStates.project(HOST, HOST, ProtocolVersion.V26_3));
        var center = new ac.cult.blocksim.engine.BlockPos(8, 64, 8);
        assertEquals(COLD, view.biomeKeyAt(center));
        assertFalse(player.getWorldRegistries().canRain(COLD, center, 63));
        world.applyClientWeather(GameEventType.RAIN_LEVEL_CHANGE, 1.0F);
        assertFalse(world.clientIsRainingAt(pos));
        assertTrue(world.clientEnvironmentAt(pos).waterEvaporates());
        var tags = player.registryState
                .blockSimulatorTags(ProtocolVersion.V26_3, ProtocolVersion.V26_3, DataTables.defaults())
                .apply(DataTables.defaults());
        assertTrue(HolderSets.contains(tags, "worldgen/biome", new JsonPrimitive("#example:cold"), COLD));
        var direct = new JsonArray();
        direct.add(COLD);
        assertTrue(HolderSets.contains(tags, "worldgen/biome", direct, view.biomeKeyAt(center)));
        assertFalse(
                HolderSets.contains(tags, "worldgen/biome", new JsonPrimitive("#example:cold"), "minecraft:plains"));
    }

    private static void hang(CultPlayer player, BlockPos wall) {
        var stack = OfflineCultTestBootstrap.item("minecraft:painting");
        player.getInventory().inventory.setHeldItem(stack);
        var place = new BlockPlace(player, Hand.MAIN_HAND, wall, Direction.NORTH, stack, null);
        place.setCursor(new Vec3(.5, .5, 0));
        ClientBlockActions.useOn(player, place);
    }

    /** A 1.21.11 client on the 1.21.11 backend, with the production configuration listeners. */
    private static void listen(RecordReceiveFixture fixture) {
        Mockito.when(fixture.connection.platform().getClientProtocol()).thenReturn(HOST);
        fixture.registrar.register(routes -> {
            routes.registerSendListener(PacketListenerPriority.NORMAL, new PacketServerRegistries());
            routes.registerSendListener(PacketListenerPriority.LOWEST, new PacketModelRegistryNames());
        });
    }

    private static void finishConfiguration(RecordReceiveFixture fixture) {
        send(fixture, frame(fixture, ConnectionPhase.CONFIGURATION, "finish_configuration"));
        fixture.phase(ConnectionPhase.PLAY);
    }

    /** Writes through the encoder; the frame must leave, unreported, and the connection must stay open. */
    private static void send(RecordReceiveFixture fixture, ByteBuf frame) {
        var expected = ByteBufUtil.getBytes(frame);
        try (var failures = new ReportedFailures()) {
            assertTrue(fixture.channel.writeOutbound(frame));
            failures.assertNone();
        }
        boolean forwarded = false;
        for (Object out; (out = fixture.channel.readOutbound()) != null; ) {
            var bytes = (ByteBuf) out;
            forwarded |= Arrays.equals(expected, ByteBufUtil.getBytes(bytes));
            bytes.release();
        }
        assertTrue(forwarded);
        assertTrue(fixture.channel.isActive());
    }

    /** Writes through the encoder; the frame must leave and the connection stay open. Returns the reports. */
    private static List<String> sendReporting(RecordReceiveFixture fixture, ByteBuf frame) {
        var expected = ByteBufUtil.getBytes(frame);
        List<String> reported;
        try (var failures = new ReportedFailures()) {
            assertTrue(fixture.channel.writeOutbound(frame));
            reported = failures.messages();
        }
        ByteBuf forwarded = fixture.channel.readOutbound();
        try {
            assertArrayEquals(expected, ByteBufUtil.getBytes(forwarded));
        } finally {
            forwarded.release();
        }
        assertTrue(fixture.channel.isActive());
        return reported;
    }

    /** A ground-up air column at x,0 whose sections all hold the single biome {@code wireId}. */
    private static ByteBuf column(RecordReceiveFixture fixture, int x, int sections, int wireId) {
        var chunk = frame(fixture, ConnectionPhase.PLAY, "level_chunk_with_light");
        chunk.writeInt(x).writeInt(0);
        Wire.writeVarInt(chunk, 0); // 1.21.5+ heightmaps are a typed list.
        var payload = Unpooled.buffer();
        for (int section = 0; section < sections; section++) {
            payload.writeShort(0);
            payload.writeByte(0);
            Wire.writeVarInt(payload, 0);
            payload.writeByte(0);
            Wire.writeVarInt(payload, wireId);
        }
        Wire.writeVarInt(chunk, payload.readableBytes());
        chunk.writeBytes(payload);
        payload.release();
        Wire.writeVarInt(chunk, 0);
        for (int i = 0; i < 6; i++) Wire.writeVarInt(chunk, 0);
        return chunk;
    }

    /** section_blocks_update setting one block to 1.21.11 state 1, stone. */
    private static ByteBuf stoneAt(RecordReceiveFixture fixture, int x, int y, int z) {
        var update = frame(fixture, ConnectionPhase.PLAY, "section_blocks_update");
        update.writeLong(((long) (x >> 4 & 0x3FFFFF) << 42) | ((long) (z >> 4 & 0x3FFFFF) << 20) | (y >> 4 & 0xFFFFF));
        Wire.writeVarInt(update, 1);
        Wire.writeVarLong(update, (1L << 12) | ((x & 15) << 8) | ((z & 15) << 4) | (y & 15));
        return update;
    }

    /** chunks_biomes for column 0,0, every section a single-valued container of {@code wireId}. */
    private static ByteBuf biomeUpdate(RecordReceiveFixture fixture, int sections, int wireId) {
        var update = frame(fixture, ConnectionPhase.PLAY, "chunks_biomes");
        Wire.writeVarInt(update, 1);
        update.writeLong(0);
        var biomes = Unpooled.buffer();
        for (int section = 0; section < sections; section++) {
            biomes.writeByte(0);
            Wire.writeVarInt(biomes, wireId);
        }
        Wire.writeVarInt(update, biomes.readableBytes());
        update.writeBytes(biomes);
        biomes.release();
        return update;
    }

    private static ByteBuf registry(RecordReceiveFixture fixture, String registry, List<Object[]> entries) {
        var frame = frame(fixture, ConnectionPhase.CONFIGURATION, "registry_data");
        identifier(frame, registry);
        Wire.writeVarInt(frame, entries.size());
        for (var entry : entries) {
            identifier(frame, (String) entry[0]);
            frame.writeBoolean(entry[1] != null);
            if (entry[1] != null) frame.writeBytes(BinaryNbt.write((NbtValue) entry[1]));
        }
        return frame;
    }

    private record TagSet(String registry, String tag, int... members) {}

    /** One update_tags frame; a null tag sends that registry as a whole-empty payload. */
    private static ByteBuf tags(RecordReceiveFixture fixture, TagSet... sets) {
        var frame = frame(fixture, fixture.connection.phase(PacketDirection.CLIENTBOUND), "update_tags");
        var registries = new LinkedHashMap<String, List<TagSet>>();
        for (var set : sets)
            registries
                    .computeIfAbsent(set.registry(), ignored -> new ArrayList<>())
                    .add(set);
        Wire.writeVarInt(frame, registries.size());
        registries.forEach((registry, entries) -> {
            identifier(frame, registry);
            var named = entries.stream().filter(set -> set.tag() != null).toList();
            Wire.writeVarInt(frame, named.size());
            for (var set : named) {
                identifier(frame, set.tag());
                Wire.writeVarInt(frame, set.members().length);
                for (int member : set.members()) Wire.writeVarInt(frame, member);
            }
        });
        return frame;
    }

    private static List<Object[]> murals() {
        return List.of(
                new Object[] {"example:mural", painting(1, 1)},
                new Object[] {"example:wide", painting(3, 2)},
                new Object[] {"minecraft:alban", null});
    }

    private static void reconfigure(RecordReceiveFixture fixture) {
        send(fixture, frame(fixture, ConnectionPhase.PLAY, "start_configuration"));
        fixture.phase(ConnectionPhase.CONFIGURATION);
    }

    /** A survival player facing one stone block, with room for a painting on its north face. */
    private static BlockPos wall(CultPlayer player) {
        player.gamemode = GameMode.SURVIVAL;
        player.x = .5;
        player.y = 64;
        player.z = -2;
        player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
        var wall = new BlockPos(1, 64, 1);
        player.compensatedWorld.updateBlock(
                wall, DataTables.defaults().registry().block("minecraft:stone").defaultState());
        return wall;
    }

    private static ByteBuf tags(RecordReceiveFixture fixture, String registry, String tag, int... members) {
        var frame = frame(fixture, fixture.connection.phase(PacketDirection.CLIENTBOUND), "update_tags");
        Wire.writeVarInt(frame, 1);
        identifier(frame, registry);
        Wire.writeVarInt(frame, 1);
        identifier(frame, tag);
        Wire.writeVarInt(frame, members.length);
        for (int member : members) Wire.writeVarInt(frame, member);
        return frame;
    }

    private static NbtValue biome(int precipitation, float temperature, boolean evaporates) {
        var attributes = evaporates
                ? Map.<String, NbtValue>of(
                        "minecraft:gameplay/water_evaporates", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 1))
                : Map.<String, NbtValue>of();
        return new NbtValue.Compound(Map.of(
                "has_precipitation", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) precipitation),
                "temperature", new NbtValue.Numeric(NbtValue.Kind.FLOAT, temperature),
                "downfall", new NbtValue.Numeric(NbtValue.Kind.FLOAT, 0.5F),
                "attributes", new NbtValue.Compound(attributes)));
    }

    private static NbtValue painting(int width, int height) {
        return new NbtValue.Compound(Map.of(
                "width", new NbtValue.Numeric(NbtValue.Kind.INT, width),
                "height", new NbtValue.Numeric(NbtValue.Kind.INT, height),
                "asset_id", new NbtValue.Text("example:asset")));
    }

    /** A global biome container over the received registry, every entry {@code id}. */
    private static void globalBiomes(ByteBuf output, int id, int registrySize) {
        int bits = 32 - Integer.numberOfLeadingZeros(registrySize - 1);
        assertTrue(bits > 3);
        output.writeByte(bits);
        int perWord = 64 / bits;
        long[] words = new long[(64 + perWord - 1) / perWord];
        for (int i = 0; i < 64; i++) words[i / perWord] |= (long) id << ((i % perWord) * bits);
        for (long word : words) output.writeLong(word);
    }

    private static int[][] chunkBiomes(WorldPackets.Chunk chunk, int sections, RegistryNames names) {
        var bytes = Unpooled.wrappedBuffer(chunk.sections());
        try {
            int[][] palettes = new int[sections][];
            for (int section = 0; section < sections; section++) {
                bytes.skipBytes(4);
                PalettedSection.readBlocks(bytes);
                palettes[section] = PalettedSection.readBiomes(bytes, names.size(BIOME));
            }
            return palettes;
        } finally {
            bytes.release();
        }
    }

    private static int[][] uniform(int sections, int id) {
        int[][] palettes = new int[sections][64];
        for (var palette : palettes) Arrays.fill(palette, id);
        return palettes;
    }

    static int paintingSerializer(ProtocolVersion version) {
        var names = new WireValueDecoder.Registries() {
            @Override
            public String name(String registry, int id) {
                return "test:probe";
            }

            @Override
            public int id(String registry, String name) {
                return 0;
            }
        };
        for (int serializer = 0; serializer < 64; serializer++) {
            var input = Unpooled.buffer();
            try {
                input.writeByte(8);
                Wire.writeVarInt(input, serializer);
                Wire.writeVarInt(input, 1);
                input.writeByte(255);
                if (ProtocolCodecs.decoder()
                        .metadata(version, input, names)
                        .getFirst()
                        .kind()
                        .equals("painting_variant")) return serializer;
            } catch (RuntimeException ignored) {
                // Other serializers cannot read this payload.
            } finally {
                input.release();
            }
        }
        throw new AssertionError("No painting variant serializer in " + version);
    }

    private static ByteBuf frame(RecordReceiveFixture fixture, ConnectionPhase phase, String name) {
        var frame = Unpooled.buffer();
        int id = fixture.data.packets(phase, PacketDirection.CLIENTBOUND).id("minecraft:" + name);
        assertTrue(id >= 0, name);
        Wire.writeVarInt(frame, id);
        return frame;
    }

    private static void identifier(ByteBuf output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Wire.writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }
}
