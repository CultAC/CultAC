package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Shared biome values equal the values a copying read gives at every index, with the same failure; a stored array is
 * shared only with a section holding exactly its values and is never written.
 */
class SharedBiomeValuesTest {
    private static final int REGISTRY = 40;

    @Test
    void sharedValuesMatchTheCopyingReadOnEveryInput() {
        SectionStore.clear();
        var random = new SplittableRandom(0xB1035L);
        var pool = new ArrayList<byte[]>();
        for (int i = 0; i < 48; i++) pool.add(container(random));
        var given = new ArrayList<int[]>();
        var givenExpected = new ArrayList<String>();
        int hits = 0, failures = 0, mixed = 0;
        for (int round = 0; round < 20_000; round++) {
            byte[] wire = round % 8 == 0 ? container(random) : pool.get(random.nextInt(pool.size()));
            boolean snapshot = round % 2 == 0;
            int target = round % 3 == 0 ? 7 : REGISTRY;
            IntUnaryOperator mapper = id -> id % target;
            PalettedSection copying = read(wire, snapshot, mapper, target);
            PalettedSection sharing = read(wire, snapshot, mapper, target);
            if (copying == null) continue;
            String expected = outcome(() -> copyingValues(copying));
            long hitsBefore = SectionStore.valueHitCounter.sum();
            int[][] result = new int[1][];
            String actual = outcome(() -> result[0] = sharing.sharedValues());
            assertEquals(expected, actual, "round " + round);
            if (result[0] == null) {
                failures++;
                continue;
            }
            if (copying.singleValue() >= 0) assertSame(CachedChunk.uniformBiomes(copying.singleValue()), result[0]);
            else mixed++;
            if (SectionStore.valueHitCounter.sum() > hitsBefore) hits++;
            given.add(result[0]);
            givenExpected.add(expected);
        }
        for (int i = 0; i < given.size(); i++)
            assertEquals(givenExpected.get(i), Arrays.toString(given.get(i)), "array " + i + " was written");
        assertTrue(hits > 4000, "hits " + hits);
        assertTrue(failures > 300, "failures " + failures);
        assertTrue(mixed > 6000, "mixed " + mixed);
    }

    @Test
    void readBiomeValuesMatchesTheCopyingRead() {
        SectionStore.clear();
        var random = new SplittableRandom(0xB1036L);
        var pool = new ArrayList<byte[]>();
        for (int i = 0; i < 32; i++) pool.add(container(random));
        int hits = 0;
        for (int round = 0; round < 6000; round++) {
            byte[] wire = round % 5 == 0 ? container(random) : pool.get(random.nextInt(pool.size()));
            for (ByteBuf input : buffers(wire)) {
                ByteBuf full = Unpooled.wrappedBuffer(wire);
                try {
                    String expected = outcome(() -> {
                        var section = PalettedSection.readCanonicalBiomes(full, REGISTRY);
                        return copyingValues(section);
                    });
                    long hitsBefore = SectionStore.valueHitCounter.sum();
                    int start = input.readerIndex();
                    String actual = outcome(() -> PalettedSection.readBiomeValues(input, REGISTRY));
                    assertEquals(expected, actual, "round " + round);
                    assertEquals(full.readerIndex(), input.readerIndex() - start, "round " + round);
                    if (SectionStore.valueHitCounter.sum() > hitsBefore) hits++;
                } finally {
                    full.release();
                    input.release();
                }
            }
        }
        assertTrue(hits > 3000, "hits " + hits);
    }

    @Test
    void aSnapshotGivesTheValuesTheWorldReaderCopied() {
        SectionStore.clear();
        var random = new SplittableRandom(0xB1037L);
        var pool = new ArrayList<byte[]>();
        for (int i = 0; i < 24; i++) pool.add(snapshotWire(random));
        for (int round = 0; round < 4000; round++) {
            byte[] wire = pool.get(random.nextInt(pool.size()));
            IntUnaryOperator states = id -> id, biomes = id -> id % 11;
            SectionSnapshot copying, sharing;
            try {
                copying = SectionSnapshot.read(
                        Unpooled.wrappedBuffer(wire), ProtocolVersion.V26_3, 64, 64, REGISTRY, 11, states, biomes);
                sharing = SectionSnapshot.read(
                        Unpooled.wrappedBuffer(wire), ProtocolVersion.V26_3, 64, 64, REGISTRY, 11, states, biomes);
            } catch (RuntimeException malformed) {
                continue;
            }
            String expected = outcome(() -> {
                int biome = copying.uniformBiome();
                return biome < 0 ? copying.copyBiomes() : CachedChunk.uniformBiomes(biome);
            });
            assertEquals(expected, outcome(sharing::biomeValues), "round " + round);
        }
    }

    @Test
    void aFingerprintCollisionNeverShares() {
        SectionStore.clear();
        var first = read(local(2, new int[] {3, 5}, 0b01), false, id -> id, REGISTRY);
        var second = read(local(2, new int[] {3, 5}, 0b0100), false, id -> id, REGISTRY);
        long fingerprint = first.valuesFingerprint();
        int[] stored = SectionStore.values(first, fingerprint);
        int[] other = SectionStore.values(second, fingerprint);
        assertNotSame(stored, other);
        assertArrayEquals(copyingValues(second), other);
        assertArrayEquals(copyingValues(first), stored);
        var again = read(local(2, new int[] {3, 5}, 0b01), false, id -> id, REGISTRY);
        assertSame(stored, SectionStore.values(again, fingerprint));
    }

    @Test
    void sectionsHoldingTheSameValuesInOtherStorageEachGetThem() {
        SectionStore.clear();
        var narrow = read(local(1, new int[] {3, 5}, 0b01), false, id -> id, REGISTRY);
        var wide = read(local(3, new int[] {3, 5, 9}, 0b001), false, id -> id, REGISTRY);
        assertArrayEquals(copyingValues(narrow), copyingValues(wide));
        assertArrayEquals(copyingValues(narrow), narrow.sharedValues());
        assertArrayEquals(copyingValues(wide), wide.sharedValues());
    }

    @Test
    void anEntryHoldsOnlyItsValuesAndTheStoreStaysBounded() throws Exception {
        SectionStore.clear();
        var random = new SplittableRandom(0xB1039L);
        for (int i = 0; i < 4 * SectionStore.VALUE_SETS * SectionStore.WAYS; i++) {
            var section = read(container(random), false, id -> id, REGISTRY);
            if (section != null && section.singleValue() < 0) {
                try {
                    section.sharedValues();
                } catch (IllegalStateException malformed) {
                    // Values past the palette fail as the copying read does.
                }
            }
        }
        var components = new ArrayList<Class<?>>();
        for (var component : SectionStore.Values.class.getRecordComponents()) components.add(component.getType());
        assertEquals(List.of(long.class, int[].class), components);
        Field field = SectionStore.class.getDeclaredField("VALUES");
        field.setAccessible(true);
        var values = (AtomicReferenceArray<?>) field.get(null);
        assertEquals(SectionStore.VALUE_SETS * SectionStore.WAYS, values.length());
        int held = 0;
        for (int i = 0; i < values.length(); i++) if (values.get(i) != null) held++;
        assertTrue(held > SectionStore.VALUE_SETS, "held " + held);
        SectionStore.clear();
        for (int i = 0; i < values.length(); i++) assertEquals(null, values.get(i));
    }

    @Test
    void onlyA64CellSectionSharesItsValues() {
        assertThrows(IllegalStateException.class, () -> PalettedSection.blocks().sharedValues());
    }

    @Test
    void threadsSharingAndOfferingEachSeeTheirOwnValues() throws Exception {
        SectionStore.clear();
        var seeds = new SplittableRandom(0xB1038L);
        var pool = new ArrayList<byte[]>();
        for (int i = 0; i < 16; i++) pool.add(container(seeds));
        int threads = 6;
        var barrier = new CyclicBarrier(threads);
        var failure = new AtomicReference<Throwable>();
        var workers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            long seed = seeds.nextLong();
            var worker = new Thread(() -> {
                try {
                    var random = new SplittableRandom(seed);
                    barrier.await();
                    for (int round = 0; round < 20_000; round++) {
                        byte[] wire = round % 8 == 0 ? container(random) : pool.get(random.nextInt(pool.size()));
                        var copying = read(wire, false, id -> id, REGISTRY);
                        if (copying == null) continue;
                        var sharing = read(wire, false, id -> id, REGISTRY);
                        assertEquals(outcome(() -> copyingValues(copying)), outcome(sharing::sharedValues));
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) worker.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    /** The values the world reader copied before sharing: a single entry's shared array, else each index in order. */
    static int[] copyingValues(PalettedSection section) {
        int single = section.singleValue();
        if (single >= 0) return CachedChunk.uniformBiomes(single);
        int[] values = new int[64];
        for (int i = 0; i < values.length; i++) values[i] = section.get(i);
        return values;
    }

    private static PalettedSection read(byte[] wire, boolean snapshot, IntUnaryOperator mapper, int target) {
        ByteBuf input = Unpooled.wrappedBuffer(wire);
        try {
            return snapshot
                    ? PalettedSection.readCanonicalBiomes(input, ProtocolVersion.V26_3, REGISTRY, mapper, target)
                    : PalettedSection.readCanonicalBiomes(input, REGISTRY);
        } catch (RuntimeException malformed) {
            return null;
        } finally {
            input.release();
        }
    }

    private static String outcome(Supplier<int[]> values) {
        try {
            return Arrays.toString(values.get());
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private static List<ByteBuf> buffers(byte[] wire) {
        ByteBuf direct = Unpooled.directBuffer(wire.length + 3);
        direct.writeBytes(new byte[] {1, 2, 3});
        direct.writeBytes(wire);
        direct.skipBytes(3);
        return List.of(Unpooled.wrappedBuffer(wire), direct, Unpooled.wrappedBuffer(wire).asReadOnly());
    }

    private static byte[] snapshotWire(SplittableRandom random) {
        ByteBuf output = Unpooled.buffer();
        output.writeShort(random.nextInt(4097));
        output.writeShort(0);
        output.writeByte(0);
        Wire.writeVarInt(output, random.nextInt(64));
        output.writeBytes(container(random));
        return drained(output);
    }

    /** A 26.3 biome container: single, local of 1 to 3 bits (some codes past the palette), or global. */
    static byte[] container(SplittableRandom random) {
        int kind = random.nextInt(6);
        if (kind == 0) {
            ByteBuf output = Unpooled.buffer();
            output.writeByte(0);
            Wire.writeVarInt(output, random.nextInt(REGISTRY));
            return drained(output);
        }
        if (kind == 5) {
            int bits = 32 - Integer.numberOfLeadingZeros(REGISTRY - 1);
            ByteBuf output = Unpooled.buffer();
            output.writeByte(6);
            int perWord = 64 / bits;
            boolean strict = random.nextInt(3) != 0;
            for (int word = 0; word < (64 + perWord - 1) / perWord; word++) {
                long value = 0;
                for (int i = 0; i < perWord; i++) {
                    long code = strict ? random.nextInt(REGISTRY) : random.nextInt(1 << bits);
                    value |= code << (i * bits);
                }
                output.writeLong(value);
            }
            return drained(output);
        }
        int bits = kind <= 2 ? kind : 3;
        int entries = 1 + random.nextInt(1 << bits);
        int[] palette = new int[entries];
        for (int i = 0; i < entries; i++) palette[i] = random.nextInt(REGISTRY);
        boolean strict = random.nextInt(4) != 0;
        long[] codes = new long[64];
        for (int i = 0; i < 64; i++) codes[i] = strict ? random.nextInt(entries) : random.nextInt(1 << bits);
        return local(bits, palette, codes);
    }

    private static byte[] local(int bits, int[] palette, long firstCodes) {
        long[] codes = new long[64];
        for (int i = 0; i < 64 && i < 3; i++) codes[i] = firstCodes >>> (i * bits) & ((1L << bits) - 1);
        return local(bits, palette, codes);
    }

    private static byte[] local(int bits, int[] palette, long[] codes) {
        ByteBuf output = Unpooled.buffer();
        output.writeByte(bits);
        Wire.writeVarInt(output, palette.length);
        for (int id : palette) Wire.writeVarInt(output, id);
        int perWord = 64 / bits;
        for (int word = 0; word < (64 + perWord - 1) / perWord; word++) {
            long value = 0;
            for (int i = 0; i < perWord && word * perWord + i < 64; i++)
                value |= codes[word * perWord + i] << (i * bits);
            output.writeLong(value);
        }
        return drained(output);
    }

    private static byte[] drained(ByteBuf output) {
        byte[] bytes = new byte[output.readableBytes()];
        output.readBytes(bytes);
        output.release();
        return bytes;
    }
}
