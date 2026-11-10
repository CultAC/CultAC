package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ac.cult.blocksim.data.BundleContents;
import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ItemTemplate;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Sent stacks converted on first read equal the stacks converted at decode, and fail and hold alike. */
class SentStackDeferralTest {
    private static final ProtocolVersion VERSION = ProtocolVersion.of(774);

    private final WireRegistryState names = registries();
    private final ModelItemValues plain =
            new ModelItemValues(ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems());

    /** The dynamic registries the fixture stacks name, as a server's registry data sends them. */
    static WireRegistryState registries() {
        var names = new WireRegistryState(VERSION, () -> null);
        names.append(registry(
                "minecraft:enchantment", "minecraft:protection", "minecraft:sharpness", "minecraft:unbreaking"));
        names.append(registry("minecraft:trim_material", "minecraft:gold"));
        names.append(registry("minecraft:trim_pattern", "minecraft:coast"));
        return names;
    }

    private static WireValueDecoder.RegistryValues registry(String registry, String... entries) {
        var values = new ArrayList<WireValueDecoder.RegistryEntry>();
        for (String entry : entries) values.add(new WireValueDecoder.RegistryEntry(entry, null));
        return new WireValueDecoder.RegistryValues(registry, values);
    }

    private ModelItemValues cached(ItemStackCache cache) {
        return new ModelItemValues(
                ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems(), cache, names::generation);
    }

    private static SimItemStack stack(String item, int count, String... components) {
        var stack = ItemUtil.modelItems().stack(item, count);
        for (int index = 0; index < components.length; index += 2)
            stack.setComponent(components[index], CanonicalSnbt.parse(components[index + 1]));
        return stack;
    }

    /** Kits, armor and tools as servers send them, plus stacks whose conversion stays at decode. */
    static List<SimItemStack> stacks() {
        var bundle = ItemUtil.modelItems().stack("minecraft:bundle", 1);
        bundle.bundleContents(
                new BundleContents(List.of(new ItemTemplate("minecraft:stone", 2, ComponentPatch.EMPTY))));
        var deep = new StringBuilder();
        for (int depth = 0; depth < 70; depth++) deep.append("{a:");
        deep.append("1");
        for (int depth = 0; depth < 70; depth++) deep.append('}');
        return List.of(
                stack("minecraft:diamond_sword", 1, "minecraft:damage", "12", "minecraft:enchantments",
                        "{'minecraft:sharpness':5,'minecraft:unbreaking':3}"),
                stack("minecraft:netherite_helmet", 1, "minecraft:enchantments",
                        "{'minecraft:protection':4}", "minecraft:repair_cost", "3"),
                stack("minecraft:leather_chestplate", 1, "minecraft:dyed_color", "16711680"),
                stack("minecraft:iron_leggings", 1, "minecraft:damage", "3"),
                stack("minecraft:diamond_boots", 1, "minecraft:unbreakable", "{}",
                        "minecraft:enchantment_glint_override", "1b"),
                stack("minecraft:golden_apple", 64),
                stack("minecraft:stone", 32),
                stack("minecraft:bow", 1, "minecraft:custom_data", "{kit:'archer',tier:3,tags:['a','b']}"),
                stack("minecraft:shield", 1),
                stack("minecraft:elytra", 1, "minecraft:damage", "400"),
                stack("minecraft:totem_of_undying", 1),
                stack("minecraft:stick", 1, "minecraft:custom_data", deep.toString()),
                bundle);
    }

    private byte[] bytes(SimItemStack stack) {
        ByteBuf output = Unpooled.buffer();
        plain.write(output, stack);
        return ByteBufUtil.getBytes(output);
    }

    private List<byte[]> fixtures() {
        var fixtures = new ArrayList<byte[]>();
        for (var stack : stacks()) {
            fixtures.add(bytes(stack));
            var other = stack.copyWithCount(stack.getMaxStackSize() > 1 ? 7 : 2);
            fixtures.add(bytes(other));
        }
        fixtures.add(bytes(SimItemStack.EMPTY));
        return fixtures;
    }

    private void assertSameStack(SimItemStack expected, SimItemStack actual) {
        assertEquals(expected.itemKey(), actual.itemKey());
        assertEquals(expected.count(), actual.count());
        assertEquals(expected.isEmpty(), actual.isEmpty());
        assertEquals(expected.components(), actual.components());
        assertEquals(List.copyOf(expected.components().keys()), List.copyOf(actual.components().keys()));
        assertEquals(expected.components().json(), actual.components().json());
        assertEquals(expected.patch(), actual.patch());
        assertEquals(expected.patch().removed(), actual.patch().removed());
        assertEquals(expected.prototype(), actual.prototype());
        assertEquals(expected.getMaxStackSize(), actual.getMaxStackSize());
        assertEquals(expected.getDamageValue(), actual.getDamageValue());
        assertEquals(expected.getEnchantments(), actual.getEnchantments());
        assertEquals(expected.wirePatch() == null, actual.wirePatch() == null);
        if (expected.wirePatch() != null)
            assertArrayEquals(expected.wirePatch().bytes(), actual.wirePatch().bytes());
        assertTrue(expected.sameItemSameComponents(actual));
        assertArrayEquals(bytes(expected), bytes(actual));
    }

    private static Object field(ItemStackCache cache, String name) {
        try {
            Field field = ItemStackCache.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(cache);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Same keys in the same slots, with the same sizes and recency: every later hit and eviction agree. */
    private static void assertSameKeys(ItemStackCache expected, ItemStackCache actual) {
        assertEquals(expected.held(), actual.held());
        assertTrue(Arrays.deepEquals((byte[][]) field(expected, "keys"), (byte[][]) field(actual, "keys")));
        assertArrayEquals((long[]) field(expected, "heads"), (long[]) field(actual, "heads"));
        assertArrayEquals((long[]) field(expected, "used"), (long[]) field(actual, "used"));
        assertArrayEquals((int[]) field(expected, "sizes"), (int[]) field(actual, "sizes"));
        assertEquals(field(expected, "clock"), field(actual, "clock"));
    }

    private static WireValueDecoder.DecodedItem<NbtValue> decoded(byte[] bytes) {
        return ProtocolCodecs.decoder()
                .item(VERSION, Unpooled.wrappedBuffer(bytes), false, registries(),
                        ModelNbtFactory.INSTANCE);
    }

    @Test
    void realisticStacksDeferAndTheOthersConvertAtDecode() {
        var stacks = stacks();
        int deferred = 0;
        for (int index = 0; index < stacks.size(); index++) {
            boolean defers = ModelItemValues.Pending.defers(decoded(bytes(stacks.get(index))));
            // The last two carry deep custom data and bundle contents.
            assertEquals(index < stacks.size() - 2, defers, stacks.get(index).itemKey());
            if (defers) deferred++;
        }
        assertTrue(deferred >= 10);
        for (String key : List.of(
                "minecraft:bundle_contents",
                "minecraft:can_break",
                "minecraft:can_place_on",
                "minecraft:tooltip_display"))
            assertFalse(ModelItemValues.Pending.defers(new WireValueDecoder.DecodedItem<>(
                    1, 1, new NbtValue.Compound(Map.of(key, new NbtValue.Compound(Map.of()))), null, Map.of(), null)));
        assertTrue(ModelItemValues.Pending.defers(new WireValueDecoder.DecodedItem<>(
                1, 1, new NbtValue.Compound(Map.of("!minecraft:bundle_contents", new NbtValue.Compound(Map.of()))),
                null, Map.of(), null)));
        var signed = new NbtValue.Compound(Map.of(
                "minecraft:custom_data",
                new NbtValue.Compound(Map.of("negative_zero", new NbtValue.Numeric(NbtValue.Kind.INT, 5)))));
        var value = decoded(bytes(stacks.get(7)));
        assertFalse(ModelItemValues.Pending.defers(new WireValueDecoder.DecodedItem<>(
                value.id(), value.count(), value.components(), signed, value.componentEncodings(), value.wirePatch())));
        assertFalse(ModelItemValues.Pending.defers(new WireValueDecoder.DecodedItem<>(
                value.id(), value.count(), new NbtValue.Text("x"), null, Map.of(), null)));
        assertFalse(ModelItemValues.Pending.defers(new WireValueDecoder.DecodedItem<>(
                value.id(), value.count(),
                new NbtValue.Compound(Map.of("minecraft:custom_data", new NbtValue.Text(null))), null, Map.of(),
                null)));
    }

    @Test
    void deferredStacksEqualStacksConvertedAtDecodeAndHoldTheSameKeys() {
        var eagerCache = new ItemStackCache();
        var laterCache = new ItemStackCache();
        var eager = cached(eagerCache);
        var later = cached(laterCache);
        var fixtures = fixtures();
        var expected = new ArrayList<SimItemStack>();
        var supplied = new ArrayList<Supplier<SimItemStack>>();
        for (int round = 0; round < 3; round++) {
            for (int index = 0; index < fixtures.size(); index++) {
                byte[] bytes = fixtures.get(index);
                var eagerInput = Unpooled.buffer().writeBytes(bytes).writeByte(0x7f);
                var laterInput = Unpooled.buffer().writeBytes(bytes).writeByte(0x7f);
                assertTrue(later.convertsLater());
                expected.add(eager.sentItem(eagerInput));
                supplied.add(later.sentLater(laterInput));
                assertEquals(eagerInput.readerIndex(), laterInput.readerIndex());
                // An eager reader of the same connection reads held stacks the deferred reader left unconverted.
                if ((index + round) % 3 == 0) {
                    var again = Unpooled.wrappedBuffer(bytes);
                    assertSameStack(eager.sentItem(Unpooled.wrappedBuffer(bytes)), later.sentItem(again));
                    assertEquals(bytes.length, again.readerIndex());
                }
                assertSameKeys(eagerCache, laterCache);
            }
        }
        // Registry answers and held stacks change before any deferred stack is read.
        var generation = names.generation();
        names.append(registry("minecraft:trim_pattern", "minecraft:coast"));
        assertFalse(generation == names.generation());
        for (byte[] bytes : fixtures) later.sentItem(Unpooled.wrappedBuffer(bytes));
        for (int index = supplied.size() - 1; index >= 0; index--) {
            var stack = supplied.get(index).get();
            assertSameStack(expected.get(index), stack);
            assertSame(stack, supplied.get(index).get());
        }
        for (int index = 0; index < supplied.size(); index++)
            for (int other = index + 1; other < supplied.size(); other++)
                if (!supplied.get(index).get().isEmpty())
                    assertFalse(supplied.get(index).get() == supplied.get(other).get());
    }

    @Test
    void malformedStacksFailAlikeAndLeaveTheSameKeys() {
        var eagerCache = new ItemStackCache();
        var laterCache = new ItemStackCache();
        var eager = cached(eagerCache);
        var later = cached(laterCache);
        var fixtures = fixtures();
        var inputs = new ArrayList<byte[]>();
        for (byte[] bytes : fixtures) {
            for (int length = 0; length < bytes.length; length++) inputs.add(Arrays.copyOf(bytes, length));
            inputs.add(bytes);
            var corrupt = bytes.clone();
            if (corrupt.length > 3) corrupt[corrupt.length / 2] ^= 0x5a;
            inputs.add(corrupt);
        }
        int items = names.id("minecraft:item", "minecraft:stone");
        while (named(items + 1)) items++;
        for (int id : new int[] {items - 1, items, items + 1, items + 2, 30000}) {
            ByteBuf unknown = Unpooled.buffer();
            Wire.writeVarInt(unknown, 1);
            Wire.writeVarInt(unknown, id);
            Wire.writeVarInt(unknown, 0);
            Wire.writeVarInt(unknown, 0);
            inputs.add(ByteBufUtil.getBytes(unknown));
        }
        inputs.add(new byte[] {1, -1, -1, -1, -1, 15, 0, 0});
        int failures = 0;
        for (byte[] bytes : inputs) {
            var eagerInput = Unpooled.wrappedBuffer(bytes);
            var laterInput = Unpooled.wrappedBuffer(bytes);
            RuntimeException eagerFailure = null, laterFailure = null;
            SimItemStack stack = null;
            Supplier<SimItemStack> supplier = null;
            try {
                stack = eager.sentItem(eagerInput);
            } catch (RuntimeException e) {
                eagerFailure = e;
            }
            try {
                supplier = later.sentLater(laterInput);
            } catch (RuntimeException e) {
                laterFailure = e;
            }
            assertEquals(eagerFailure == null, laterFailure == null, () -> Arrays.toString(bytes));
            if (eagerFailure != null) {
                failures++;
                assertEquals(eagerFailure.getClass(), laterFailure.getClass());
                assertEquals(eagerFailure.getMessage(), laterFailure.getMessage());
            } else assertSameStack(stack, supplier.get());
            assertEquals(eagerInput.readerIndex(), laterInput.readerIndex());
            assertSameKeys(eagerCache, laterCache);
        }
        assertTrue(failures > inputs.size() / 2);
    }

    private boolean named(int item) {
        try {
            return names.name("minecraft:item", item) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] equipment(int entity, int[] slots, byte[][] items) {
        ByteBuf output = Unpooled.buffer();
        Wire.writeVarInt(output, entity);
        for (int index = 0; index < slots.length; index++) {
            output.writeByte(slots[index] | (index < slots.length - 1 ? 128 : 0));
            output.writeBytes(items[index]);
        }
        return ByteBufUtil.getBytes(output);
    }

    @Test
    void equipmentEntriesEqualTheEagerEntriesAndFailAlike() {
        var fixtures = fixtures();
        var cache = new ItemStackCache();
        var values = new ModelInventoryValues(
                ProtocolCodecs.decoder(), VERSION, names, cache, names::generation);
        var observedCache = new ItemStackCache();
        var observed = new ModelInventoryValues(
                ProtocolCodecs.decoder(), VERSION, names, observedCache, names::generation);
        var packets = new ArrayList<byte[]>();
        for (int index = 0; index + 5 < fixtures.size(); index += 3) {
            packets.add(equipment(
                    300 + index,
                    new int[] {0, 1, 2, 3, 4, 5},
                    new byte[][] {
                        fixtures.get(index), fixtures.get(index + 1), fixtures.get(index + 2),
                        fixtures.get(index + 3), fixtures.get(index + 4), fixtures.get(index + 5)
                    }));
        }
        packets.add(equipment(7, new int[] {7}, new byte[][] {fixtures.get(0)}));
        var malformed = new ArrayList<byte[]>();
        for (byte[] packet : packets) {
            malformed.add(Arrays.copyOf(packet, packet.length - 1));
            malformed.add(Arrays.copyOf(packet, packet.length / 2));
        }
        malformed.add(equipment(9, new int[] {120}, new byte[][] {fixtures.get(0)}));
        var all = new ArrayList<>(packets);
        all.addAll(malformed);
        int read = 0;
        for (byte[] bytes : all) {
            Object expected, actual;
            try {
                expected = observedCache.observe(
                        () -> observed.read(Unpooled.wrappedBuffer(bytes), "minecraft:set_equipment"));
            } catch (RuntimeException e) {
                expected = e;
            }
            try {
                actual = values.read(Unpooled.wrappedBuffer(bytes), "minecraft:set_equipment");
            } catch (RuntimeException e) {
                actual = e;
            }
            if (expected instanceof RuntimeException failure) {
                assertEquals(failure.getClass(), actual.getClass());
                assertEquals(failure.getMessage(), ((RuntimeException) actual).getMessage());
                continue;
            }
            read++;
            var eagerPacket = (InventoryPackets.Equipment) expected;
            var laterPacket = (InventoryPackets.Equipment) actual;
            assertTrue(laterPacket.slots() instanceof InventoryPackets.SentEquipment);
            assertEquals(eagerPacket.entityId(), laterPacket.entityId());
            assertEquals(eagerPacket.slots().size(), laterPacket.slots().size());
            for (int index = laterPacket.slots().size() - 1; index >= 0; index--) {
                EquipmentSlot slot = InventoryPackets.Equipment.slot(laterPacket.slots(), index);
                assertEquals(eagerPacket.slots().get(index).slot(), slot);
                var entry = laterPacket.slots().get(index);
                assertSame(entry, laterPacket.slots().get(index));
                assertEquals(slot, entry.slot());
                assertSameStack(eagerPacket.slots().get(index).item(), entry.item());
            }
        }
        assertEquals(packets.size(), read);
        assertTrue(cache.held() > 0);
    }

    @Test
    void anObservingCacheReadsEquipmentAtDecode() {
        var cache = new ItemStackCache();
        var values = new ModelInventoryValues(ProtocolCodecs.decoder(), VERSION, names, cache, names::generation);
        byte[] bytes = equipment(5, new int[] {0}, new byte[][] {fixtures().get(0)});
        var packet = (InventoryPackets.Equipment)
                cache.observe(() -> values.read(Unpooled.wrappedBuffer(bytes), "minecraft:set_equipment"));
        if (packet.slots() instanceof InventoryPackets.SentEquipment) fail("observed equipment deferred");
        assertEquals(0, cache.held());
    }
}
