package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The canonical reads, snapshots and received sections read, write and fail exactly as the copying reads do, and no
 * write reaches a canonical section.
 */
class CanonicalSectionReadTest {
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int SOURCE = 30_000;
    private static final int BIOMES = 90;
    private static final ProtocolVersion[] VERSIONS = {
        ProtocolVersion.V1_21_4, ProtocolVersion.V1_21_5, ProtocolVersion.V26_1, ProtocolVersion.V26_3
    };

    @Test
    void aCanonicalReadReadsWritesAndFailsAsTheCopyingRead() {
        var random = new SplittableRandom(0xCA70L);
        int singles = 0, failures = 0;
        for (int round = 0; round < 6000; round++) {
            var version = VERSIONS[random.nextInt(VERSIONS.length)];
            boolean blocks = round % 2 == 0;
            int source = blocks ? SOURCE : BIOMES;
            int target = blocks
                    ? round % 13 == 0 ? 2 + random.nextInt(300) : STATES - random.nextInt(50)
                    : 40 + random.nextInt(BIOMES);
            int offset = random.nextInt(source), failing = round % 7 == 0 ? random.nextInt(source) : -1;
            IntUnaryOperator mapper = round % 11 == 0
                    ? null
                    : id -> {
                        if (id == failing) throw new IllegalStateException("Unmapped " + id);
                        return id % 997 == 0 ? target + id % 3 : (id + offset) % target;
                    };
            byte[] wire = variant(random, container(random, version, blocks, source, round % 5 == 0), round);
            var pool = round % 3 == 0 ? new SectionPool() : null;
            List<ByteBuf> copying = buffers(wire), canonical = buffers(wire);
            for (int kind = 0; kind < copying.size(); kind++) {
                ByteBuf full = copying.get(kind), input = canonical.get(kind);
                try {
                    PalettedSection[] expected = new PalettedSection[1], actual = new PalettedSection[1];
                    String expectedOutcome = outcome(() -> expected[0] = blocks
                            ? PalettedSection.readBlocks(full, version, source, mapper, target)
                            : PalettedSection.readBiomes(full, version, source, mapper, target));
                    String actualOutcome = outcome(() -> actual[0] = blocks
                            ? PalettedSection.readCanonicalBlocksPooled(input, version, source, mapper, target, pool)
                                    .states()
                            : PalettedSection.readCanonicalBiomes(input, version, source, mapper, target));
                    assertEquals(expectedOutcome, actualOutcome, "round " + round);
                    assertEquals(full.readerIndex(), input.readerIndex(), "round " + round);
                    if (expected[0] == null) {
                        failures++;
                        continue;
                    }
                    boolean single = wire[0] == 0;
                    if (single) singles++;
                    sameWrites(expected[0], actual[0], single, random.nextLong(), "round " + round);
                } finally {
                    full.release();
                    input.release();
                }
            }
        }
        assertTrue(singles > 3000, "singles " + singles);
        assertTrue(failures > 2000, "failures " + failures);
    }

    @Test
    void biomeValuesMatchTheCopyingReadAtEveryPosition() {
        var random = new SplittableRandom(0xB10EL);
        int singles = 0;
        for (int round = 0; round < 4000; round++) {
            byte[] wire =
                    variant(random, container(random, ProtocolVersion.V26_3, false, BIOMES, round % 5 == 0), round);
            for (int path = 0; path < 2; path++) {
                List<ByteBuf> copying = buffers(wire), read = buffers(wire);
                for (int kind = 0; kind < copying.size(); kind++) {
                    ByteBuf full = copying.get(kind), input = read.get(kind);
                    try {
                        String expected = values(() -> {
                            var section = PalettedSection.readBiomes(full, ProtocolVersion.V26_3, BIOMES);
                            int[] values = new int[64];
                            for (int i = 0; i < 64; i++) values[i] = section.get(i);
                            return values;
                        });
                        boolean uniform = path == 1;
                        String actual = values(() -> uniform
                                ? PalettedSection.readBiomeValues(input, BIOMES)
                                : PalettedSection.readBiomes(input, BIOMES));
                        assertEquals(expected, actual, "round " + round);
                        assertEquals(full.readerIndex(), input.readerIndex(), "round " + round);
                        if (wire.length > 0 && wire[0] == 0 && !expected.contains(":")) singles++;
                    } finally {
                        full.release();
                        input.release();
                    }
                }
            }
        }
        assertTrue(singles > 2000, "singles " + singles);
    }

    @Test
    void aSnapshotReadsEncodesAndCopiesAsTheCopyingReads() {
        var random = new SplittableRandom(0x5A75L);
        IntUnaryOperator states = id -> id % STATES, biomes = id -> id % 64;
        int read = 0;
        for (int round = 0; round < 3000; round++) {
            var version = VERSIONS[random.nextInt(VERSIONS.length)];
            boolean malformed = round % 6 == 0;
            ByteBuf output = Unpooled.buffer();
            output.writeShort(random.nextInt(4097));
            if (version.atLeast(ProtocolVersion.V26_1)) output.writeShort(random.nextInt(3));
            output.writeBytes(container(random, version, true, SOURCE, malformed));
            output.writeBytes(container(random, version, false, BIOMES, malformed));
            byte[] wire = variant(random, drained(output), round);
            ByteBuf full = Unpooled.wrappedBuffer(wire), input = Unpooled.wrappedBuffer(wire);
            String expected = snapshotOutcome(() -> {
                int nonEmpty = full.readShort();
                int fluids = version.atLeast(ProtocolVersion.V26_1) ? full.readShort() : 0;
                var blocks = PalettedSection.readBlocks(full, version, SOURCE, states, STATES);
                var biome = PalettedSection.readBiomes(full, version, BIOMES, biomes, 64);
                return new Decoded(
                        nonEmpty,
                        version.atLeast(ProtocolVersion.V26_1) ? fluids : -1,
                        blocks.copyOnWrite(),
                        biome.singleValue(),
                        values(biome),
                        encoded(nonEmpty, fluids, blocks, biome));
            });
            SectionSnapshot[] snapshot = new SectionSnapshot[1];
            String actual = snapshotOutcome(() -> {
                snapshot[0] = SectionSnapshot.read(input, version, SOURCE, STATES, BIOMES, 64, states, biomes);
                var section = snapshot[0];
                return new Decoded(
                        section.nonEmptyBlockCount(),
                        section.knownFluidCount(),
                        section.copyBlocks(),
                        section.uniformBiome(),
                        section.copyBiomes(),
                        bytes(SectionSnapshot.encode(List.of(section))));
            });
            assertEquals(expected, actual, "round " + round);
            assertEquals(full.readerIndex(), input.readerIndex(), "round " + round);
            if (snapshot[0] == null) continue;
            read++;
            long seed = random.nextLong();
            byte[] before = bytes(SectionSnapshot.encode(List.of(snapshot[0])));
            var expectedCopy = PalettedSection.readBlocks(
                    Unpooled.wrappedBuffer(wire).skipBytes(version.atLeast(ProtocolVersion.V26_1) ? 4 : 2),
                    version,
                    SOURCE,
                    states,
                    STATES);
            assertEquals(
                    writes(expectedCopy.copyOnWrite(), new SplittableRandom(seed)),
                    writes(snapshot[0].copyBlocks(), new SplittableRandom(seed)),
                    "round " + round);
            assertArrayEquals(before, bytes(SectionSnapshot.encode(List.of(snapshot[0]))), "round " + round);
        }
        assertTrue(read > 1500, "read " + read);
    }

    @Test
    void aReceivedSectionCountsInternsAndWritesAsTheConstructedOne() throws ReflectiveOperationException {
        Constructor<CachedSection> constructed =
                CachedSection.class.getDeclaredConstructor(PalettedSection.class, int.class, int.class);
        constructed.setAccessible(true);
        var air = SectionPool.canonicalAir();
        byte[] airBefore = written(air.states());
        var random = new SplittableRandom(0x2EC5L);
        int canonical = 0;
        for (int round = 0; round < 3000; round++) {
            byte[] wire = container(random, ProtocolVersion.V26_3, true, STATES, false);
            if (round % 3 == 0) wire = new byte[] {0, 0};
            int nonEmpty = round % 4 == 0 ? random.nextInt(4097) : round % 11 == 0 ? -1 : 0;
            int fluids = round % 5 == 0 ? random.nextInt(4097) : round % 2 == 0 ? -1 : 0;
            PalettedSection copied, read;
            try {
                copied = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire));
                read = round % 2 == 0
                        ? PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire))
                        : PalettedSection.readCanonicalBlocksPooled(
                                        Unpooled.wrappedBuffer(wire),
                                        ProtocolVersion.V26_3,
                                        STATES,
                                        IntUnaryOperator.identity(),
                                        STATES,
                                        null)
                                .states();
            } catch (RuntimeException rejected) {
                continue;
            }
            byte[] readBefore = written(read);
            CachedSection expected = constructed.newInstance(copied, nonEmpty, fluids);
            CachedSection actual = CachedSection.received(read, nonEmpty, fluids);
            if (actual == air) canonical++;
            assertEquals(state(expected), state(actual), "round " + round);
            var expectedInterned = new SectionPool().internRetained(expected);
            var actualInterned = new SectionPool().internRetained(actual);
            assertEquals(expectedInterned == air, actualInterned == air, "round " + round);
            assertEquals(state(expectedInterned), state(actualInterned), "round " + round);
            long seed = random.nextLong();
            assertEquals(
                    writes(expectedInterned, new SplittableRandom(seed)),
                    writes(actualInterned, new SplittableRandom(seed)),
                    "round " + round);
            assertArrayEquals(airBefore, written(air.states()), "round " + round);
            assertTrue(air.isEmpty() && !air.hasFluid() && air.isShared(), "round " + round);
            if (read.singleValue() >= 0 && round % 2 == 1) assertArrayEquals(readBefore, written(read));
        }
        assertTrue(canonical > 300, "canonical " + canonical);
    }

    @Test
    void aTakenSnapshotSectionReceivesAndWritesAsItsCopy() {
        var air = SectionPool.canonicalAir();
        byte[] airBefore = written(air.states());
        IntUnaryOperator states = id -> id % STATES, biomes = id -> id % 64;
        var random = new SplittableRandom(0x7A4EL);
        int canonical = 0, receivedAir = 0;
        for (int round = 0; round < 3000; round++) {
            var version = VERSIONS[random.nextInt(VERSIONS.length)];
            boolean wireFluids = version.atLeast(ProtocolVersion.V26_1);
            byte[] blocks = round % 3 == 0
                    ? version.atLeast(ProtocolVersion.V1_21_5) ? new byte[] {0, 0} : new byte[] {0, 0, 0}
                    : container(random, version, true, SOURCE, false);
            ByteBuf output = Unpooled.buffer();
            output.writeShort(round % 4 == 0 ? random.nextInt(4097) : 0);
            if (wireFluids) output.writeShort(round % 5 == 0 ? random.nextInt(3) : 0);
            output.writeBytes(blocks);
            output.writeBytes(container(random, version, false, BIOMES, false));
            byte[] wire = drained(output);
            SectionPool pool = null;
            if (round % 2 == 1) {
                pool = new SectionPool();
                ByteBuf held = Unpooled.wrappedBuffer(blocks);
                pool.internRetained(
                        new CachedSection(PalettedSection.readBlocks(held, version, SOURCE, states, STATES)));
            }
            var copied = SectionSnapshot.read(
                    Unpooled.wrappedBuffer(wire), version, SOURCE, STATES, BIOMES, 64, states, biomes, pool);
            var taken = SectionSnapshot.read(
                    Unpooled.wrappedBuffer(wire), version, SOURCE, STATES, BIOMES, 64, states, biomes, pool);
            byte[] takenBefore = bytes(SectionSnapshot.encode(List.of(taken)));
            var expected =
                    CachedSection.received(copied.copyBlocks(), copied.nonEmptyBlockCount(), copied.knownFluidCount());
            var takenBlocks = taken.takeBlocks();
            if (takenBlocks.isCanonical()) canonical++;
            var actual = CachedSection.received(takenBlocks, taken.nonEmptyBlockCount(), taken.knownFluidCount());
            assertEquals(expected == air, actual == air, "round " + round);
            if (actual == air) receivedAir++;
            assertEquals(expected.isShared(), actual.isShared(), "round " + round);
            assertEquals(state(expected), state(actual), "round " + round);
            long seed = random.nextLong();
            assertEquals(
                    writes(expected, new SplittableRandom(seed)),
                    writes(actual, new SplittableRandom(seed)),
                    "round " + round);
            assertArrayEquals(takenBefore, bytes(SectionSnapshot.encode(List.of(taken))), "round " + round);
            assertArrayEquals(written(copied.copyBlocks()), written(taken.takeBlocks()), "round " + round);
            assertArrayEquals(airBefore, written(air.states()), "round " + round);
        }
        assertTrue(canonical > 1000 && receivedAir > 500, "canonical " + canonical + " air " + receivedAir);
    }

    @Test
    void aCanonicalSectionIsSharedAndNeverWritten() {
        byte[] stone = {0, 1};
        var first = canonicalBlocks(stone);
        var second = canonicalBlocks(stone);
        assertSame(first, second);
        assertThrows(IllegalStateException.class, () -> first.getAndSet(0, 2));
        var copy = first.copyOnWrite();
        assertNotSame(first, copy);
        assertEquals(1, copy.getAndSet(7, 2));
        assertEquals(2, copy.get(7));
        var section = new CachedSection(first);
        section.setStateId(9, 3);
        assertEquals(3, section.getStateId(9));
        var received = CachedSection.received(first, 4096, 0);
        received.setStateId(10, 4);
        assertEquals(4, received.getStateId(10));
        var remapped = first.remap(id -> id + 1, STATES);
        assertEquals(2, remapped.get(0));
        for (int i = 0; i < 4096; i++) assertEquals(1, first.get(i));
        assertArrayEquals(stone, written(first));
        assertNotSame(first, canonicalBlocks(new byte[] {0, 1}, STATES - 1));
        for (int round = 0; round < 3; round++)
            for (int target = 60; target < 64; target++) {
                var biome = canonicalBiome(5, target);
                assertSame(biome, canonicalBiome(5, target));
                assertEquals(5, biome.get(63));
            }
    }

    @Test
    void theReceivedAirSectionRefusesWrites() {
        var air = SectionPool.canonicalAir();
        byte[] before = written(air.states());
        long mutated = air.getLastMutatedNanos();
        var received = CachedSection.received(PalettedSection.blocks(), 0, 0);
        assertSame(air, received);
        assertTrue(received.isShared());
        assertThrows(IllegalStateException.class, () -> received.setStateId(0, 1));
        assertThrows(IllegalStateException.class, () -> received.setLastMutatedNanos(mutated + 1));
        var canonicalAir = CachedSection.received(canonicalBlocks(new byte[] {0, 0}), 0, -1);
        assertSame(air, canonicalAir);
        assertThrows(IllegalStateException.class, () -> canonicalAir.setStateId(4095, 1));
        assertArrayEquals(before, written(air.states()));
        assertEquals(mutated, air.getLastMutatedNanos());
        assertTrue(air.isEmpty() && !air.hasFluid());
        var own = CachedSection.createAirSection();
        assertEquals(0, own.setStateId(0, 1));
        own.setLastMutatedNanos(mutated);
        assertEquals(1, own.getStateId(0));
        assertEquals(0, air.getStateId(0));
    }

    @Test
    void aSingleEntrySectionCountsAsEveryIndexOfItsEntry() {
        var random = new SplittableRandom(0xC0A7L);
        for (int round = 0; round < 500; round++) {
            int state = round < 3 ? round : random.nextInt(STATES);
            ByteBuf wire = Unpooled.buffer().writeByte(0);
            Wire.writeVarInt(wire, state);
            var section = new CachedSection(PalettedSection.readBlocks(wire));
            StateFacts facts = DataTables.defaults().registry().facts(state);
            boolean solid = !facts.has(StateFacts.AIR);
            assertEquals(!solid, section.isEmpty(), "state " + state);
            assertEquals(solid && !facts.fluid().equals("minecraft:empty") ? 4096 : 0, section.fluidCount());
        }
    }

    private static PalettedSection canonicalBlocks(byte[] wire) {
        return canonicalBlocks(wire, STATES);
    }

    private static PalettedSection canonicalBlocks(byte[] wire, int target) {
        return PalettedSection.readCanonicalBlocksPooled(
                        Unpooled.wrappedBuffer(wire),
                        ProtocolVersion.V26_3,
                        STATES,
                        IntUnaryOperator.identity(),
                        target,
                        null)
                .states();
    }

    private static PalettedSection canonicalBiome(int biome, int target) {
        return PalettedSection.readCanonicalBiomes(
                Unpooled.wrappedBuffer(new byte[] {0, (byte) biome}),
                ProtocolVersion.V26_3,
                BIOMES,
                IntUnaryOperator.identity(),
                target);
    }

    private record Decoded(
            int nonEmpty, int fluids, PalettedSection blocks, int uniformBiome, int[] biomes, byte[] encoded) {
        @Override
        public String toString() {
            return nonEmpty + "/" + fluids + "/" + hex(written(blocks)) + "/" + uniformBiome + "/"
                    + Arrays.toString(biomes) + "/" + hex(encoded);
        }
    }

    /** Both sections hold and write the same, and neither write reaches a canonical one. */
    private static void sameWrites(
            PalettedSection expected, PalettedSection actual, boolean canonical, long seed, String message) {
        byte[] before = written(actual);
        assertEquals(
                writes(expected.copyOnWrite(), new SplittableRandom(seed)),
                writes(actual.copyOnWrite(), new SplittableRandom(seed)),
                message);
        assertArrayEquals(before, written(actual), message);
        if (canonical) {
            assertThrows(IllegalStateException.class, () -> actual.getAndSet(0, 0), message);
            assertArrayEquals(before, written(actual), message);
        } else
            assertEquals(
                    writes(expected, new SplittableRandom(seed)), writes(actual, new SplittableRandom(seed)), message);
    }

    /** As the world writes a section: a shared section is replaced by its own copy first. */
    private static List<String> writes(CachedSection section, SplittableRandom random) {
        var results = new ArrayList<String>();
        try {
            for (int i = 0, count = 1 + random.nextInt(60); i < count; i++) {
                if (section.isShared())
                    section = section.isEmpty() && !section.hasFluid()
                            ? CachedSection.createAirSection()
                            : section.mutableCopy();
                results.add(Integer.toString(section.setStateId(random.nextInt(4096), random.nextInt(STATES))));
            }
            results.add(state(section));
        } catch (RuntimeException failure) {
            results.add(failure.getClass().getName() + ": " + failure.getMessage());
        }
        return results;
    }

    private static String state(CachedSection section) {
        return section.isEmpty() + "/" + section.hasFluid() + "/" + section.fluidCount() + "/"
                + hex(written(section.states()));
    }

    /** Every getAndSet result, then the written section or the first failure. */
    private static List<String> writes(PalettedSection section, SplittableRandom random) {
        var results = new ArrayList<String>();
        int states = 1 + random.nextInt(400);
        int base = random.nextInt(STATES - states);
        try {
            for (int i = 0, count = 1 + random.nextInt(600); i < count; i++) {
                int index = random.nextInt(section.size());
                int state = random.nextInt(4) == 0
                        ? section.get(random.nextInt(section.size()))
                        : base + random.nextInt(states);
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
            values.append('/').append(section.singleValue());
            for (int i = 0; i < section.size(); i++) values.append(',').append(section.get(i));
            return values.toString();
        } catch (NullPointerException failure) {
            // Compiled code may throw a preallocated NullPointerException without its message.
            return failure.getClass().getName();
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private static String values(Supplier<int[]> read) {
        try {
            return Arrays.toString(read.get());
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private static String snapshotOutcome(Supplier<Decoded> read) {
        try {
            return read.get().toString();
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private static int[] values(PalettedSection section) {
        int[] values = new int[64];
        for (int i = 0; i < values.length; i++) values[i] = section.get(i);
        return values;
    }

    private static byte[] encoded(int nonEmpty, int fluids, PalettedSection blocks, PalettedSection biomes) {
        ByteBuf output = Unpooled.buffer();
        output.writeShort(nonEmpty);
        output.writeShort(fluids);
        blocks.write(output);
        biomes.write(output);
        return drained(output);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /** The wire itself, or a copy with one change: a byte, a truncation or trailing bytes. */
    private static byte[] variant(SplittableRandom random, byte[] wire, int round) {
        return switch (round % 7) {
            case 0, 1, 2, 3 -> wire;
            case 4 -> {
                byte[] changed = wire.clone();
                if (changed.length > 0) changed[random.nextInt(changed.length)] ^= (byte) (1 << random.nextInt(8));
                yield changed;
            }
            case 5 -> Arrays.copyOf(wire, random.nextInt(wire.length + 1));
            default -> Arrays.copyOf(wire, wire.length + 1 + random.nextInt(9));
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

    /**
     * A block or biome container with random width, palette (duplicates included) and stored codes; half of them hold
     * a single entry, some of those outside the registry.
     */
    private static byte[] container(
            SplittableRandom random, ProtocolVersion version, boolean blocks, int source, boolean malformed) {
        int size = blocks ? 4096 : 64, minimum = blocks ? 4 : 1, maximum = blocks ? 8 : 3;
        int globalBits = 32 - Integer.numberOfLeadingZeros(source - 1);
        int wireBits = random.nextBoolean() ? 0 : random.nextInt(maximum + 2);
        boolean global = wireBits > maximum;
        int bits = wireBits == 0 ? 0 : global ? globalBits : Math.max(minimum, wireBits);
        int paletteSize = bits == 0 || global ? 1 : 1 + random.nextInt(1 << bits);
        int distinct = 1 + random.nextInt(Math.min(paletteSize, 40));
        ByteBuf output = Unpooled.buffer();
        output.writeByte(wireBits);
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++)
            palette[i] = i > 0 && random.nextInt(8) == 0
                    ? palette[random.nextInt(i)]
                    : malformed && random.nextInt(4) == 0 ? source + random.nextInt(3) : random.nextInt(source);
        if (!global) {
            if (bits == 0) Wire.writeVarInt(output, palette[0]);
            else {
                Wire.writeVarInt(output, paletteSize);
                for (int state : palette) Wire.writeVarInt(output, state);
            }
        }
        int perWord = bits == 0 ? 1 : 64 / bits;
        int words = bits == 0 ? 0 : (size + perWord - 1) / perWord;
        if (!version.atLeast(ProtocolVersion.V1_21_5)) {
            int length = malformed && random.nextBoolean() ? words + 1 + random.nextInt(3) : words;
            Wire.writeVarInt(output, length);
            if (length != words && random.nextBoolean()) for (int i = 0; i < length - words; i++) output.writeLong(0);
        }
        int range = global ? source : malformed ? 1 << bits : Math.min(paletteSize, 1 << bits);
        int[] codes = new int[distinct];
        for (int i = 0; i < distinct; i++) codes[i] = random.nextInt(Math.max(1, range));
        for (int word = 0, index = 0; word < words; word++) {
            long value = 0;
            for (int slot = 0; slot < perWord && index < size; slot++, index++)
                value |= (long) codes[random.nextInt(distinct)] << (slot * bits);
            output.writeLong(value);
        }
        return drained(output);
    }

    private static byte[] written(PalettedSection section) {
        ByteBuf output = Unpooled.buffer();
        section.write(output);
        return drained(output);
    }

    private static String hex(byte[] bytes) {
        var text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
            text.append(Character.forDigit(value >> 4 & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    /** The readable bytes of {@code output}, which is released. */
    private static byte[] drained(ByteBuf output) {
        try {
            byte[] bytes = new byte[output.readableBytes()];
            output.readBytes(bytes);
            return bytes;
        } finally {
            output.release();
        }
    }
}
