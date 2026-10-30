package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** A block container read against a section pool reads exactly as the full read does, on any input. */
class SectionPoolWireMatchTest {
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int SOURCE = 30_000;
    private static final ProtocolVersion[] VERSIONS = {
        ProtocolVersion.V1_21_4, ProtocolVersion.V1_21_5, ProtocolVersion.V26_1, ProtocolVersion.V26_3
    };

    @Test
    void aPooledReadMatchesTheFullReadOnEveryInput() {
        var random = new SplittableRandom(0x5EC7L);
        int shared = 0, missed = 0, failed = 0;
        for (int round = 0; round < 6000; round++) {
            var version = VERSIONS[random.nextInt(VERSIONS.length)];
            int target = round % 17 == 0 ? 2 + random.nextInt(300) : STATES - random.nextInt(50);
            int offset = random.nextInt(SOURCE), failing = round % 7 == 0 ? random.nextInt(SOURCE) : -1;
            IntUnaryOperator mapper = id -> {
                if (id == failing) throw new IllegalStateException("Unmapped " + id);
                return id % 4999 == 0 ? target + id % 3 : (id + offset) % target;
            };
            byte[] pooledWire = section(random, version, round % 9 == 0);
            var pool = new SectionPool();
            var candidate = pooled(pool, pooledWire, version, mapper, target);
            byte[] wire = variant(random, pooledWire, version, round);
            byte[] before = candidate == null ? null : written(candidate);
            List<ByteBuf> fulls = buffers(wire), pooleds = buffers(wire);
            for (int kind = 0; kind < fulls.size(); kind++) {
                ByteBuf full = fulls.get(kind), input = pooleds.get(kind);
                try {
                    String expected = outcome(() -> PalettedSection.readBlocks(full, version, SOURCE, mapper, target));
                    boolean hit = candidate != null && matches(pool, input.duplicate(), version, mapper, target);
                    String actual =
                            outcome(() -> PalettedSection.readBlocks(input, version, SOURCE, mapper, target, pool));
                    assertEquals(expected, actual, "round " + round);
                    assertEquals(full.readerIndex(), input.readerIndex(), "round " + round);
                    if (hit) shared++;
                    else if (expected.startsWith("java.") || expected.startsWith("ac.")) failed++;
                    else missed++;
                } finally {
                    full.release();
                    input.release();
                }
            }
            if (candidate != null) assertArrayEquals(before, written(candidate), "round " + round);
        }
        assertTrue(shared > 2500, "shared " + shared);
        assertTrue(missed > 2500, "missed " + missed);
        assertTrue(failed > 500, "failed " + failed);
    }

    @Test
    void aSharedReadWritesLikeAFullReadAndNeverChangesThePooledSection() {
        var random = new SplittableRandom(0xC0DEL);
        int shared = 0;
        for (int round = 0; round < 2000; round++) {
            var pool = new SectionPool();
            byte[] wire = section(random, ProtocolVersion.V26_3, false);
            var candidate = pooled(pool, wire, ProtocolVersion.V26_3, IntUnaryOperator.identity(), STATES);
            if (candidate == null) continue;
            if (round % 2 == 0) {
                // A reinterned section: grown in place, then sent as the model writes it.
                var grown = candidate.copy();
                for (int i = 0; i < 1 + random.nextInt(40); i++)
                    grown.getAndSet(random.nextInt(4096), random.nextInt(STATES));
                pool = new SectionPool();
                candidate = grown;
                var section = new CachedSection(grown);
                if (section.isEmpty() || grown.isUniform()) continue;
                pool.internRetained(section);
                wire = written(grown);
            }
            byte[] before = written(candidate);
            var full = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire));
            var input = Unpooled.wrappedBuffer(wire);
            var read = PalettedSection.readBlocks(input, pool);
            assertEquals(wire.length, input.readerIndex(), "round " + round);
            if (matches(pool, Unpooled.wrappedBuffer(wire), ProtocolVersion.V26_3, IntUnaryOperator.identity(), STATES))
                shared++;
            long seed = random.nextLong();
            assertEquals(
                    writes(full, new SplittableRandom(seed)),
                    writes(read, new SplittableRandom(seed)),
                    "round " + round);
            assertArrayEquals(before, written(candidate), "round " + round);
            var again = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), pool);
            assertArrayEquals(written(PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire))), written(again));
        }
        assertTrue(shared > 600, "shared " + shared);
    }

    @Test
    void aReadAgainstSeveralCandidatesWithTheSamePaletteMatchesTheFullRead() {
        var random = new SplittableRandom(0x9A11L);
        IntUnaryOperator mapper = id -> id % STATES;
        int later = 0, missed = 0;
        for (int round = 0; round < 1500; round++) {
            var version = VERSIONS[random.nextInt(VERSIONS.length)];
            byte[] wire = section(random, version, false);
            int wordBytes = wordBytes(wire);
            if (wordBytes == 0) continue;
            var candidates = new ArrayList<PalettedSection>();
            var candidateWires = new ArrayList<byte[]>();
            for (int i = 0, count = 2 + random.nextInt(8); i < count; i++) {
                byte[] changed = wire.clone();
                int word = wire.length - wordBytes + random.nextInt(wordBytes / Long.BYTES) * Long.BYTES;
                changed[word + random.nextInt(Long.BYTES)] ^= (byte) (1 << random.nextInt(8));
                var states = decoded(changed, version, mapper);
                if (states == null) continue;
                candidates.add(states);
                candidateWires.add(changed);
            }
            if (candidates.size() < 2) continue;
            int matching = random.nextInt(candidates.size() + 1);
            byte[] input = matching == candidates.size() ? wire : candidateWires.get(matching);
            var pool = new SectionPool();
            injected(pool, candidates);
            var before = new ArrayList<byte[]>();
            for (var candidate : candidates) before.add(written(candidate));
            List<ByteBuf> fulls = buffers(input), pooleds = buffers(input);
            for (int kind = 0; kind < fulls.size(); kind++) {
                ByteBuf full = fulls.get(kind), pooled = pooleds.get(kind);
                try {
                    String expected = outcome(() -> PalettedSection.readBlocks(full, version, SOURCE, mapper, STATES));
                    if (matching > 0 && matches(pool, pooled.duplicate(), version, mapper, STATES)) later++;
                    String actual =
                            outcome(() -> PalettedSection.readBlocks(pooled, version, SOURCE, mapper, STATES, pool));
                    assertEquals(expected, actual, "round " + round);
                    assertEquals(full.readerIndex(), pooled.readerIndex(), "round " + round);
                } finally {
                    full.release();
                    pooled.release();
                }
            }
            for (int i = 0; i < candidates.size(); i++)
                assertArrayEquals(before.get(i), written(candidates.get(i)), "round " + round);
            if (matching == candidates.size()) missed++;
        }
        assertTrue(later > 300, "later " + later);
        assertTrue(missed > 100, "missed " + missed);
    }

    @Test
    void wireReadsCountOffersHitsCandidatesAndMultiValueFullReads() {
        var random = new SplittableRandom(0x57A7L);
        var pool = new SectionPool();
        var pooled = interned(random, pool, false);
        byte[] other;
        do other = section(random, ProtocolVersion.V26_3, false);
        while (other[0] == 0 || decoded(other, ProtocolVersion.V26_3, IntUnaryOperator.identity()) == null);
        byte[] single = {0, 1};
        var start = counters();

        PalettedSection.readBlocks(Unpooled.wrappedBuffer(pooled.wire()), pool);
        PalettedSection.readBlocks(Unpooled.wrappedBuffer(pooled.wire()), pool);
        PalettedSection.readBlocks(Unpooled.wrappedBuffer(other), pool);
        PalettedSection.readBlocks(Unpooled.wrappedBuffer(single), pool);
        PalettedSection.readBlocks(Unpooled.wrappedBuffer(other), null);
        PalettedSection.readBlocks(Unpooled.wrappedBuffer(single), null);
        assertArrayEquals(new long[] {4, 2, 4, 1, 0}, since(start));
    }

    @Test
    void twoHitsThenWritesOnThePooledSectionAndBothViewsCountThreeClones() {
        var pool = new SectionPool();
        var pooled = interned(new SplittableRandom(0x57A8L), pool, false);
        var start = counters();

        var first = PalettedSection.readBlocks(Unpooled.wrappedBuffer(pooled.wire()), pool);
        var second = PalettedSection.readBlocks(Unpooled.wrappedBuffer(pooled.wire()), pool);
        assertTrue(pool.detachForSingleReferenceMutation(pooled.owner()));
        rewrite(pooled.owner().states());
        rewrite(pooled.owner().states());
        rewrite(first);
        rewrite(first);
        rewrite(second);
        assertArrayEquals(new long[] {2, 2, 2, 0, 3}, since(start));
    }

    @Test
    void aViewWrittenFirstCountsItsCloneAndThePooledSectionsNext() {
        var pool = new SectionPool();
        var pooled = interned(new SplittableRandom(0x57A9L), pool, false);
        var start = counters();

        var view = PalettedSection.readBlocks(Unpooled.wrappedBuffer(pooled.wire()), pool);
        var copied = PalettedSection.readBlocks(Unpooled.wrappedBuffer(pooled.wire()), pool);
        var copy = copied.copyOnWrite();
        rewrite(view);
        assertEquals(1, since(start)[4]);
        rewrite(copy);
        rewrite(copied);
        assertEquals(1, since(start)[4]);
        assertTrue(pool.detachForSingleReferenceMutation(pooled.owner()));
        rewrite(pooled.owner().states());
        assertArrayEquals(new long[] {2, 2, 2, 0, 2}, since(start));
    }

    @Test
    void aPooledSectionSharedBeforeTheHitCountsOnlyTheView() {
        var pool = new SectionPool();
        var pooled = interned(new SplittableRandom(0x57AAL), pool, true);
        var start = counters();

        var view = PalettedSection.readBlocks(Unpooled.wrappedBuffer(pooled.wire()), pool);
        assertTrue(pool.detachForSingleReferenceMutation(pooled.owner()));
        rewrite(pooled.owner().states());
        rewrite(view);
        assertArrayEquals(new long[] {1, 1, 1, 0, 1}, since(start));
    }

    private record Pooled(byte[] wire, CachedSection owner) {}

    /** A 26.3 container whose decode {@code pool} holds; {@code shared} pools a copy-on-write view of the decode. */
    private static Pooled interned(SplittableRandom random, SectionPool pool, boolean shared) {
        while (true) {
            var wire = section(random, ProtocolVersion.V26_3, false);
            var states = decoded(wire, ProtocolVersion.V26_3, IntUnaryOperator.identity());
            if (states == null) continue;
            var owner = pool.internRetained(new CachedSection(shared ? states.copyOnWrite() : states));
            assertEquals(1, pool.nonUniformPoolSize());
            return new Pooled(wire, owner);
        }
    }

    /** Offers, hits, candidates, multi-value full reads and shared clones, process-wide. */
    private static long[] counters() {
        return new long[] {
            SectionPool.wireOfferCounter.sum(),
            SectionPool.wireHitCounter.sum(),
            SectionPool.wireCandidateCounter.sum(),
            SectionPool.multiValueFullReadCounter.sum(),
            SectionPool.sharedCloneCounter.sum()
        };
    }

    private static long[] since(long[] start) {
        var now = counters();
        for (int i = 0; i < now.length; i++) now[i] -= start[i];
        return now;
    }

    private static void rewrite(PalettedSection states) {
        states.getAndSet(0, states.get(0) == 1 ? 2 : 1);
    }

    /** The length of the stored words that end {@code wire}, 0 for a container without local palette words. */
    private static int wordBytes(byte[] wire) {
        int wireBits = wire[0];
        if (wireBits < 1 || wireBits > 8) return 0;
        int perWord = 64 / Math.max(4, wireBits);
        return (4096 + perWord - 1) / perWord * Long.BYTES;
    }

    /** The decoded section when it decodes and could be pooled, null otherwise. */
    private static PalettedSection decoded(byte[] wire, ProtocolVersion version, IntUnaryOperator mapper) {
        try {
            var states = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), version, SOURCE, mapper, STATES);
            return states.isUniform() || new CachedSection(states).isEmpty() ? null : states;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** The section a decode of {@code wire} pools, or null when it fails or pools nothing. */
    private static PalettedSection pooled(
            SectionPool pool, byte[] wire, ProtocolVersion version, IntUnaryOperator mapper, int target) {
        try {
            var states = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), version, SOURCE, mapper, target);
            if (target != STATES) {
                // Sections outside the model registry are never interned; pool a copy directly.
                if (states.isUniform()) return null;
                return injected(pool, states);
            }
            var section = new CachedSection(states);
            if (section.isEmpty() || states.isUniform()) return null;
            pool.internRetained(section);
            return pool.nonUniformPoolSize() == 1 ? states : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static PalettedSection injected(SectionPool pool, PalettedSection states) {
        injected(pool, List.of(states));
        return states;
    }

    private static void injected(SectionPool pool, List<PalettedSection> states) {
        try {
            var field = SectionPool.class.getDeclaredField("nonUniformSections");
            field.setAccessible(true);
            var sections = new ArrayList<CachedSection>();
            for (var candidate : states) {
                var section = new CachedSection(candidate);
                section.markShared();
                sections.add(section);
            }
            field.set(pool, sections);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean matches(
            SectionPool pool, ByteBuf input, ProtocolVersion version, IntUnaryOperator mapper, int target) {
        return pool.sharedBlocks(input, !version.atLeast(ProtocolVersion.V1_21_5), SOURCE, mapper, target) != null;
    }

    /** The pooled wire itself, or a copy with one change: a byte, a truncation or trailing bytes. */
    private static byte[] variant(SplittableRandom random, byte[] wire, ProtocolVersion version, int round) {
        return switch (round % 6) {
            case 0, 1, 2 -> wire.clone();
            case 3 -> {
                byte[] changed = wire.clone();
                if (changed.length > 0) changed[random.nextInt(changed.length)] ^= (byte) (1 << random.nextInt(8));
                yield changed;
            }
            case 4 -> Arrays.copyOf(wire, random.nextInt(wire.length + 1));
            default -> {
                byte[] longer = Arrays.copyOf(wire, wire.length + 1 + random.nextInt(9));
                yield random.nextBoolean() ? longer : section(random, version, false);
            }
        };
    }

    /** The wire in a heap buffer, a direct buffer and a buffer of two parts, each read from a nonzero index. */
    private static List<ByteBuf> buffers(byte[] wire) {
        var buffers = new ArrayList<ByteBuf>();
        var heap = Unpooled.buffer(wire.length + 3);
        heap.writeBytes(new byte[3]).writeBytes(wire).skipBytes(3);
        buffers.add(heap);
        var direct = ByteBufAllocator.DEFAULT.directBuffer(wire.length + 5);
        direct.writeBytes(new byte[5]).writeBytes(wire);
        buffers.add(direct.skipBytes(5));
        int split = wire.length / 2;
        buffers.add(Unpooled.wrappedBuffer(
                Unpooled.wrappedBuffer(wire, 0, split), Unpooled.wrappedBuffer(wire, split, wire.length - split)));
        return buffers;
    }

    private static String outcome(Supplier<PalettedSection> read) {
        try {
            var section = read.get();
            var values = new StringBuilder(hex(written(section)));
            for (int i = 0; i < 4096; i++) values.append(',').append(section.get(i));
            return values.toString();
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    /** Every getAndSet result, then the written section or the first failure. */
    private static List<String> writes(PalettedSection section, SplittableRandom random) {
        var results = new ArrayList<String>();
        int states = 1 + random.nextInt(400);
        int base = random.nextInt(STATES - states);
        try {
            for (int i = 0, count = 1 + random.nextInt(1500); i < count; i++) {
                int index = random.nextInt(4096);
                int state = random.nextInt(4) == 0 ? section.get(random.nextInt(4096)) : base + random.nextInt(states);
                results.add(Integer.toString(section.getAndSet(index, state)));
            }
            results.add(hex(written(section)));
        } catch (RuntimeException failure) {
            results.add(failure.getClass().getName() + ": " + failure.getMessage());
        }
        return results;
    }

    /** A block container with random width, palette (duplicates included) and stored codes. */
    private static byte[] section(SplittableRandom random, ProtocolVersion version, boolean malformed) {
        int wireBits = random.nextInt(10);
        boolean global = wireBits > 8;
        int bits = wireBits == 0 ? 0 : global ? 15 : Math.max(4, wireBits);
        int paletteSize = bits == 0 || global ? 1 : 1 + random.nextInt(1 << bits);
        int distinct = 1 + random.nextInt(Math.min(paletteSize, 40));
        ByteBuf output = Unpooled.buffer();
        try {
            output.writeByte(wireBits);
            int[] palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++)
                palette[i] = i > 0 && random.nextInt(8) == 0 ? palette[random.nextInt(i)] : random.nextInt(SOURCE);
            if (!global) {
                if (bits == 0) Wire.writeVarInt(output, palette[0]);
                else {
                    Wire.writeVarInt(output, paletteSize);
                    for (int state : palette) Wire.writeVarInt(output, state);
                }
            }
            int perWord = bits == 0 ? 1 : 64 / bits;
            int words = bits == 0 ? 0 : (4096 + perWord - 1) / perWord;
            if (!version.atLeast(ProtocolVersion.V1_21_5))
                Wire.writeVarInt(output, malformed && random.nextBoolean() ? words + 1 : words);
            int range = global ? SOURCE : malformed ? 1 << bits : Math.min(paletteSize, 1 << bits);
            int[] codes = new int[distinct];
            for (int i = 0; i < distinct; i++) codes[i] = random.nextInt(Math.max(1, range));
            for (int word = 0, index = 0; word < words; word++) {
                long value = 0;
                for (int slot = 0; slot < perWord && index < 4096; slot++, index++)
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
