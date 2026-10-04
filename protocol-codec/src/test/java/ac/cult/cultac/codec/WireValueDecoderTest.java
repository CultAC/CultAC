package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.*;
import com.viaversion.nbt.tag.*;
import com.viaversion.viaversion.api.minecraft.*;
import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.minecraft.data.*;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityDataType;
import com.viaversion.viaversion.api.minecraft.item.*;
import com.viaversion.viaversion.api.minecraft.item.data.*;
import com.viaversion.viaversion.api.minecraft.item.data.consumable.*;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.*;
import com.viaversion.viaversion.util.Unit;
import io.netty.buffer.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WireValueDecoderTest {
    @TempDir
    static Path directory;

    private static PrivateCodecService decoder;

    @BeforeAll
    static void open() throws Exception {
        decoder = new PrivateCodecService(directory);
    }

    @AfterAll
    static void close() {
        if (decoder != null) decoder.close();
    }

    private static WireValueDecoder.Registries names(ProtocolVersion version) {
        var data = ModelRegistryData.load(version);
        return new WireValueDecoder.Registries() {
            public String name(String registry, int id) {
                if (registry.equals("minecraft:enchantment")) {
                    assertEquals(0, id);
                    return "minecraft:efficiency";
                }
                if (registry.equals("minecraft:block_transformer")) {
                    assertEquals(0, id);
                    return "minecraft:axe";
                }
                return data.registry(registry).name(id);
            }

            public int id(String registry, String name) {
                if (registry.equals("minecraft:enchantment")) {
                    assertEquals("minecraft:efficiency", name);
                    return 0;
                }
                return data.registry(registry).id(name);
            }
        };
    }

    @Test
    void versionedItemsPreserveConsumedComponentsTagsRemovalsAndNestedStacks() throws Exception {
        {
            for (var version : ProtocolVersion.values()) {
                var types = PrivateCodecService.types(version);
                var ids = ModelRegistryData.load(version).registry("minecraft:data_component_type");
                var names = names(version);
                String chain =
                        names.id("minecraft:item", "minecraft:chain") >= 0 ? "minecraft:chain" : "minecraft:iron_chain";
                String potion = names.id("minecraft:entity_type", "minecraft:potion") >= 0
                        ? "minecraft:potion"
                        : "minecraft:splash_potion";
                var enchantments = new Enchantments(true);
                enchantments.add(names.id("minecraft:enchantment", "minecraft:efficiency"), 3);
                var tag = new CompoundTag();
                tag.putInt("answer", 42);
                var blockData = new CompoundTag();
                blockData.putString("id", "minecraft:chest");
                var entityData = new CompoundTag();
                entityData.putString("id", potion);
                var samples = new HashMap<String, Object>();
                samples.put(
                        "tool",
                        new ToolProperties(
                                new ToolRule[] {
                                    new ToolRule(HolderSet.of("cult:mineable"), 5f, true),
                                    new ToolRule(HolderSet.of(new int[] {names.id("minecraft:block", chain)}), 3f, true)
                                },
                                2f,
                                1,
                                true));
                samples.put("food", new FoodProperties1_21_2(4, .6f, true));
                samples.put(
                        "consumable",
                        new Consumable1_21_2(
                                1.25f,
                                1,
                                Holder.of(names.id("minecraft:sound_event", "minecraft:entity.generic.eat")),
                                true,
                                new ConsumeEffect[0]));
                samples.put(
                        "equippable",
                        new Equippable(
                                1,
                                Holder.of(names.id("minecraft:sound_event", "minecraft:item.armor.equip_generic")),
                                null,
                                null,
                                HolderSet.of("cult:equippable"),
                                true,
                                false,
                                true));
                samples.put("glider", Unit.INSTANCE);
                samples.put("enchantments", enchantments);
                samples.put("use_cooldown", new UseCooldown(2f, "cult:cooldown"));
                samples.put("damage", 7);
                samples.put("max_damage", 99);
                samples.put("max_stack_size", 16);
                var nested = new StructuredDataContainer(new StructuredData[] {
                    data(
                            types.structuredData().key(ids.id("minecraft:creative_slot_lock")),
                            Unit.INSTANCE,
                            ids.id("minecraft:creative_slot_lock")),
                    data(
                            types.structuredData().key(ids.id("minecraft:map_post_processing")),
                            1,
                            ids.id("minecraft:map_post_processing")),
                    data(
                            types.structuredData().key(ids.id("minecraft:can_break")),
                            new AdventureModePredicate(new BlockPredicate[0]),
                            ids.id("minecraft:can_break")),
                    StructuredData.empty(
                            types.structuredData().key(ids.id("minecraft:max_stack_size")),
                            ids.id("minecraft:max_stack_size"))
                });
                samples.put(
                        "bundle_contents",
                        new Item[] {new StructuredItem(names.id("minecraft:item", chain), 2, nested)});
                var predicate = new AdventureModePredicate(
                        new BlockPredicate[] {new BlockPredicate(HolderSet.of("cult:allowed"), null, null)});
                samples.put("can_place_on", predicate);
                samples.put("can_break", predicate);
                samples.put(
                        "writable_book_content",
                        new WritableBook(new FilterableString[] {new FilterableString("page", null)}));
                samples.put("custom_data", tag);
                samples.put("block_state", new BlockStateProperties(Map.of("facing", "east")));
                samples.put("block_entity_data", blockData);
                samples.put("entity_data", entityData);
                samples.put("potion_contents", new PotionContents(null, 0x123456, new PotionEffect[0]));
                samples.put("dyed_color", new DyedColor(0x123456));
                samples.put("creative_slot_lock", Unit.INSTANCE);
                samples.put("map_post_processing", 0);
                for (String component : WireValueDecoder.COMPONENTS) {
                    int id = ids.id("minecraft:" + component);
                    if (id < 0) continue; // This component did not exist in this wire schema.
                    var key = types.structuredData().key(id);
                    Object value = samples.get(component);
                    if (component.equals("block_transformer")) value = 0;
                    if (component.equals("block_entity_data") && key.type().getOutputClass() != CompoundTag.class)
                        value = new BlockEntityData(
                                names.id("minecraft:block_entity_type", "minecraft:chest"), blockData);
                    if (component.equals("entity_data") && key.type().getOutputClass() != CompoundTag.class)
                        value = new com.viaversion.viaversion.api.minecraft.item.data.EntityData(
                                names.id("minecraft:entity_type", potion), entityData);
                    assertNotNull(value, component);
                    var stack = new StructuredItem(
                            names.id("minecraft:item", "minecraft:stone"),
                            2,
                            new StructuredDataContainer(new StructuredData[] {data(key, value, id)}));
                    for (boolean creative : List.of(false, true)) {
                        var input = Unpooled.buffer();
                        try {
                            (creative && version.atLeast(ProtocolVersion.V1_21_5)
                                            ? types.lengthPrefixedItem()
                                            : types.item())
                                    .write(input, stack);
                            var decoded = decoder.item(version, input, creative, names);
                            if (!creative) {
                                var fixture = Path.of(
                                        System.getProperty("wireValueFixtures"), version.name(), component + ".nbt");
                                java.nio.file.Files.createDirectories(fixture.getParent());
                                java.nio.file.Files.write(fixture, decoded.components());
                            }
                            assertFalse(input.isReadable(), version + "/" + component);
                            assertEquals(stack.identifier(), decoded.id());
                            assertEquals(2, decoded.count());
                            var patch = Unpooled.wrappedBuffer(decoded.components());
                            try {
                                var nbt = Types.COMPOUND_TAG.read(patch);
                                assertNotNull(nbt.get("minecraft:" + component), version + "/" + component);
                                if (component.equals("tool")) {
                                    assertEquals(
                                            "#cult:mineable",
                                            nbt.getCompoundTag("minecraft:tool")
                                                    .getListTag("rules", CompoundTag.class)
                                                    .get(0)
                                                    .getString("blocks"));
                                    assertEquals(
                                            "minecraft:iron_chain",
                                            nbt.getCompoundTag("minecraft:tool")
                                                    .getListTag("rules", CompoundTag.class)
                                                    .get(1)
                                                    .getString("blocks"));
                                }
                                if (component.equals("entity_data"))
                                    assertEquals(
                                            "minecraft:splash_potion",
                                            nbt.getCompoundTag("minecraft:entity_data")
                                                    .getString("id"));
                                if (component.equals("bundle_contents"))
                                    assertEquals(
                                            "minecraft:iron_chain",
                                            nbt.getListTag("minecraft:bundle_contents", CompoundTag.class)
                                                    .get(0)
                                                    .getString("id"));
                                if (component.equals("can_place_on"))
                                    assertEquals(
                                            "#cult:allowed",
                                            nbt.getListTag("minecraft:can_place_on", CompoundTag.class)
                                                    .get(0)
                                                    .getString("blocks"));
                                assertFalse(patch.isReadable());
                            } finally {
                                patch.release();
                            }
                        } catch (Throwable failure) {
                            throw new AssertionError(version + "/" + component, failure);
                        } finally {
                            input.release();
                        }
                    }
                    var removed =
                            new StructuredItem(stack.identifier(), 1, new StructuredDataContainer(new StructuredData[] {
                                StructuredData.empty(key, id)
                            }));
                    var input = Unpooled.buffer();
                    try {
                        types.item().write(input, removed);
                        var decoded = decoder.item(version, input, false, names);
                        var bytes = Unpooled.wrappedBuffer(decoded.components());
                        try {
                            assertNotNull(Types.COMPOUND_TAG.read(bytes).get("!minecraft:" + component));
                        } finally {
                            bytes.release();
                        }
                    } finally {
                        input.release();
                    }
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static StructuredData<?> data(StructuredDataKey key, Object value, int id) {
        return StructuredData.of(key, value, id);
    }

    @Test
    void staticHolderNamesUseTheSourceVersionsDirectedMappings() {
        for (var version : ProtocolVersion.values()) {
            var context = new ValueContext(names(version), version, decoder.mappings(version, ProtocolVersion.V26_3));
            var source = ModelRegistryData.load(version);
            for (String registry :
                    List.of("minecraft:block", "minecraft:item", "minecraft:entity_type", "minecraft:sound_event")) {
                var ids = source.registry(registry);
                for (int id = 0; id < ids.size(); id++) {
                    if (registry.equals("minecraft:entity_type")
                            && ModelIdMappings.project(version, ProtocolVersion.V26_3)
                                            .entity(id)
                                    < 0) continue;
                    var mapped = context.registryKey(registry, id).toString();
                    assertTrue(
                            ModelRegistryData.load(ProtocolVersion.V26_3)
                                            .registry(registry)
                                            .id(mapped)
                                    >= 0,
                            version + "/" + registry + "/" + ids.name(id));
                }
            }
        }
    }

    @Test
    void metadataRetainsEveryOriginalEntryAndUsesVersionedSerializers() throws Exception {
        {
            for (var version : ProtocolVersion.values()) {
                var types = PrivateCodecService.types(version);
                var data = types.entityDataTypes();
                var entries = List.of(
                        new EntityData(0, field(data, "byteType"), (byte) 2),
                        new EntityData(9, field(data, "floatType"), 1.5f),
                        new EntityData(6, field(data, "poseType"), 3),
                        new EntityData(8, field(data, "optionalVarIntType"), 0));
                var input = Unpooled.buffer();
                try {
                    types.entityDataList().write(input, entries);
                    byte[] original = ByteBufUtil.getBytes(input);
                    var decoded = decoder.metadata(version, input, names(version));
                    assertEquals(4, decoded.size());
                    assertFalse(input.isReadable());
                    assertEquals("pose", decoded.get(2).kind());
                    assertEquals(3, decoded.get(2).value());
                    assertEquals("optional_int", decoded.get(3).kind());
                    assertEquals(0, decoded.get(3).value());
                    var output = Unpooled.buffer();
                    try {
                        decoded.forEach(entry -> output.writeBytes(entry.bytes()));
                        output.writeByte(255);
                        assertArrayEquals(original, ByteBufUtil.getBytes(output));
                    } finally {
                        output.release();
                    }
                } finally {
                    input.release();
                }
            }
        }
    }

    private static EntityDataType field(Object value, String field) throws Exception {
        return (EntityDataType) value.getClass().getField(field).get(value);
    }

    @Test
    void paletteProjectionPreservesGeometryInEveryVersionUnderConcurrentUse() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Future<?>>();
            for (var version : ProtocolVersion.values())
                jobs.add(workers.submit(() -> {
                    var sourceData = ModelRegistryData.load(version);
                    int blockBits = bits(sourceData.blockStates().size());
                    var source = version.atLeast(ProtocolVersion.V26_1)
                            ? new ChunkSectionType26_1(blockBits, 6)
                            : version.atLeast(ProtocolVersion.V1_21_5)
                                    ? new ChunkSectionType1_21_5(blockBits, 6)
                                    : new ChunkSectionType1_18(blockBits, 6);
                    var section = new ChunkSectionImpl();
                    section.setNonAirBlocksCount(4096);
                    section.setFluidCount(0);
                    var blocks = new DataPaletteImpl(4096);
                    blocks.addId(0);
                    blocks.addId(10);
                    for (int i = 0; i < 4096; i++) blocks.setPaletteIndexAt(i, i % 2);
                    section.addPalette(PaletteType.BLOCKS, blocks);
                    var biomes = new DataPaletteImpl(64);
                    biomes.addId(1);
                    section.addPalette(PaletteType.BIOMES, biomes);
                    var input = Unpooled.buffer();
                    try {
                        source.write(input, section);
                        var bytes = decoder.sections(version, input, 1, blockBits, 6, 6, id -> id + 2);
                        assertFalse(input.isReadable());
                        var output = Unpooled.wrappedBuffer(bytes);
                        try {
                            var decoded = new ChunkSectionType26_1(
                                            bits(ModelRegistryData.load(ProtocolVersion.V26_3)
                                                    .blockStates()
                                                    .size()),
                                            6)
                                    .read(output);
                            var mapping = ModelIdMappings.project(version, ProtocolVersion.V26_3);
                            for (int i = 0; i < 4096; i++)
                                assertEquals(
                                        mapping.blockState(i % 2 == 0 ? 0 : 10),
                                        decoded.palette(PaletteType.BLOCKS).idAt(i));
                            assertEquals(3, decoded.palette(PaletteType.BIOMES).idAt(0));
                            assertFalse(output.isReadable());
                        } finally {
                            output.release();
                        }
                    } finally {
                        input.release();
                    }
                }));
            for (var job : jobs) job.get(30, TimeUnit.SECONDS);
        }
    }

    private static int bits(int count) {
        return 32 - Integer.numberOfLeadingZeros(count - 1);
    }
}
