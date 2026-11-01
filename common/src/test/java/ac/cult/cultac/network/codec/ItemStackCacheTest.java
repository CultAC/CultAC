package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BundleContents;
import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.ItemTemplate;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ItemStackCacheTest {
    private static final ProtocolVersion VERSION = ProtocolVersion.of(774);

    private final WireRegistryState names = new WireRegistryState(VERSION, () -> null);
    private final ModelItemValues plain =
            new ModelItemValues(ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems());

    private ModelItemValues cached(ItemStackCache cache, AtomicReference<Object> generation) {
        return new ModelItemValues(
                ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems(), cache, generation::get);
    }

    private ModelItemValues cached() {
        return new ModelItemValues(
                ProtocolCodecs.decoder(),
                VERSION,
                names,
                ItemUtil.modelItems(),
                new ItemStackCache(),
                names::generation);
    }

    private byte[] sword() {
        var stack = ItemUtil.modelItems().stack("minecraft:diamond_sword", 1);
        stack.components(stack.components().with("minecraft:damage", new JsonPrimitive(5)));
        return bytes(stack);
    }

    private byte[] bytes(SimItemStack stack) {
        ByteBuf output = Unpooled.buffer();
        plain.write(output, stack);
        return ByteBufUtil.getBytes(output);
    }

    private static SimItemStack read(ModelItemValues values, byte[] bytes) {
        var input = Unpooled.wrappedBuffer(bytes);
        var stack = values.sentItem(input);
        assertEquals(bytes.length, input.readerIndex());
        return stack;
    }

    private void assertSameStack(SimItemStack expected, SimItemStack actual) {
        assertEquals(expected.itemKey(), actual.itemKey());
        assertEquals(expected.count(), actual.count());
        assertEquals(expected.components(), actual.components());
        assertEquals(expected.patch(), actual.patch());
        assertArrayEquals(bytes(expected), bytes(actual));
    }

    @Test
    void repeatedStacksAreSeparateCopies() {
        var values = cached();
        byte[] bytes = sword();
        var first = read(values, bytes);
        var second = read(values, bytes);
        assertNotSame(first, second);
        assertSame(first.patch(), second.patch());
        assertSameStack(plain.item(Unpooled.wrappedBuffer(bytes), false), second);
    }

    @Test
    void changingAHandedOutStackLeavesTheNextOneIntact() {
        var values = cached();
        byte[] bytes = sword();
        var expected = plain.item(Unpooled.wrappedBuffer(bytes), false);
        for (var stack : List.of(read(values, bytes), read(values, bytes))) {
            stack.setCount(40);
            stack.components(Components.EMPTY);
            stack.wirePatch(null);
        }
        assertSameStack(expected, read(values, bytes));
    }

    @Test
    void registryDataDecodesAgain() {
        var values = cached();
        byte[] bytes = sword();
        var before = read(values, bytes);
        var generation = names.generation();
        names.append(new WireValueDecoder.RegistryValues(
                "minecraft:enchantment", List.of(new WireValueDecoder.RegistryEntry("minecraft:sharpness", null))));
        assertNotSame(generation, names.generation());
        var after = read(values, bytes);
        assertNotSame(before.patch(), after.patch());
        assertSameStack(before, after);
    }

    @Test
    void anotherGenerationDropsHeldStacks() {
        var cache = new ItemStackCache();
        var first = new Object();
        var generation = new AtomicReference<>(first);
        var values = cached(cache, generation);
        byte[] bytes = sword();
        var before = read(values, bytes);
        generation.set(new Object());
        var other = read(values, bytes);
        generation.set(first);
        var after = read(values, bytes);
        assertNotSame(before.patch(), other.patch());
        assertNotSame(before.patch(), after.patch());
        assertNotSame(other.patch(), after.patch());
        assertSameStack(before, after);
    }

    @Test
    void bundlesAreDecodedEveryTime() {
        var bundle = ItemUtil.modelItems().stack("minecraft:bundle", 1);
        bundle.bundleContents(
                new BundleContents(List.of(new ItemTemplate("minecraft:stone", 2, ComponentPatch.EMPTY))));
        byte[] bytes = bytes(bundle);
        var values = cached();
        var first = read(values, bytes);
        var second = read(values, bytes);
        assertNotSame(first.patch().added().bundle(), second.patch().added().bundle());
        assertSameStack(first, second);
    }

    @Test
    void truncatedStacksFailAsWithoutReuse() {
        var values = cached();
        byte[] bytes = sword();
        read(values, bytes);
        for (int length = 0; length < bytes.length; length++) {
            var expectedInput = Unpooled.wrappedBuffer(bytes, 0, length);
            var expected = assertThrows(RuntimeException.class, () -> plain.item(expectedInput, false));
            var actualInput = Unpooled.wrappedBuffer(bytes, 0, length);
            var actual = assertThrows(RuntimeException.class, () -> values.sentItem(actualInput));
            assertEquals(expected.getClass(), actual.getClass());
            assertEquals(expected.getMessage(), actual.getMessage());
            assertEquals(expectedInput.readerIndex(), actualInput.readerIndex());
        }
    }

    /** A box holding {@code nested} damaged swords, the first of them a stack of {@code count}. */
    private byte[] box(int count, int nested) {
        byte[] sword = sword();
        ByteBuf output = Unpooled.buffer();
        Wire.writeVarInt(output, 1);
        Wire.writeVarInt(output, names.id("minecraft:item", "minecraft:shulker_box"));
        Wire.writeVarInt(output, 1);
        Wire.writeVarInt(output, 0);
        Wire.writeVarInt(output, names.id("minecraft:data_component_type", "minecraft:container"));
        Wire.writeVarInt(output, nested);
        Wire.writeVarInt(output, count);
        output.writeBytes(sword, 1, sword.length - 1);
        for (int index = 1; index < nested; index++) output.writeBytes(sword);
        return ByteBufUtil.getBytes(output);
    }

    @Test
    void heldStacksStayWithinTheBudget() {
        int nested = 1;
        while (box(ItemStackCache.ENTRIES * 2, nested + 1).length <= ItemStackCache.MAX_BYTES) nested++;
        byte[] first = box(1, nested);
        var decoded = ProtocolCodecs.decoder()
                .item(VERSION, Unpooled.wrappedBuffer(first), false, names, ModelNbtFactory.INSTANCE);
        int size = ItemStackCache.retained(first.length, decoded.components(), decoded.componentLayouts());
        assertTrue(size > ItemStackCache.BUDGET / ItemStackCache.ENTRIES);

        var cache = new ItemStackCache();
        var values = cached(cache, new AtomicReference<>(new Object()));
        for (int count = 1; count < ItemStackCache.ENTRIES * 2; count++) {
            byte[] bytes = box(count, nested);
            var held = read(values, bytes);
            assertTrue(cache.held() <= ItemStackCache.BUDGET);
            assertSame(held.patch(), read(values, bytes).patch());
        }
        assertEquals(ItemStackCache.BUDGET / size * size, cache.held());
        assertNull(cache.get(Unpooled.wrappedBuffer(first), 0, first.length));
    }

    @Test
    void theLeastRecentlyUsedStacksMakeRoom() {
        var cache = new ItemStackCache();
        var stack = plain.item(Unpooled.wrappedBuffer(sword()), false);
        byte[][] keys = {{1}, {2}, {3}};
        int half = ItemStackCache.BUDGET / 2;
        cache.put(Unpooled.wrappedBuffer(keys[0]), 0, 1, stack, half);
        cache.put(Unpooled.wrappedBuffer(keys[1]), 0, 1, stack, half);
        cache.get(Unpooled.wrappedBuffer(keys[0]), 0, 1);
        cache.put(Unpooled.wrappedBuffer(keys[2]), 0, 1, stack, half);
        assertSame(stack, cache.get(Unpooled.wrappedBuffer(keys[0]), 0, 1));
        assertNull(cache.get(Unpooled.wrappedBuffer(keys[1]), 0, 1));
        assertSame(stack, cache.get(Unpooled.wrappedBuffer(keys[2]), 0, 1));
        assertEquals(half * 2, cache.held());

        cache.put(Unpooled.wrappedBuffer(keys[1]), 0, 1, stack, ItemStackCache.BUDGET + 1);
        assertNull(cache.get(Unpooled.wrappedBuffer(keys[1]), 0, 1));
        assertEquals(half * 2, cache.held());
    }
}
