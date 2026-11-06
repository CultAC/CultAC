package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.management.ManagementFactory;
import java.util.SplittableRandom;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/** The mapper-taking reads against the public read then {@link PalettedSection#remap}, on global containers. */
class PalettedSectionGlobalRemapTest {
    private static final int[] BLOCK_REGISTRIES = {257, 300, 511, 512, 1000, 4096, 20_000, 30_000, 32_768, 70_000};
    private static final int[] BIOME_REGISTRIES = {2, 5, 8, 9, 16, 65, 100, 128, 300};
    private static final ProtocolVersion[] VERSIONS = {ProtocolVersion.V1_21_4, ProtocolVersion.V26_3};

    private static long sink;

    @Test
    void readAndRemapMatchesCopyRemapInValuesBytesMapperCallsAndFailures() {
        var random = new SplittableRandom(0x6C0BA1L);
        int sameWidth = 0, failures = 0;
        for (int round = 0; round < 6000; round++) {
            boolean blocks = random.nextInt(3) != 0;
            int size = blocks ? 4096 : 64, maximumLocalBits = blocks ? 8 : 3;
            int[] registries = blocks ? BLOCK_REGISTRIES : BIOME_REGISTRIES;
            int source = registries[random.nextInt(registries.length)];
            int sourceBits = width(source);
            int target = target(random, source, maximumLocalBits);
            var version = VERSIONS[random.nextInt(VERSIONS.length)];
            byte[] wire = globalWire(random, size, maximumLocalBits, source, version, round);
            int[] table = table(random, source, target, random.nextInt(4) == 0 ? 0.002 : 0);

            var expected = outcome(wire, blocks, version, source, table, target, false);
            var actual = outcome(wire, blocks, version, source, table, target, true);
            String context = "round " + round + " blocks=" + blocks + " " + version + " " + source + "->" + target;
            assertEquals(expected.error, actual.error, context);
            assertArrayEquals(expected.calls, actual.calls, context);
            assertArrayEquals(expected.bytes, actual.bytes, context);
            assertArrayEquals(expected.values, actual.values, context);
            assertEquals(expected.readerIndex, actual.readerIndex, context);
            if (width(target) == sourceBits && sourceBits > maximumLocalBits) sameWidth++;
            if (expected.error != null) failures++;
        }
        assertTrue(sameWidth > 1500, "same-width rounds " + sameWidth);
        assertTrue(failures > 200, "failing rounds " + failures);
    }

    @Test
    void remapNeverWritesTheSourceOrItsSharedCopy() {
        var random = new SplittableRandom(0x150L);
        for (int round = 0; round < 200; round++) {
            boolean blocks = round % 2 == 0;
            int source = blocks ? 30_000 : 100, size = blocks ? 4096 : 64;
            var version = VERSIONS[round % 2];
            byte[] wire = cleanGlobalWire(random, size, source, version);
            var section = read(Unpooled.wrappedBuffer(wire), blocks, version, source);
            var shared = section.copyOnWrite();
            byte[] before = written(section);
            int[] table = table(random, source, source, 0);
            var remapped = (round % 4 < 2 ? section : shared).remap(id -> table[id], source);
            assertArrayEquals(before, written(section), "round " + round);
            assertArrayEquals(before, written(shared), "round " + round);
            for (int i = 0; i < size; i++) assertEquals(table[section.get(i)], remapped.get(i));
        }
    }

    @Test
    void sameWidthGlobalReadAndRemapAllocatesOneStorage() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        var random = new SplittableRandom(0xA110CL);
        int source = 30_000, target = 29_000;
        byte[][] wires = new byte[16][];
        for (int i = 0; i < wires.length; i++) wires[i] = cleanGlobalWire(random, 4096, source, ProtocolVersion.V26_3);
        int[] table = table(random, source, target, 0);
        IntUnaryOperator mapper = id -> table[id];
        int operations = 2000;
        for (int warmup = 0; warmup < 5; warmup++) {
            copyRemap(wires, operations, source, mapper, target);
            readAndRemap(wires, operations, source, mapper, target);
        }
        long thread = Thread.currentThread().threadId();
        long start = bean.getThreadAllocatedBytes(thread);
        copyRemap(wires, operations, source, mapper, target);
        long copied = bean.getThreadAllocatedBytes(thread) - start;
        start = bean.getThreadAllocatedBytes(thread);
        readAndRemap(wires, operations, source, mapper, target);
        long remapped = bean.getThreadAllocatedBytes(thread) - start;
        double copiedPerRead = (double) copied / operations, remappedPerRead = (double) remapped / operations;
        assertTrue(
                remappedPerRead <= copiedPerRead * 0.7,
                "read-and-remap " + remappedPerRead + " B/read vs copy-remap " + copiedPerRead + " B/read");
    }

    private static void copyRemap(byte[][] wires, int operations, int source, IntUnaryOperator mapper, int target) {
        for (int i = 0; i < operations; i++) {
            var section = PalettedSection.readBlocks(
                            Unpooled.wrappedBuffer(wires[i % wires.length]), ProtocolVersion.V26_3, source)
                    .remap(mapper, target);
            sink += section.get(i & 4095);
        }
    }

    private static void readAndRemap(byte[][] wires, int operations, int source, IntUnaryOperator mapper, int target) {
        for (int i = 0; i < operations; i++) {
            var section = PalettedSection.readBlocks(
                    Unpooled.wrappedBuffer(wires[i % wires.length]), ProtocolVersion.V26_3, source, mapper, target);
            sink += section.get(i & 4095);
        }
    }

    private record Outcome(String error, int[] calls, byte[] bytes, int[] values, int readerIndex) {}

    private static Outcome outcome(
            byte[] wire,
            boolean blocks,
            ProtocolVersion version,
            int source,
            int[] table,
            int target,
            boolean readAndRemap) {
        var calls = new IntArrayList();
        IntUnaryOperator mapper = id -> {
            calls.add(id);
            return table[id];
        };
        ByteBuf input = Unpooled.wrappedBuffer(wire);
        try {
            PalettedSection result;
            if (readAndRemap)
                result = blocks
                        ? PalettedSection.readBlocks(input, version, source, mapper, target)
                        : PalettedSection.readBiomes(input, version, source, mapper, target);
            else result = read(input, blocks, version, source).remap(mapper, target);
            int[] values = new int[blocks ? 4096 : 64];
            for (int i = 0; i < values.length; i++) values[i] = result.get(i);
            return new Outcome(null, calls.toIntArray(), written(result), values, input.readerIndex());
        } catch (RuntimeException failure) {
            return new Outcome(
                    failure.getClass().getName() + ": " + failure.getMessage(),
                    calls.toIntArray(),
                    new byte[0],
                    new int[0],
                    input.readerIndex());
        }
    }

    private static PalettedSection read(ByteBuf input, boolean blocks, ProtocolVersion version, int source) {
        return blocks
                ? PalettedSection.readBlocks(input, version, source)
                : PalettedSection.readBiomes(input, version, source);
    }

    private static int width(int registrySize) {
        return 32 - Integer.numberOfLeadingZeros(registrySize - 1);
    }

    /** Mostly the source's width, otherwise a narrower, wider or local-sized registry. */
    private static int target(SplittableRandom random, int source, int maximumLocalBits) {
        int bits = width(source);
        switch (random.nextInt(8)) {
            case 0:
                return 1 + random.nextInt(1 << maximumLocalBits);
            case 1:
                return bits <= 1 ? source : 1 + random.nextInt(1 << (bits - 1));
            case 2:
                return (1 << bits) + 1 + random.nextInt(1 << bits);
            case 3:
                return source;
            default:
                return bits == 0 ? source : (1 << (bits - 1)) + 1 + random.nextInt(1 << (bits - 1));
        }
    }

    /** {@code source} IDs onto [0, target), a {@code bad} share of them out of range. */
    private static int[] table(SplittableRandom random, int source, int target, double bad) {
        int[] table = new int[source];
        for (int id = 0; id < source; id++) {
            table[id] = random.nextInt(target);
            if (random.nextDouble() < bad) table[id] = random.nextBoolean() ? -1 - random.nextInt(4) : target;
        }
        return table;
    }

    /**
     * A global container: random wire bits above the local range, garbage in word padding and the tail, codes past the
     * source registry in some rounds, and a mismatched legacy length prefix in others.
     */
    private static byte[] globalWire(
            SplittableRandom random, int size, int maximumLocalBits, int source, ProtocolVersion version, int round) {
        var wire = Unpooled.buffer();
        int[] global = {maximumLocalBits + 1, 15, 31, 127, -1, -128};
        wire.writeByte(global[random.nextInt(global.length)]);
        int bits = width(source);
        int perWord = bits == 0 ? 0 : 64 / bits;
        int length = bits == 0 ? 0 : (size + perWord - 1) / perWord;
        boolean malformed = random.nextInt(5) == 0 && Integer.bitCount(source) != 1;
        boolean garbage = random.nextBoolean();
        if (!version.atLeast(ProtocolVersion.V1_21_5)) {
            int written = random.nextInt(8) == 0 ? Math.max(0, length + random.nextInt(3) - 1) : length;
            Wire.writeVarInt(wire, written);
            length = written;
        }
        for (int word = 0, index = 0; word < length; word++) {
            long value = garbage ? random.nextLong() : 0;
            for (int slot = 0; slot < perWord; slot++, index++) {
                long mask = (1L << bits) - 1;
                if (index >= size && garbage) continue;
                int code = malformed && random.nextInt(round % 3 == 0 ? 50 : 2000) == 0
                        ? source + random.nextInt((1 << bits) - source)
                        : random.nextInt(source);
                value = value & ~(mask << (slot * bits)) | (long) code << (slot * bits);
            }
            wire.writeLong(value);
        }
        return bytes(wire);
    }

    static byte[] cleanGlobalWire(SplittableRandom random, int size, int source, ProtocolVersion version) {
        var wire = Unpooled.buffer();
        wire.writeByte(width(source));
        int bits = width(source), perWord = 64 / bits, length = (size + perWord - 1) / perWord;
        if (!version.atLeast(ProtocolVersion.V1_21_5)) Wire.writeVarInt(wire, length);
        for (int word = 0, index = 0; word < length; word++) {
            long value = 0;
            for (int slot = 0; slot < perWord && index < size; slot++, index++)
                value |= (long) random.nextInt(source) << (slot * bits);
            wire.writeLong(value);
        }
        return bytes(wire);
    }

    private static byte[] written(PalettedSection section) {
        ByteBuf output = Unpooled.buffer();
        section.write(output);
        return bytes(output);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }
}
