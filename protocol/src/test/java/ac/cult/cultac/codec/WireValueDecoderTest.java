package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.*;
import ac.cult.shaded.vialib.api.minecraft.*;
import ac.cult.shaded.vialib.api.minecraft.data.*;
import ac.cult.shaded.vialib.api.minecraft.entitydata.EntityData;
import ac.cult.shaded.vialib.api.minecraft.entitydata.EntityDataType;
import ac.cult.shaded.vialib.api.minecraft.item.*;
import ac.cult.shaded.vialib.api.minecraft.item.data.*;
import ac.cult.shaded.vialib.api.minecraft.item.data.consumable.*;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.nbt.tag.*;
import ac.cult.shaded.vialib.util.Unit;
import io.netty.buffer.*;
import java.nio.file.Path;
import java.util.*;
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
    void completePatchesRetainOpaqueValuesAndEveryRemovalAcrossWireVersions() {
        for (var version : ProtocolVersion.values()) {
            var types = PrivateCodecService.types(version);
            var ids = ModelRegistryData.load(version).registry("minecraft:data_component_type");
            var names = names(version);
            var entries = new ArrayList<StructuredData<?>>();
            // Components the movement/action engine does not inspect still affect stack identity.
            for (String name : List.of(
                    "repair_cost", "custom_name", "lore", "enchantment_glint_override", "intangible_projectile")) {
                int id = ids.id("minecraft:" + name);
                Object value = switch (name) {
                    case "repair_cost" -> 19;
                    case "enchantment_glint_override" -> true;
                    case "intangible_projectile" -> null;
                    case "lore" -> new ac.cult.shaded.vialib.nbt.tag.Tag[] {new StringTag("opaque lore")};
                    default -> new StringTag("opaque name");
                };
                entries.add(data(types.structuredData().key(id), value, id));
            }
            for (int id = 0; id < ids.size(); id++) {
                String name = ids.name(id);
                if (Set.of(
                                "minecraft:repair_cost",
                                "minecraft:custom_name",
                                "minecraft:lore",
                                "minecraft:enchantment_glint_override",
                                "minecraft:intangible_projectile")
                        .contains(name)) continue;
                var key = types.structuredData().key(id);
                assertNotNull(key, version + "/" + name);
                entries.add(StructuredData.empty(key, id));
            }
            var nested = new StructuredItem(
                    names.id("minecraft:item", "minecraft:stone"),
                    2,
                    new StructuredDataContainer(entries.toArray(StructuredData[]::new)));
            var outer = new ArrayList<StructuredData<?>>();
            for (String name : List.of(
                    "bundle_contents", "container", "charged_projectiles", "use_remainder", "sulfur_cube_content")) {
                int id = ids.id("minecraft:" + name);
                if (id < 0) continue;
                Object value = switch (name) {
                    case "use_remainder", "sulfur_cube_content" -> nested;
                    case "container" ->
                        version.atLeast(ProtocolVersion.V26_1)
                                ? new Item[] {null, nested, null}
                                : new Item[] {StructuredItem.empty(), nested, StructuredItem.empty()};
                    default -> new Item[] {nested};
                };
                outer.add(data(types.structuredData().key(id), value, id));
            }
            for (boolean creative : List.of(false, true)) {
                var input = Unpooled.buffer();
                try {
                    var item = new StructuredItem(
                            names.id("minecraft:item", "minecraft:stone"),
                            3,
                            new StructuredDataContainer(outer.toArray(StructuredData[]::new)));
                    (creative && version.atLeast(ProtocolVersion.V1_21_5) ? types.lengthPrefixedItem() : types.item())
                            .write(input, item);
                    var result = decoder.item(version, input, creative, names);
                    assertFalse(input.isReadable());
                    var layoutBytes = result.componentLayouts();
                    assertNotNull(layoutBytes);
                    byte layoutStart = layoutBytes[0];
                    layoutBytes[0] = 0;
                    assertEquals(layoutStart, result.componentLayouts()[0], "Result owns layout bytes");
                    var layouts = Unpooled.wrappedBuffer(result.componentLayouts());
                    try {
                        assertEquals(
                                3,
                                Types.COMPOUND_TAG
                                        .read(layouts)
                                        .getCompoundTag("minecraft:container")
                                        .getInt("slots"),
                                "Trailing optional slots survive the persistent component encoding");
                        assertFalse(layouts.isReadable());
                    } finally {
                        layouts.release();
                    }
                    var encoded = result.components();
                    byte original = encoded[0];
                    encoded[0] = 0;
                    assertEquals(original, result.components()[0], "Result owns its bytes");
                    var buffer = Unpooled.wrappedBuffer(result.components());
                    try {
                        var patch = Types.COMPOUND_TAG.read(buffer);
                        for (var entry : outer) {
                            String name = entry.key().identifier();
                            CompoundTag child = switch (name) {
                                case "container" ->
                                    patch.getListTag("minecraft:" + name, CompoundTag.class)
                                            .get(0)
                                            .getCompoundTag("item");
                                case "use_remainder", "sulfur_cube_content" ->
                                    patch.getCompoundTag("minecraft:" + name);
                                default ->
                                    patch.getListTag("minecraft:" + name, CompoundTag.class)
                                            .get(0);
                            };
                            var components = child.getCompoundTag("components");
                            assertEquals(ids.size(), components.size(), version + "/" + name);
                            assertEquals(19, components.getInt("minecraft:repair_cost"));
                            assertEquals("opaque name", components.getString("minecraft:custom_name"));
                            assertEquals(
                                    "opaque lore",
                                    components
                                            .getListTag("minecraft:lore", StringTag.class)
                                            .get(0)
                                            .getValue());
                            assertTrue(components.getBoolean("minecraft:enchantment_glint_override"));
                            assertTrue(components
                                    .getCompoundTag("minecraft:intangible_projectile")
                                    .isEmpty());
                            for (var data : entries)
                                if (data.isEmpty())
                                    assertTrue(
                                            components.get("!minecraft:"
                                                            + data.key().identifier())
                                                    instanceof CompoundTag,
                                            version + "/" + name + "/"
                                                    + data.key().identifier());
                            if (name.equals("container"))
                                assertEquals(
                                        1,
                                        patch.getListTag("minecraft:container", CompoundTag.class)
                                                .get(0)
                                                .getInt("slot"));
                        }
                    } finally {
                        buffer.release();
                    }
                } finally {
                    input.release();
                }
            }
        }
    }

    @Test
    void numericRecordSignsSurviveEveryWireVersionWithoutChangingRawNbt() {
        for (var version : ProtocolVersion.values()) {
            var types = PrivateCodecService.types(version);
            var names = names(version);
            int toolId = ModelRegistryData.load(version)
                    .registry("minecraft:data_component_type")
                    .id("minecraft:tool");
            int rawId = ModelRegistryData.load(version)
                    .registry("minecraft:data_component_type")
                    .id("minecraft:custom_data");
            for (boolean creative : List.of(false, true))
                for (float number : new float[] {0.0F, -0.0F}) {
                    var raw = new CompoundTag();
                    raw.putFloat("value", -0.0F);
                    var item = new StructuredItem(
                            names.id("minecraft:item", "minecraft:stone"),
                            1,
                            new StructuredDataContainer(new StructuredData[] {
                                data(
                                        types.structuredData().key(toolId),
                                        new ToolProperties(new ToolRule[0], number, 1, true),
                                        toolId),
                                data(types.structuredData().key(rawId), raw, rawId)
                            }));
                    var input = Unpooled.buffer();
                    try {
                        (creative && version.atLeast(ProtocolVersion.V1_21_5)
                                        ? types.lengthPrefixedItem()
                                        : types.item())
                                .write(input, item);
                        var result = decoder.item(version, input, creative, names);
                        assertFalse(input.isReadable());
                        boolean negative = Float.floatToRawIntBits(number) == Integer.MIN_VALUE;
                        assertEquals(negative, result.componentLayouts() != null);
                        if (negative) {
                            var encoded = Unpooled.wrappedBuffer(result.componentLayouts());
                            try {
                                var detail = Types.COMPOUND_TAG.read(encoded);
                                assertEquals(
                                        5,
                                        detail.getCompoundTag("minecraft:tool")
                                                .getCompoundTag("negative_zero")
                                                .getInt("default_mining_speed"));
                                assertFalse(detail.contains("minecraft:custom_data"));
                            } finally {
                                encoded.release();
                            }
                        }
                    } finally {
                        input.release();
                    }
                }
        }
    }

    @Test
    void adventureStateMatchersKeepOrderAndRepeatedNamesAcrossWireVersions() {
        var properties = new StatePropertyMatcher[] {
            new StatePropertyMatcher("axis", ac.cult.shaded.vialib.util.Either.left("x")),
            new StatePropertyMatcher("axis", ac.cult.shaded.vialib.util.Either.left("y"))
        };
        for (var version : ProtocolVersion.values()) {
            var types = PrivateCodecService.types(version);
            var names = names(version);
            var predicate = new BlockPredicate(null, properties, null);
            if (version.atLeast(ProtocolVersion.V1_21_5)) {
                int damage = ModelRegistryData.load(version)
                        .registry("minecraft:data_component_type")
                        .id("minecraft:damage");
                var exact = new StructuredData<?>[] {
                    data(types.structuredData().key(damage), 7, damage),
                    data(types.structuredData().key(damage), 8, damage)
                };
                predicate = new BlockPredicate(
                        null,
                        properties,
                        null,
                        new ac.cult.shaded.vialib.api.minecraft.data.predicate.DataComponentMatchers(
                                exact,
                                new ac.cult.shaded.vialib.api.minecraft.data.predicate.DataComponentPredicate[0]));
            }
            var value = new AdventureModePredicate(new BlockPredicate[] {predicate});
            for (String component : List.of("can_break", "can_place_on"))
                for (boolean creative : List.of(false, true)) {
                    int id = ModelRegistryData.load(version)
                            .registry("minecraft:data_component_type")
                            .id("minecraft:" + component);
                    var item = new StructuredItem(
                            names.id("minecraft:item", "minecraft:stone"),
                            1,
                            new StructuredDataContainer(new StructuredData[] {
                                data(types.structuredData().key(id), value, id)
                            }));
                    var input = Unpooled.buffer();
                    try {
                        (creative && version.atLeast(ProtocolVersion.V1_21_5)
                                        ? types.lengthPrefixedItem()
                                        : types.item())
                                .write(input, item);
                        var decoded = decoder.item(version, input, creative, names);
                        assertFalse(input.isReadable());
                        var detail = Unpooled.wrappedBuffer(decoded.componentLayouts());
                        try {
                            var layout = Types.COMPOUND_TAG.read(detail).getCompoundTag("minecraft:" + component);
                            var matchers = layout.getCompoundTag("states").getListTag("0", CompoundTag.class);
                            assertEquals(2, matchers.size());
                            assertEquals("axis", matchers.get(0).getString("name"));
                            assertEquals("x", matchers.get(0).getString("value"));
                            assertEquals("axis", matchers.get(1).getString("name"));
                            assertEquals("y", matchers.get(1).getString("value"));
                            if (version.atLeast(ProtocolVersion.V1_21_5)) {
                                var exact = layout.getCompoundTag("exact").getListTag("0", CompoundTag.class);
                                assertEquals(2, exact.size());
                                assertEquals("minecraft:damage", exact.get(0).getString("type"));
                                assertEquals(7, exact.get(0).getInt("value"));
                                assertEquals(8, exact.get(1).getInt("value"));
                            }
                        } finally {
                            detail.release();
                        }
                    } finally {
                        input.release();
                    }
                }
        }
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
                samples.put("map_id", 123);
                samples.put("painting_variant", Holder.of(new PaintingVariant(4, 3, "test:asset")));
                int brick = names.id("minecraft:item", "minecraft:brick");
                samples.put(
                        "pot_decorations",
                        version.atLeast(ProtocolVersion.V26_3)
                                ? new PotDecorations26_3(
                                        new StructuredItemTemplate(brick, 1, new StructuredDataContainer()),
                                        null,
                                        null,
                                        null)
                                : new PotDecorations1_20_5(new int[] {brick}));
                for (String component : WireValueDecoder.PROJECTED_COMPONENTS) {
                    int id = ids.id("minecraft:" + component);
                    if (id < 0) continue; // This component did not exist in this wire schema.
                    var key = types.structuredData().key(id);
                    Object value = samples.get(component);
                    if (component.equals("block_transformer")) value = 0;
                    if (component.equals("block_entity_data") && key.type().getOutputClass() != CompoundTag.class)
                        value = new BlockEntityData(
                                names.id("minecraft:block_entity_type", "minecraft:chest"), blockData);
                    if (component.equals("entity_data") && key.type().getOutputClass() != CompoundTag.class)
                        value = new ac.cult.shaded.vialib.api.minecraft.item.data.EntityData(
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
                                if (component.equals("pot_decorations")) {
                                    var decorations = nbt.getCompoundTag("minecraft:pot_decorations");
                                    assertEquals(
                                            "minecraft:brick",
                                            decorations.getCompoundTag("back").getString("id"));
                                    assertEquals(
                                            version.atLeast(ProtocolVersion.V26_3)
                                                    ? Set.of("back")
                                                    : Set.of("back", "left", "right", "front"),
                                            decorations.keySet());
                                }
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
    void paintingMetadataKeepsReceivedNamesAndInlineDimensions() throws Exception {
        var names = new WireValueDecoder.Registries() {
            public String name(String registry, int id) {
                assertEquals("minecraft:painting_variant", registry);
                assertEquals(3, id);
                return "test:wide";
            }

            public int id(String registry, String key) {
                return 3;
            }
        };
        for (var version : ProtocolVersion.values()) {
            var types = PrivateCodecService.types(version);
            var painting = field(types.entityDataTypes(), "paintingVariantType");
            var input = Unpooled.buffer();
            try {
                types.entityDataList()
                        .write(input, List.of(new EntityData(8, painting, Holder.<PaintingVariant>of(3))));
                var reference = decoder.metadata(version, input, names).getFirst();
                assertEquals("painting_variant", reference.kind());
                assertEquals("test:wide", reference.value());
                types.entityDataList()
                        .write(
                                input,
                                List.of(new EntityData(
                                        8, painting, Holder.of(new PaintingVariant(4, 3, "test:asset")))));
                var inline = decoder.metadata(version, input, names).getFirst();
                assertArrayEquals(new int[] {4, 3}, (int[]) inline.value());
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }
    }
}
