package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DeferredMetadataItemTest {
    private static final ProtocolVersion VERSION = ProtocolVersion.of(774);
    private static final ItemRegistry ITEMS = new ItemRegistry(DataTables.defaults());
    /** EntityDataSerializers.ITEM_STACK. */
    private static final int ITEM_SERIALIZER = 7;

    private final WireRegistryState names = SentStackDeferralTest.registries();
    private final ModelItemValues plain =
            new ModelItemValues(ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems());

    private byte[] bytes(SimItemStack stack) {
        ByteBuf output = Unpooled.buffer();
        plain.write(output, stack);
        return ByteBufUtil.getBytes(output);
    }

    private List<byte[]> stacks() {
        var stacks = new ArrayList<byte[]>();
        for (var stack : SentStackDeferralTest.stacks()) {
            stacks.add(bytes(stack));
            stacks.add(bytes(stack.copyWithCount(stack.getMaxStackSize() > 1 ? 9 : 3)));
        }
        var map = ItemUtil.modelItems().stack("minecraft:filled_map", 1);
        map.setComponent("minecraft:map_id", CanonicalSnbt.parse("42"));
        stacks.add(bytes(map));
        stacks.add(bytes(SimItemStack.EMPTY));
        return stacks;
    }

    private WireValueDecoder.DecodedItem<NbtValue> decoded(byte[] bytes) {
        return ProtocolCodecs.decoder()
                .item(VERSION, Unpooled.wrappedBuffer(bytes), false, names, ModelNbtFactory.INSTANCE);
    }

    private ModelItemValues values() {
        return new ModelItemValues(ProtocolCodecs.decoder(), VERSION, names, ITEMS);
    }

    private static Object outcome(Supplier<Object> run) {
        try {
            return run.get();
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private void assertSameStack(SimItemStack expected, SimItemStack actual) {
        assertEquals(expected.itemKey(), actual.itemKey());
        assertEquals(expected.count(), actual.count());
        assertEquals(expected.isEmpty(), actual.isEmpty());
        assertEquals(expected.components(), actual.components());
        assertEquals(List.copyOf(expected.components().keys()), List.copyOf(actual.components().keys()));
        assertEquals(expected.components().json(), actual.components().json());
        assertEquals(expected.patch(), actual.patch());
        assertEquals(expected.prototype(), actual.prototype());
        assertEquals(expected.wirePatch() == null, actual.wirePatch() == null);
        if (expected.wirePatch() != null) assertArrayEquals(expected.wirePatch().bytes(), actual.wirePatch().bytes());
        assertTrue(expected.sameItemSameComponents(actual));
        assertArrayEquals(bytes(expected), bytes(actual));
    }

    private static SimItemStack read(Object value) {
        return value instanceof EntityMetadata.Deferred deferred ? (SimItemStack) deferred.get() : (SimItemStack) value;
    }

    @Test
    void deferredStacksEqualTheEagerConversion() {
        int deferred = 0;
        for (byte[] bytes : stacks()) {
            var value = decoded(bytes);
            var eager = values().item(value);
            var later = values().itemOnRead(value);
            if (later instanceof EntityMetadata.Deferred) deferred++;
            assertEquals(ModelItemValues.Pending.defers(value), later instanceof EntityMetadata.Deferred);
            var first = read(later);
            assertSameStack(eager, first);
            assertSame(first, read(later));
        }
        assertTrue(deferred >= 10, "deferred " + deferred);
    }

    @Test
    void unresolvableStacksFailAtDecode() {
        for (byte[] bytes : stacks()) {
            if (bytes.length < 2) continue;
            for (int id : new int[] {-1, 0, 1, 1200, 1500, 30000}) {
                var value = decoded(bytes);
                var changed = new WireValueDecoder.DecodedItem<>(
                        id, value.count(), value.components(), value.componentLayouts(), value.componentEncodings(),
                        value.wirePatch());
                var eager = outcome(() -> values().item(changed));
                var later = outcome(() -> values().itemOnRead(changed));
                if (eager instanceof SimItemStack stack) assertSameStack(stack, read(later));
                else assertEquals(eager, later);
            }
        }
        var signed = new WireValueDecoder.DecodedItem<NbtValue>(
                1, 1, new NbtValue.Compound(Map.of("minecraft:damage", new NbtValue.Numeric(NbtValue.Kind.INT, null))),
                null, Map.of(), null);
        assertEquals(outcome(() -> values().item(signed)), outcome(() -> values().itemOnRead(signed)));
    }

    @Test
    void racingReadersGetOneStack() throws Exception {
        var stacks = stacks();
        for (int round = 0; round < 300; round++) {
            var value = decoded(stacks.get(round % stacks.size()));
            var later = values().itemOnRead(value);
            if (!(later instanceof EntityMetadata.Deferred deferred)) continue;
            var seen = new AtomicReferenceArray<Object>(4);
            var start = new CountDownLatch(1);
            var threads = new ArrayList<Thread>();
            for (int thread = 0; thread < 4; thread++) {
                int slot = thread;
                var reader = new Thread(() -> {
                    try {
                        start.await();
                        seen.set(slot, deferred.get());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                reader.start();
                threads.add(reader);
            }
            start.countDown();
            for (var thread : threads) thread.join();
            for (int slot = 1; slot < 4; slot++) assertSame(seen.get(0), seen.get(slot));
            assertSame(seen.get(0), deferred.get());
        }
    }

    /** A set_entity_data body: a byte at index 0, an int at index 1, then an item entry at index 8. */
    private static byte[] metadata(int entity, byte[] item) {
        ByteBuf output = Unpooled.buffer();
        Wire.writeVarInt(output, entity);
        output.writeByte(0);
        Wire.writeVarInt(output, 0);
        output.writeByte(3);
        output.writeByte(1);
        Wire.writeVarInt(output, 1);
        Wire.writeVarInt(output, 300);
        output.writeByte(8);
        Wire.writeVarInt(output, ITEM_SERIALIZER);
        output.writeBytes(item);
        output.writeByte(255);
        return ByteBufUtil.getBytes(output);
    }

    @Test
    void metadataEntriesReadTheConvertedStack() {
        var reader = new ModelEntityMetadataValues(ProtocolCodecs.decoder(), VERSION, names, name -> null);
        for (byte[] bytes : stacks()) {
            var packet = reader.read(Unpooled.wrappedBuffer(metadata(5, bytes)));
            var entry = packet.packedItems().get(2);
            assertEquals(8, entry.id());
            var stack = assertInstanceOf(SimItemStack.class, entry.value());
            var value = decoded(bytes);
            var unpatched = new WireValueDecoder.DecodedItem<>(
                    value.id(), value.count(), value.components(), value.componentLayouts(),
                    value.componentEncodings(), null);
            assertSameStack(values().item(unpatched), stack);
            assertSame(stack, entry.value());
            var entries = (EntityMetadata.Entries) packet.packedItems();
            assertSame(stack, entries.value(8));
            assertSame(stack, entries.entry(8).value());
            assertEquals((byte) 3, entries.value(0));
            assertEquals(300, entries.value(1));
            assertSame(entry, packet.packedItems().get(2));
            assertTrue(entry.toString().contains("value=" + stack + ", bytes="));
            var again = reader.read(Unpooled.wrappedBuffer(metadata(5, bytes)));
            assertEquals(packet.packedItems().get(0), again.packedItems().get(0));
            assertEquals(stack.isEmpty() || stack.sameItemSameComponents((SimItemStack) again.packedItems().get(2).value()), true);
        }
    }
}
