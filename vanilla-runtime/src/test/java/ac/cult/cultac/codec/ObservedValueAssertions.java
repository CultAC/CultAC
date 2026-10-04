package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.codec.*;
import ac.cult.cultac.network.packet.*;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.*;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.vanilla.VanillaBootstrap;
import ac.cult.cultac.vanilla.VanillaRegistryState;
import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityDataType;
import com.viaversion.viaversion.api.minecraft.item.*;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.*;
import io.netty.buffer.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;

/** Exercises the isolated decoder SPI through the actual native record consumers. */
public final class ObservedValueAssertions {
    private ObservedValueAssertions() {}

    public static void verify(VanillaBootstrap model, Path directory) throws Exception {
        try (var decoder = new PrivateCodecService(directory)) {
            for (var version : ProtocolVersion.values()) {
                var state = model.newConnection();
                var values = new ObservedPacketValues(decoder, version, state.registries(), () -> new int[] {-64, 384});
                var source = ModelRegistryData.load(version);
                var input = Unpooled.buffer();
                try {
                    // Received dynamic IDs deliberately differ from the model's default order.
                    Types.STRING.write(input, "minecraft:enchantment");
                    Wire.writeVarInt(input, 2);
                    for (String name : List.of("minecraft:unbreaking", "minecraft:efficiency")) {
                        Types.STRING.write(input, name);
                        input.writeByte(0); // known-pack reference
                    }
                    var registry = (RegistryData) read(
                            values,
                            state.registries().access(),
                            version,
                            ConnectionPhase.CONFIGURATION,
                            "registry_data",
                            input);
                    assertEquals(Registries.ENCHANTMENT, registry.registry());
                    assertTrue(registry.entries().isEmpty(), "Older dynamic registries use vanilla entries by name");
                    assertFalse(input.isReadable());
                    verifyDimension(values, state, version, input);
                    input.clear();
                    Wire.writeVarInt(input, 2);
                    for (String name : List.of("minecraft:block", "minecraft:enchantment")) {
                        Wire.writeString(input, name, 32767);
                        Wire.writeVarInt(input, 1);
                        Wire.writeString(input, "cult:wire", 32767);
                        Wire.writeVarInt(input, 1);
                        Wire.writeVarInt(
                                input,
                                name.equals("minecraft:block")
                                        ? source.registry(name).id("minecraft:stone")
                                        : 1);
                    }
                    var tags = (RegistryTags) read(
                            values,
                            state.registries().access(),
                            version,
                            ConnectionPhase.CONFIGURATION,
                            "update_tags",
                            input);
                    assertFalse(input.isReadable());
                    state.execute(() -> {
                        state.appendTags(tags.tags());
                        state.finishOlder();
                        var dimension = state.registries()
                                .access()
                                .lookupOrThrow(Registries.DIMENSION_TYPE)
                                .get(Identifier.parse("cult:wire_dimension"))
                                .orElseThrow()
                                .value();
                        assertEquals(-48, dimension.minY());
                        assertEquals(256, dimension.height());
                        assertEquals(256, dimension.logicalHeight());
                        assertFalse(dimension.hasSkyLight());
                        assertTrue(dimension
                                .attributes()
                                .applyModifier(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA, false));
                        assertTrue(Blocks.STONE
                                .builtInRegistryHolder()
                                .is(TagKey.create(Registries.BLOCK, Identifier.parse("cult:wire"))));
                        var enchantments = state.registries().access().lookupOrThrow(Registries.ENCHANTMENT);
                        assertTrue(enchantments
                                .get(Identifier.parse("minecraft:efficiency"))
                                .orElseThrow()
                                .is(TagKey.create(Registries.ENCHANTMENT, Identifier.parse("cult:wire"))));
                        assertFalse(enchantments
                                .get(Identifier.parse("minecraft:unbreaking"))
                                .orElseThrow()
                                .is(TagKey.create(Registries.ENCHANTMENT, Identifier.parse("cult:wire"))));
                    });

                    input.clear();
                    Wire.writeVarInt(input, 0); // Entity ID zero must remain zero.
                    var metadataTypes = PrivateCodecService.types(version).entityDataTypes();
                    var entries = List.of(
                            new EntityData(0, serializer(metadataTypes, "byteType"), (byte) 2),
                            new EntityData(9, serializer(metadataTypes, "floatType"), 15f),
                            new EntityData(6, serializer(metadataTypes, "poseType"), 3),
                            new EntityData(8, serializer(metadataTypes, "optionalVarIntType"), 0),
                            new EntityData(
                                    16,
                                    serializer(metadataTypes, "itemType"),
                                    new StructuredItem(
                                            source.registry("minecraft:item").id("minecraft:stone"), 2)));
                    PrivateCodecService.types(version).entityDataList().write(input, entries);
                    byte[] original = ByteBufUtil.getBytes(input);
                    var metadata = (EntityMetadata) read(
                            values,
                            state.registries().access(),
                            version,
                            ConnectionPhase.PLAY,
                            "set_entity_data",
                            input);
                    assertEquals(0, metadata.id());
                    assertEquals(Pose.SWIMMING, metadata.packedItems().get(2).value());
                    assertEquals(
                            OptionalInt.of(0), metadata.packedItems().get(3).value());
                    var metadataItem = (net.minecraft.world.item.ItemStack)
                            metadata.packedItems().get(4).value();
                    assertSame(Items.STONE, metadataItem.getItem());
                    assertEquals(2, metadataItem.getCount());
                    input.clear();
                    values.write(
                            input,
                            context(state.registries().access(), version, ConnectionPhase.PLAY, "set_entity_data"),
                            metadata);
                    assertArrayEquals(original, ByteBufUtil.getBytes(input));
                    input.clear();
                    values.write(
                            input,
                            context(state.registries().access(), version, ConnectionPhase.PLAY, "set_entity_data"),
                            new EntityMetadata(0, List.of(EntityMetadata.Entry.health(9, version, 1f))));
                    Wire.readVarInt(input);
                    var edited = decoder.metadata(version, input, new WireRegistryState(version, state.registries()));
                    assertEquals(9, edited.getFirst().index());
                    assertEquals(1f, edited.getFirst().value());
                    assertFalse(input.isReadable());

                    var wireItem =
                            new StructuredItem(source.registry("minecraft:item").id("minecraft:stone"), 3);
                    input.clear();
                    PrivateCodecService.types(version).item().write(input, wireItem);
                    var cursor = (InventoryPackets.Cursor) read(
                            values,
                            state.registries().access(),
                            version,
                            ConnectionPhase.PLAY,
                            "set_cursor_item",
                            input);
                    assertSame(Items.STONE, cursor.item().getItem());
                    assertEquals(3, cursor.item().getCount());
                    assertFalse(input.isReadable());
                    input.clear();
                    input.writeShort(36);
                    (version.atLeast(ProtocolVersion.V1_21_5)
                                    ? PrivateCodecService.types(version).lengthPrefixedItem()
                                    : PrivateCodecService.types(version).item())
                            .write(input, wireItem);
                    var creative = (InventoryPackets.CreativeSlot) read(
                            values,
                            state.registries().access(),
                            version,
                            ConnectionPhase.PLAY,
                            "set_creative_mode_slot",
                            input);
                    assertEquals(36, creative.slot());
                    assertSame(Items.STONE, creative.item().getItem());
                    assertFalse(input.isReadable());
                    verifySpawn(values, state.registries().access(), version, source, input);
                    ObservedInventoryAssertions.verify(
                            values, state.registries().access(), version, source, input);

                    input.clear();
                    var position = new BlockPos(1, 70, -3);
                    input.writeLong(position.asLong());
                    int sourceState = ModelIdMappings.project(ProtocolVersion.V26_3, version)
                            .blockState(Block.getId(Blocks.STONE.defaultBlockState()));
                    Wire.writeVarInt(input, sourceState);
                    byte[] blockBytes = ByteBufUtil.getBytes(input);
                    var block = (WorldPackets.BlockUpdate) read(
                            values, state.registries().access(), version, ConnectionPhase.PLAY, "block_update", input);
                    assertEquals(position, block.position());
                    assertSame(Blocks.STONE.defaultBlockState(), block.state());
                    input.clear();
                    values.write(
                            input,
                            context(state.registries().access(), version, ConnectionPhase.PLAY, "block_update"),
                            block);
                    assertArrayEquals(blockBytes, ByteBufUtil.getBytes(input));
                    verifyChunkAndLight(values, state.registries().access(), version, source, input);
                } catch (Throwable failure) {
                    throw new AssertionError(version.toString(), failure);
                } finally {
                    input.release();
                }
            }
        }
    }

    private static void verifySpawn(
            ObservedPacketValues values,
            RegistryAccess access,
            ProtocolVersion version,
            ModelRegistryData source,
            ByteBuf input) {
        var types = source.registry("minecraft:entity_type");
        String type = types.id("minecraft:potion") >= 0 ? "minecraft:potion" : "minecraft:splash_potion";
        input.clear();
        Wire.writeVarInt(input, 0);
        Wire.writeUuid(input, new UUID(1, 2));
        Wire.writeVarInt(input, types.id(type));
        input.writeDouble(1).writeDouble(70).writeDouble(-3);
        var velocity = new ac.cult.cultac.protocol.value.Vec3d(0, 0, 0);
        if (version.atLeast(ProtocolVersion.V1_21_9)) Wire.writeLpVec3(input, velocity);
        input.writeByte(0).writeByte(0).writeByte(0);
        Wire.writeVarInt(input, 0);
        if (!version.atLeast(ProtocolVersion.V1_21_9)) Wire.writeShortVelocity(input, velocity);
        var packet = (ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity)
                read(values, access, version, ConnectionPhase.PLAY, "add_entity", input);
        assertEquals(0, packet.entityId());
        assertEquals("minecraft:splash_potion", packet.entityType());
        assertFalse(input.isReadable());
    }

    private static void verifyDimension(
            ObservedPacketValues values, VanillaRegistryState state, ProtocolVersion version, ByteBuf input) {
        var access = state.registries().access();
        var nether = access.lookupOrThrow(Registries.DIMENSION_TYPE)
                .get(Identifier.parse("minecraft:the_nether"))
                .orElseThrow()
                .value();
        var json = net.minecraft.world.level.dimension.DimensionType.NETWORK_CODEC
                .encodeStart(
                        net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, access),
                        nether)
                .getOrThrow()
                .getAsJsonObject();
        json.addProperty("min_y", -48);
        json.addProperty("height", 256);
        json.addProperty("logical_height", 256);
        String projected = ac.cult.cultac.utils.minecraft.ModelDimensions.project(
                "cult:wire_dimension", json.toString(), ProtocolVersion.V26_3, version);
        var tag = com.mojang.serialization.JsonOps.INSTANCE.convertTo(
                net.minecraft.nbt.NbtOps.INSTANCE, com.google.gson.JsonParser.parseString(projected));
        input.clear();
        Wire.writeString(input, "minecraft:dimension_type", 32767);
        Wire.writeVarInt(input, 1);
        Wire.writeString(input, "cult:wire_dimension", 32767);
        input.writeBoolean(true);
        new net.minecraft.network.FriendlyByteBuf(input).writeNbt(tag);
        var registry =
                (RegistryData) read(values, access, version, ConnectionPhase.CONFIGURATION, "registry_data", input);
        assertFalse(input.isReadable());
        assertEquals(1, registry.entries().size());
        state.append(registry.registry(), registry.entries());
    }

    private static void verifyChunkAndLight(
            ObservedPacketValues values,
            RegistryAccess registries,
            ProtocolVersion version,
            ModelRegistryData source,
            ByteBuf input) {
        input.clear();
        Types.STRING.write(input, "minecraft:worldgen/biome");
        Wire.writeVarInt(input, 2);
        for (String name : List.of("minecraft:plains", "minecraft:forest")) {
            Types.STRING.write(input, name);
            input.writeByte(0);
        }
        read(values, registries, version, ConnectionPhase.CONFIGURATION, "registry_data", input);
        var section = new ChunkSectionImpl();
        section.setNonAirBlocksCount(4096);
        section.setFluidCount(0);
        var blocks = new DataPaletteImpl(4096);
        int stone = ModelIdMappings.project(ProtocolVersion.V26_3, version)
                .blockState(Block.getId(Blocks.STONE.defaultBlockState()));
        blocks.addId(stone);
        section.addPalette(PaletteType.BLOCKS, blocks);
        var biomes = new DataPaletteImpl(64);
        biomes.addId(1);
        section.addPalette(PaletteType.BIOMES, biomes);
        int bits = 32 - Integer.numberOfLeadingZeros(source.blockStates().size() - 1);
        var sectionType = version.atLeast(ProtocolVersion.V26_1)
                ? new ChunkSectionType26_1(bits, 1)
                : version.atLeast(ProtocolVersion.V1_21_5)
                        ? new ChunkSectionType1_21_5(bits, 1)
                        : new ChunkSectionType1_18(bits, 1);
        var sections = Unpooled.buffer();
        try {
            for (int index = 0; index < 24; index++) sectionType.write(sections, section);
            input.clear();
            input.writeInt(3).writeInt(-4);
            if (version.atLeast(ProtocolVersion.V1_21_5)) Wire.writeVarInt(input, 0);
            else Types.COMPOUND_TAG.write(input, new com.viaversion.nbt.tag.CompoundTag());
            Wire.writeVarInt(input, sections.readableBytes());
            input.writeBytes(sections);
            Wire.writeVarInt(input, 0); // block entities
            writeLight(input, version);
            var chunk = (WorldPackets.Chunk)
                    read(values, registries, version, ConnectionPhase.PLAY, "level_chunk_with_light", input);
            assertFalse(input.isReadable(), version + " chunk framing");
            assertEquals(3, chunk.x());
            assertEquals(-4, chunk.z());
            assertLight(chunk.light());
            var nativeBytes = new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(chunk.sections()));
            try {
                for (int index = 0; index < 24; index++) {
                    var nativeSection = new net.minecraft.world.level.chunk.LevelChunkSection(
                            net.minecraft.world.level.chunk.PalettedContainerFactory.create(registries));
                    nativeSection.read(nativeBytes);
                    assertSame(Blocks.STONE.defaultBlockState(), nativeSection.getBlockState(7, 8, 9));
                    assertEquals(
                            Identifier.parse("minecraft:forest"),
                            nativeSection
                                    .getNoiseBiome(1, 2, 3)
                                    .unwrapKey()
                                    .orElseThrow()
                                    .identifier());
                }
                assertFalse(nativeBytes.isReadable());
            } finally {
                nativeBytes.release();
            }
            input.clear();
            Wire.writeVarInt(input, 3);
            Wire.writeVarInt(input, -4);
            writeLight(input, version);
            var light = (WorldPackets.LightUpdate)
                    read(values, registries, version, ConnectionPhase.PLAY, "light_update", input);
            assertLight(light.light());
            assertFalse(input.isReadable(), version + " light framing");
        } finally {
            sections.release();
        }
    }

    private static void writeLight(ByteBuf input, ProtocolVersion version) {
        for (long mask : new long[] {2, 4, 8, 16}) {
            if (version == ProtocolVersion.V26_3) Types.BIT_SET.write(input, BitSet.valueOf(new long[] {mask}));
            else Types.LONG_ARRAY_PRIMITIVE.write(input, new long[] {mask});
        }
        for (byte value : new byte[] {0x12, 0x34}) {
            Wire.writeVarInt(input, 1);
            var layer = new byte[2048];
            Arrays.fill(layer, value);
            Types.BYTE_ARRAY_PRIMITIVE.write(input, layer);
        }
    }

    private static void assertLight(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData light) {
        assertEquals(BitSet.valueOf(new long[] {2}), light.skyYMask());
        assertEquals(BitSet.valueOf(new long[] {4}), light.blockYMask());
        assertEquals(BitSet.valueOf(new long[] {8}), light.emptySkyYMask());
        assertEquals(BitSet.valueOf(new long[] {16}), light.emptyBlockYMask());
        assertEquals(2048, light.skyUpdates().getFirst().length);
        assertEquals(0x12, light.skyUpdates().getFirst()[0]);
        assertEquals(0x34, light.blockUpdates().getFirst()[2047]);
    }

    private static EntityDataType serializer(Object values, String name) throws Exception {
        return (EntityDataType) values.getClass().getField(name).get(values);
    }

    static Object read(
            ObservedPacketValues values,
            RegistryAccess registries,
            ProtocolVersion version,
            ConnectionPhase phase,
            String name,
            ByteBuf input) {
        return values.read(input, context(registries, version, phase, name));
    }

    private static ProtocolContext context(
            RegistryAccess registries, ProtocolVersion version, ConnectionPhase phase, String name) {
        String wireName = "minecraft:" + name;
        var type = NativePacketCodecs.catalog(registries).stream()
                .filter(value ->
                        value.phases().contains(phase) && value.wireNames().contains(wireName))
                .findFirst()
                .orElseThrow();
        return new ProtocolContext(type, ProtocolData.load(version), phase, wireName, 256, 0);
    }
}
