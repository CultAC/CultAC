package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.google.gson.JsonElement;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Recorded stacks decode alike with and without reuse; CULT_ITEM_BENCH names a file of {@code <protocol> <hex>}. */
@EnabledIfEnvironmentVariable(named = "CULT_ITEM_BENCH", matches = ".+")
class ItemStackReuseEquivalenceTest {
    private record Values(ModelItemValues plain, ModelItemValues cached, ItemStackCache cache) {}

    private record Outcome(SimItemStack stack, Class<?> failure, String message, int readerIndex) {}

    @Test
    void recordedStacksDecodeAlike() throws IOException {
        Map<Integer, Values> decoders = new HashMap<>();
        int stacks = 0, reused = 0, held = 0, failures = 0;
        for (String line : Files.readAllLines(Path.of(System.getenv("CULT_ITEM_BENCH")))) {
            int space = line.indexOf(' ');
            var values = decoders.computeIfAbsent(Integer.parseInt(line.substring(0, space)), protocol -> {
                var version = ProtocolVersion.of(protocol);
                var names = new WireRegistryState(version, () -> null);
                var cache = new ItemStackCache();
                return new Values(
                        new ModelItemValues(ProtocolCodecs.decoder(), version, names, ItemUtil.modelItems()),
                        new ModelItemValues(
                                ProtocolCodecs.decoder(),
                                version,
                                names,
                                ItemUtil.modelItems(),
                                cache,
                                names::generation),
                        cache);
            });
            byte[] bytes = HexFormat.of().parseHex(line.substring(space + 1));
            boolean wasHeld = values.cache().find(Unpooled.wrappedBuffer(bytes), afterCount(bytes)) >= 0;
            var expected = outcome(input -> values.plain().item(input, false), Unpooled.wrappedBuffer(bytes));
            var actual = outcome(values.cached()::sentItem, Unpooled.wrappedBuffer(bytes));
            assertAlike(values.plain(), expected, actual);
            if (expected.stack() == null) {
                failures++;
                continue;
            }
            stacks++;
            if (wasHeld) reused++;
            assertEquals(bytes.length, expected.readerIndex());
            boolean holdable = bytes.length - afterCount(bytes) <= ItemStackCache.MAX_BYTES
                    && !expected.stack().isEmpty()
                    && expected.stack().patch().added().bundle() == null;
            if (holdable) {
                int slot = values.cache().find(Unpooled.wrappedBuffer(bytes), afterCount(bytes));
                assertTrue(slot >= 0, line);
                assertEquals(bytes.length - afterCount(bytes), values.cache().length(slot), line);
                held++;
            }
            byte[] followed = Arrays.copyOf(bytes, bytes.length + 3);
            followed[bytes.length] = (byte) 0xff;
            assertAlike(
                    values.plain(),
                    outcome(input -> values.plain().item(input, false), Unpooled.wrappedBuffer(followed)),
                    outcome(values.cached()::sentItem, Unpooled.wrappedBuffer(followed)));
            for (byte[] count : COUNTS) {
                byte[] recounted = new byte[count.length + bytes.length - afterCount(bytes)];
                System.arraycopy(count, 0, recounted, 0, count.length);
                System.arraycopy(bytes, afterCount(bytes), recounted, count.length, bytes.length - afterCount(bytes));
                assertAlike(
                        values.plain(),
                        outcome(input -> values.plain().item(input, false), Unpooled.wrappedBuffer(recounted)),
                        outcome(values.cached()::sentItem, Unpooled.wrappedBuffer(recounted)));
            }
            actual.stack().setCount(99);
            actual.stack().components(Components.EMPTY);
            actual.stack().wirePatch(null);

            for (int length : new int[] {0, 1, bytes.length / 2, bytes.length - 1}) {
                if (length < 0 || length >= bytes.length) continue;
                assertAlike(
                        values.plain(),
                        outcome(input -> values.plain().item(input, false), Unpooled.wrappedBuffer(bytes, 0, length)),
                        outcome(values.cached()::sentItem, Unpooled.wrappedBuffer(bytes, 0, length)));
            }
            for (int index : new int[] {0, 1, bytes.length / 2, bytes.length - 1}) {
                if (index >= bytes.length) continue;
                byte[] corrupt = bytes.clone();
                corrupt[index] = (byte) 0xff;
                assertAlike(
                        values.plain(),
                        outcome(input -> values.plain().item(input, false), Unpooled.wrappedBuffer(corrupt)),
                        outcome(values.cached()::sentItem, Unpooled.wrappedBuffer(corrupt)));
            }
        }
        System.out.printf(
                "ITEM-REUSE stacks %d failures %d held %d reused %d (%.1f%%)%n",
                stacks, failures, held, reused, 100.0 * reused / stacks);
        assertTrue(reused > 0);
    }

    /** Counts 1, 64, 300, 0, -1, a non-minimal 1 and one with unused high bits in byte five. */
    private static final byte[][] COUNTS = {
        {1},
        {64},
        {(byte) 0xac, 0x02},
        {0},
        {-1, -1, -1, -1, 0x0f},
        {(byte) 0x81, 0},
        {(byte) 0x81, -128, -128, -128, 0x70}
    };

    private static int afterCount(byte[] bytes) {
        int index = 0;
        while (index < bytes.length && (bytes[index++] & 0x80) != 0) {}
        return index;
    }

    private static Outcome outcome(Function<ByteBuf, SimItemStack> decode, ByteBuf input) {
        try {
            var stack = decode.apply(input);
            return new Outcome(stack, null, null, input.readerIndex());
        } catch (RuntimeException e) {
            return new Outcome(null, e.getClass(), e.getMessage(), input.readerIndex());
        }
    }

    private static void assertAlike(ModelItemValues writer, Outcome expected, Outcome actual) {
        assertEquals(expected.failure(), actual.failure());
        assertEquals(expected.message(), actual.message());
        assertEquals(expected.readerIndex(), actual.readerIndex());
        if (expected.stack() == null) return;
        assertSame(expected.stack().definition(), actual.stack().definition());
        assertSame(expected.stack().capturedPrototype(), actual.stack().capturedPrototype());
        assertDeepEquals(expected.stack(), actual.stack(), "stack");
        assertArrayEquals(write(writer, expected.stack()), write(writer, actual.stack()));
    }

    private static byte[] write(ModelItemValues writer, SimItemStack stack) {
        ByteBuf output = Unpooled.buffer();
        writer.write(output, stack);
        return ByteBufUtil.getBytes(output);
    }

    /** Field by field, skipping lambdas and the memo of already compared encodings. */
    private static void assertDeepEquals(Object expected, Object actual, String path) {
        if (expected == actual) return;
        assertTrue(expected != null && actual != null, path);
        assertEquals(expected.getClass(), actual.getClass(), path);
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            assertEquals(Array.getLength(expected), Array.getLength(actual), path);
            for (int index = 0; index < Array.getLength(expected); index++)
                assertDeepEquals(Array.get(expected, index), Array.get(actual, index), path + "[" + index + "]");
        } else if (expected instanceof Map<?, ?> map) {
            var other = (Map<?, ?>) actual;
            assertEquals(map.keySet(), other.keySet(), path);
            for (var key : map.keySet()) assertDeepEquals(map.get(key), other.get(key), path + "." + key);
        } else if (expected instanceof Set<?>
                || expected instanceof JsonElement
                || type.getName().startsWith("java.")) {
            assertEquals(expected, actual, path);
        } else if (expected instanceof Collection<?> list) {
            Iterator<?> left = list.iterator(), right = ((Collection<?>) actual).iterator();
            int index = 0;
            while (left.hasNext()) {
                assertTrue(right.hasNext(), path);
                assertDeepEquals(left.next(), right.next(), path + "[" + index++ + "]");
            }
            assertTrue(!right.hasNext(), path);
        } else if (type.isSynthetic() || type.isHidden()) {
            return;
        } else {
            for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass())
                for (Field field : level.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.getName().equals("encodedComplete")) continue;
                    field.setAccessible(true);
                    try {
                        assertDeepEquals(field.get(expected), field.get(actual), path + "." + field.getName());
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(path, e);
                    }
                }
        }
    }
}
