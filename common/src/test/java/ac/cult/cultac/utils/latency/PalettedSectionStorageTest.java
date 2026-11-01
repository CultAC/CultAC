package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PalettedSectionStorageTest {
    private static final int REGISTRY = 30_000;

    @Test
    void writesWithoutAnIndexBuiltOnReadMatchWritesWithIt() throws Exception {
        Method index = PalettedSection.class.getDeclaredMethod("indexPalette");
        index.setAccessible(true);
        var random = new SplittableRandom(0x1DE7L);
        int indexed = 0, failed = 0;
        for (int round = 0; round < 3000; round++) {
            boolean blocks = random.nextInt(4) != 0;
            byte[] wire = section(random, blocks, ProtocolVersion.V26_3, round % 9 == 0);
            var lazy = read(wire, blocks, ProtocolVersion.V26_3);
            var eager = read(wire, blocks, ProtocolVersion.V26_3);
            index.invoke(eager);
            if (round % 3 == 1) {
                lazy = lazy.copy();
                eager = eager.copy();
                index.invoke(eager);
            }
            if (blocks && written(eager).length > 0 && (written(eager)[0] & 0xFF) > 4) indexed++;
            long seed = random.nextLong();
            var expected = writes(eager, blocks ? 4096 : 64, new SplittableRandom(seed));
            var actual = writes(lazy, blocks ? 4096 : 64, new SplittableRandom(seed));
            assertEquals(expected, actual, "round " + round);
            if (expected.getLast().startsWith("java.")) failed++;
        }
        assertTrue(indexed > 500, "indexed " + indexed);
        assertTrue(failed > 50, "failed " + failed);
    }

    @Test
    void remappingTheReadStorageMatchesRemappingACopy() {
        var random = new SplittableRandom(0x8E3AL);
        int remapped = 0, repacked = 0, failed = 0;
        for (int round = 0; round < 6000; round++) {
            boolean blocks = random.nextBoolean();
            var version = round % 3 == 0 ? ProtocolVersion.V1_21_4 : ProtocolVersion.V26_3;
            byte[] wire = section(random, blocks, version, round % 11 == 0);
            int target = round % 13 == 0 ? 2 + random.nextInt(blocks ? 200 : 6) : REGISTRY - random.nextInt(100);
            int offset = random.nextInt(REGISTRY), failing = round % 5 == 0 ? random.nextInt(REGISTRY) : -1;
            IntUnaryOperator mapper = id -> {
                if (id == failing) throw new IllegalStateException("Unmapped " + id);
                return id % 4999 == 0 ? target + id % 3 : (id + offset) % target;
            };
            var copied = Unpooled.wrappedBuffer(wire);
            var reused = Unpooled.wrappedBuffer(wire);
            String expected = outcome(() -> blocks
                    ? PalettedSection.readBlocks(copied, version, REGISTRY).remap(mapper, target)
                    : PalettedSection.readBiomes(copied, version, REGISTRY).remap(mapper, target));
            String actual = outcome(() -> blocks
                    ? PalettedSection.readBlocks(reused, version, REGISTRY, mapper, target)
                    : PalettedSection.readBiomes(reused, version, REGISTRY, mapper, target));
            assertEquals(expected, actual, "round " + round);
            assertEquals(copied.readerIndex(), reused.readerIndex(), "round " + round);
            if (expected.startsWith("java.")) failed++;
            else if (target < 256) repacked++;
            else remapped++;
        }
        assertTrue(remapped > 4000, "remapped " + remapped);
        assertTrue(repacked > 250, "repacked " + repacked);
        assertTrue(failed > 150, "failed " + failed);
    }

    @Test
    void copyOnWriteMatchesCopyAndNeverChangesTheOtherSection() {
        var random = new SplittableRandom(0xC0E3L);
        for (int round = 0; round < 3000; round++) {
            boolean blocks = random.nextInt(4) != 0;
            int size = blocks ? 4096 : 64;
            byte[] wire = section(random, blocks, ProtocolVersion.V26_3, round % 9 == 0);
            var source = read(wire, blocks, ProtocolVersion.V26_3);
            byte[] before = written(source);
            var shared = source.copyOnWrite();
            var copied = source.copy();
            var chained = round % 4 == 0 ? shared.copyOnWrite() : null;
            assertArrayEquals(written(copied), written(shared), "round " + round);
            long seed = random.nextLong();
            var expected = writes(copied, size, new SplittableRandom(seed));
            assertEquals(expected, writes(shared, size, new SplittableRandom(seed)), "round " + round);
            assertArrayEquals(before, written(source), "round " + round);
            if (chained != null) assertArrayEquals(before, written(chained), "round " + round);
            byte[] afterShared = written(shared);
            var reference = source.copy();
            long sourceSeed = random.nextLong();
            assertEquals(
                    writes(reference, size, new SplittableRandom(sourceSeed)),
                    writes(source, size, new SplittableRandom(sourceSeed)),
                    "round " + round);
            assertArrayEquals(afterShared, written(shared), "round " + round);
            if (chained != null) assertArrayEquals(before, written(chained), "round " + round);
        }
    }

    private static PalettedSection read(byte[] wire, boolean blocks, ProtocolVersion version) {
        var input = Unpooled.wrappedBuffer(wire);
        return blocks
                ? PalettedSection.readBlocks(input, version, REGISTRY)
                : PalettedSection.readBiomes(input, version, REGISTRY);
    }

    /** Every getAndSet result, then the written section or the first failure. */
    private static List<String> writes(PalettedSection section, int size, SplittableRandom random) {
        var results = new ArrayList<String>();
        int states = 1 + random.nextInt(size == 4096 ? 400 : 12);
        int base = random.nextInt(REGISTRY - states);
        int count = 1 + random.nextInt(size == 4096 ? 1500 : 64);
        try {
            for (int i = 0; i < count; i++) {
                int index = random.nextInt(size);
                int state = random.nextInt(4) == 0 ? section.get(random.nextInt(size)) : base + random.nextInt(states);
                results.add(Integer.toString(section.getAndSet(index, state)));
            }
            results.add(hex(written(section)));
        } catch (RuntimeException failure) {
            results.add(failure.getClass().getName() + ": " + failure.getMessage());
        }
        return results;
    }

    private static String outcome(Supplier<PalettedSection> read) {
        try {
            var section = read.get();
            var values = new StringBuilder(hex(written(section)));
            for (int i = 0; i < 64; i++) values.append(',').append(section.get(i));
            return values.toString();
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    /** A paletted container with random width, palette (duplicates included) and stored codes. */
    private static byte[] section(SplittableRandom random, boolean blocks, ProtocolVersion version, boolean malformed) {
        int size = blocks ? 4096 : 64, minimumBits = blocks ? 4 : 1, maximumLocalBits = blocks ? 8 : 3;
        int wireBits = random.nextInt(maximumLocalBits + 2);
        boolean global = wireBits > maximumLocalBits;
        int bits = wireBits == 0 ? 0 : global ? 15 : Math.max(minimumBits, wireBits);
        int paletteSize = bits == 0 || global ? 1 : 1 + random.nextInt(1 << bits);
        int distinct = 1 + random.nextInt(Math.min(paletteSize, 40));
        ByteBuf output = Unpooled.buffer();
        try {
            output.writeByte(wireBits);
            int[] palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++)
                palette[i] = i > 0 && random.nextInt(8) == 0 ? palette[random.nextInt(i)] : random.nextInt(REGISTRY);
            if (!global) {
                if (bits == 0) Wire.writeVarInt(output, palette[0]);
                else {
                    Wire.writeVarInt(output, paletteSize);
                    for (int state : palette) Wire.writeVarInt(output, state);
                }
            }
            int perWord = bits == 0 ? 1 : 64 / bits;
            int words = bits == 0 ? 0 : (size + perWord - 1) / perWord;
            if (!version.atLeast(ProtocolVersion.V1_21_5)) Wire.writeVarInt(output, words);
            int range = global ? REGISTRY : malformed ? 1 << bits : Math.min(paletteSize, 1 << bits);
            int[] codes = new int[distinct];
            for (int i = 0; i < distinct; i++) codes[i] = random.nextInt(Math.max(1, range));
            for (int word = 0, index = 0; word < words; word++) {
                long value = 0;
                for (int slot = 0; slot < perWord && index < size; slot++, index++)
                    value |= (long) codes[random.nextInt(distinct)] << (slot * bits);
                output.writeLong(value);
            }
            byte[] bytes = new byte[output.readableBytes()];
            output.readBytes(bytes);
            return bytes;
        } finally {
            output.release();
        }
    }

    private static byte[] written(PalettedSection section) {
        ByteBuf output = Unpooled.buffer();
        try {
            section.write(output);
            byte[] bytes = new byte[output.readableBytes()];
            output.readBytes(bytes);
            return bytes;
        } finally {
            output.release();
        }
    }

    private static String hex(byte[] bytes) {
        var text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
            text.append(Character.forDigit(value >> 4 & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }
}
