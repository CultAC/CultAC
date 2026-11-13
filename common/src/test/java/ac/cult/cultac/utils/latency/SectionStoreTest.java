package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * A block container read through the section store reads exactly as a full read of the same container on the same
 * connection, on any input; entries are never written, and a write to a section sharing an entry's storage reaches no
 * other section.
 */
class SectionStoreTest {
    private static final int STATES = DataTables.defaults().registry().stateCount();
    private static final int SOURCE = 30_000;
    private static final ProtocolVersion[] VERSIONS = {
        ProtocolVersion.V1_21_4, ProtocolVersion.V1_21_5, ProtocolVersion.V26_1, ProtocolVersion.V26_3
    };

    @Test
    void aStoredReadMatchesTheFullReadOnEveryInput() {
        SectionStore.clear();
        var random = new SplittableRandom(0x5702EL);
        int shared = 0, missed = 0, failed = 0;
        var offered = new ArrayList<byte[]>();
        for (int round = 0; round < 6000; round++) {
            if (round % 64 == 0) {
                SectionStore.clear();
                offered.clear();
            }
            var version = VERSIONS[random.nextInt(VERSIONS.length)];
            int target = round % 17 == 0 ? 2 + random.nextInt(300) : STATES - random.nextInt(50);
            int offset = random.nextInt(SOURCE), failing = round % 7 == 0 ? random.nextInt(SOURCE) : -1;
            IntUnaryOperator mapper = id -> {
                if (id == failing) throw new IllegalStateException("Unmapped " + id);
                return id % 4999 == 0 ? target + id % 3 : (id + offset) % target;
            };
            byte[] storedWire = round % 4 == 3 && !offered.isEmpty()
                    ? offered.get(random.nextInt(offered.size()))
                    : section(random, version, round % 9 == 0);
            offer(storedWire, version, mapper, target);
            offered.add(storedWire);
            byte[] wire = variant(random, storedWire, version, round);
            var entriesBefore = held();
            List<ByteBuf> fulls = buffers(wire), storeds = buffers(wire);
            for (int kind = 0; kind < fulls.size(); kind++) {
                ByteBuf full = fulls.get(kind), input = storeds.get(kind);
                try {
                    long hits = SectionStore.hitCounter.sum();
                    String expected = outcome(() -> PalettedSection.readBlocks(full, version, SOURCE, mapper, target));
                    String actual =
                            outcome(() -> PalettedSection.readBlocks(input, version, SOURCE, mapper, target, null));
                    assertEquals(expected, actual, "round " + round);
                    assertEquals(full.readerIndex(), input.readerIndex(), "round " + round);
                    if (SectionStore.hitCounter.sum() > hits) shared++;
                    else if (expected.startsWith("java.") || expected.startsWith("ac.")) failed++;
                    else missed++;
                } finally {
                    full.release();
                    input.release();
                }
            }
            assertUnchanged(entriesBefore);
        }
        assertTrue(shared > 3000, "shared " + shared);
        assertTrue(missed > 2000, "missed " + missed);
        assertTrue(failed > 500, "failed " + failed);
    }

    @Test
    void everyReadPathConsultsTheStoreAfterThePoolAndReadsAsTheFullRead() {
        SectionStore.clear();
        var random = new SplittableRandom(0xA11L);
        int hits = 0;
        for (int round = 0; round < 600; round++) {
            byte[] wire = local(random, 4 + random.nextInt(5));
            var expected = outcome(() -> PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire)));
            long before = SectionStore.hitCounter.sum();
            var paths = new ArrayList<Supplier<PalettedSection>>();
            paths.add(() -> PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null));
            paths.add(() -> PalettedSection.readCanonicalBlocks(
                    Unpooled.wrappedBuffer(wire), null, new SectionPool.ReadCounts()));
            paths.add(() -> PalettedSection.readCanonicalBlocksPooled(
                            Unpooled.wrappedBuffer(wire),
                            ProtocolVersion.V26_3,
                            STATES,
                            IntUnaryOperator.identity(),
                            STATES,
                            null)
                    .states());
            paths.add(() -> PalettedSection.readBlocks(
                    Unpooled.wrappedBuffer(wire),
                    ProtocolVersion.V26_3,
                    STATES,
                    IntUnaryOperator.identity(),
                    STATES,
                    new SectionPool()));
            for (int path = 0; path < paths.size(); path++) {
                var read = paths.get(path).get();
                assertEquals(expected, outcome(() -> read), "round " + round + " path " + path);
            }
            hits += (int) (SectionStore.hitCounter.sum() - before);
        }
        assertEquals(600 * 3, hits, "after the first path stores it, every later path shares the entry");
    }

    @Test
    void aSectionIsSharedAcrossMappersOnlyWhenItsMappedContentsAreEqual() {
        SectionStore.clear();
        var random = new SplittableRandom(0xB0BL);
        for (int round = 0; round < 400; round++) {
            byte[] wire = sourceLocal(random, 4 + random.nextInt(5));
            int[] palette = palette(wire);
            int offset = 1 + random.nextInt(1000);
            IntUnaryOperator first = id -> (id + offset) % STATES;
            int differing = palette[random.nextInt(palette.length)];
            IntUnaryOperator agreeing = id -> contains(palette, id) ? first.applyAsInt(id) : (id * 7) % STATES;
            IntUnaryOperator disagreeing =
                    id -> id == differing ? (first.applyAsInt(id) + 1) % STATES : first.applyAsInt(id);
            var version = round % 2 == 0 ? ProtocolVersion.V1_21_5 : ProtocolVersion.V26_3;
            for (var mapper : List.of(first, agreeing, disagreeing)) {
                for (int target : new int[] {STATES, STATES - 1}) {
                    for (int source : new int[] {SOURCE, maximum(palette)}) {
                        SectionStore.clear();
                        offer(wire, version, first, STATES);
                        long hits = SectionStore.hitCounter.sum();
                        String expected = outcome(() -> PalettedSection.readBlocks(
                                Unpooled.wrappedBuffer(wire), version, source, mapper, target));
                        String actual = outcome(() -> PalettedSection.readBlocks(
                                Unpooled.wrappedBuffer(wire), version, source, mapper, target, null));
                        assertEquals(expected, actual, "round " + round);
                        boolean hit = SectionStore.hitCounter.sum() > hits;
                        boolean same = mapper != disagreeing && target == STATES && source == SOURCE;
                        assertEquals(same, hit, "round " + round + " target " + target + " source " + source);
                    }
                }
            }
        }
    }

    @Test
    void aFingerprintCollisionNeverShares() {
        SectionStore.clear();
        var random = new SplittableRandom(0xC011L);
        int compared = 0;
        for (int round = 0; round < 1500; round++) {
            if (round % 64 == 0) SectionStore.clear();
            byte[] wire = local(random, 4 + random.nextInt(5));
            long fingerprint = SectionStore.fingerprint(Unpooled.wrappedBuffer(wire), false);
            assertNotEquals(SectionStore.NOT_STORED, fingerprint);
            byte[] near = nearMiss(random, wire, round % 6);
            var other = PalettedSection.readBlocks(Unpooled.wrappedBuffer(near));
            // Both ways of the set hold a different container under this container's fingerprint.
            SectionStore.offer(fingerprint, other);
            SectionStore.offer(
                    fingerprint, PalettedSection.readBlocks(Unpooled.wrappedBuffer(nearMiss(random, wire, 0))));
            var entries = held();
            long candidates = SectionStore.candidateCounter.sum(), hits = SectionStore.hitCounter.sum();
            var input = Unpooled.wrappedBuffer(wire);
            var read = PalettedSection.readBlocks(input, (SectionPool) null);
            assertEquals(outcome(() -> PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire))), outcome(() -> read));
            assertEquals(wire.length, input.readerIndex());
            assertEquals(hits, SectionStore.hitCounter.sum(), "round " + round);
            compared += (int) (SectionStore.candidateCounter.sum() - candidates);
            assertUnchanged(entries);
        }
        assertTrue(compared >= 2 * 1500, "compared " + compared);
    }

    @Test
    void writesToSectionsSharingAnEntryReachNoOtherSection() {
        SectionStore.clear();
        var random = new SplittableRandom(0x1501L);
        for (int round = 0; round < 500; round++) {
            byte[] wire = local(random, 4 + random.nextInt(5));
            int[] original = values(PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire)));
            var owner = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
            var second = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
            var third = PalettedSection.readCanonicalBlocks(
                    Unpooled.wrappedBuffer(wire), null, new SectionPool.ReadCounts());
            var sections = List.of(owner, second, third);
            var references = new ArrayList<int[]>();
            for (int i = 0; i < 3; i++) references.add(original.clone());
            var entries = held();
            int writer = random.nextInt(3);
            int writes = round % 5 == 0 ? 4096 : 1 + random.nextInt(round % 2 == 0 ? 3 : 400);
            for (int i = 0; i < writes; i++) {
                int index = round % 5 == 0 ? i : random.nextInt(4096);
                int state = random.nextInt(8) == 0 ? random.nextInt(STATES) : original[random.nextInt(4096)];
                assertEquals(references.get(writer)[index], sections.get(writer).getAndSet(index, state));
                references.get(writer)[index] = state;
            }
            for (int i = 0; i < 3; i++) assertArrayEquals(references.get(i), values(sections.get(i)), "round " + round);
            assertUnchanged(entries);
            var later = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
            assertArrayEquals(original, values(later), "round " + round);
        }
    }

    @Test
    void aStoredSectionRefusesEveryWrite() {
        SectionStore.clear();
        byte[] wire = local(new SplittableRandom(7), 5);
        PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
        var stored = entries().getFirst().section();
        assertThrows(IllegalStateException.class, () -> stored.getAndSet(0, 1));
        assertThrows(IllegalStateException.class, stored::copyOnWrite);
        assertThrows(IllegalStateException.class, stored::copyOnWriteFromPool);
        assertNull(stored.storeTwin());
        var view = stored.storeView();
        assertNotSame(stored, view);
        assertEquals(outcome(() -> PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire))), outcome(() -> view));
    }

    @Test
    void onlyLocalMultiEntryBlockContainersAreStored() {
        SectionStore.clear();
        var random = new SplittableRandom(0x0FFEL);
        byte[] single = {0, 1};
        byte[] global = global(random);
        byte[] biome = biome(random);
        for (byte[] wire : List.of(single, global)) {
            long offers = SectionStore.offerCounter.sum();
            assertEquals(SectionStore.NOT_STORED, SectionStore.fingerprint(Unpooled.wrappedBuffer(wire), false));
            PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
            assertEquals(offers, SectionStore.offerCounter.sum());
        }
        long offers = SectionStore.offerCounter.sum();
        PalettedSection.readBiomes(Unpooled.wrappedBuffer(biome), 64);
        PalettedSection.readBiomeValues(Unpooled.wrappedBuffer(biome), 64);
        assertEquals(offers, SectionStore.offerCounter.sum());
        assertEquals(0, SectionStore.size());
        // A 1.21.4 container whose word count the read discards leaves zeroed storage, which is never stored.
        byte[] discarded = lengthPrefixed(local(random, 4), 1);
        assertEquals(SectionStore.NOT_STORED, SectionStore.fingerprint(Unpooled.wrappedBuffer(discarded), true));
        assertEquals(0, SectionStore.size());
    }

    @Test
    void anEntryHoldsOnlyPrimitiveArraysAndClearDropsEveryEntry() throws Exception {
        SectionStore.clear();
        var random = new SplittableRandom(0xE17L);
        for (int i = 0; i < 2 * SectionStore.SETS * SectionStore.WAYS; i++)
            PalettedSection.readBlocks(Unpooled.wrappedBuffer(local(random, 4 + i % 5)), (SectionPool) null);
        assertTrue(SectionStore.size() <= SectionStore.SETS * SectionStore.WAYS);
        assertTrue(SectionStore.size() > SectionStore.SETS, "held " + SectionStore.size());
        var components = new ArrayList<Class<?>>();
        for (var component : SectionStore.Entry.class.getRecordComponents()) components.add(component.getType());
        assertEquals(List.of(long.class, PalettedSection.class), components);
        for (var entry : entries()) {
            for (Field field : PalettedSection.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(entry.section());
                Class<?> type = field.getType();
                if (type == Int2IntOpenHashMap.class) assertNull(value, "a stored section builds no index");
                else assertTrue(type.isPrimitive() || type == int[].class || type == long[].class, field.getName());
            }
        }
        SectionStore.clear();
        assertEquals(0, SectionStore.size());
        assertTrue(entries().isEmpty());
    }

    @Test
    void storeCountersCountLookupsCandidatesHitsOffersAndExtraClones() {
        SectionStore.clear();
        var random = new SplittableRandom(0xC0DE5L);
        byte[] wire = local(random, 5);
        long[] start = counters();
        var owner = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
        var view = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), (SectionPool) null);
        PalettedSection.readBlocks(Unpooled.wrappedBuffer(new byte[] {0, 1}), (SectionPool) null);
        owner.getAndSet(0, owner.get(0) == 1 ? 2 : 1);
        owner.getAndSet(1, owner.get(1) == 1 ? 2 : 1);
        view.copyOnWrite().getAndSet(0, 1);
        view.getAndSet(0, view.get(0) == 1 ? 2 : 1);
        // lookups, candidates, hits, offers, clones an unshared read would not make
        assertArrayEquals(new long[] {2, 1, 1, 1, 1}, since(start));
    }

    @Test
    void poolCountersReadAsWithoutTheStore() {
        SectionStore.clear();
        var random = new SplittableRandom(0x9001L);
        byte[] wire = local(random, 5);
        var pool = new SectionPool();
        long fullReads = SectionPool.multiValueFullReadCounter.sum(), clones = SectionPool.sharedCloneCounter.sum();
        var owner = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), pool);
        var stored = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), pool);
        assertEquals(2, SectionPool.multiValueFullReadCounter.sum() - fullReads, "a store hit is a pool miss");
        var section = pool.internRetained(new CachedSection(owner));
        var view = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), pool);
        assertTrue(pool.detachForSingleReferenceMutation(section));
        section.states().getAndSet(0, section.states().get(0) == 1 ? 2 : 1);
        view.getAndSet(0, 1);
        stored.getAndSet(0, 1);
        assertEquals(2, SectionPool.sharedCloneCounter.sum() - clones, "the pooled section and the view, as before");
    }

    @Test
    void threadsOfferingHittingAndWritingEachSeeTheirOwnContents() throws Exception {
        SectionStore.clear();
        var random = new SplittableRandom(0x57E55L);
        int contents = 48;
        var wires = new ArrayList<byte[]>();
        var references = new ArrayList<int[]>();
        var written = new ArrayList<String>();
        for (int i = 0; i < contents; i++) {
            byte[] wire = local(random, 4 + i % 5);
            wires.add(wire);
            var read = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire));
            references.add(values(read));
            written.add(hex(written(read)));
        }
        int threads = Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        var failure = new AtomicReference<Throwable>();
        var barrier = new CyclicBarrier(threads);
        long hitsBefore = SectionStore.hitCounter.sum();
        var workers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            long seed = random.nextLong();
            var worker = new Thread(() -> {
                var own = new SplittableRandom(seed);
                try {
                    barrier.await();
                    int loops = 0;
                    var seen = new IdentityHashMap<PalettedSection, String>();
                    while (System.nanoTime() < deadline || loops < 200) {
                        loops++;
                        int content = own.nextInt(contents);
                        byte[] wire = wires.get(content);
                        int[] reference = references.get(content);
                        if (own.nextInt(8) == 0) {
                            // A container only this thread holds: stored by its read, shared by its next.
                            wire = local(own, 4 + own.nextInt(5));
                            reference = values(PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire)));
                            assertArrayEquals(
                                    reference,
                                    values(PalettedSection.readBlocks(
                                            Unpooled.wrappedBuffer(wire), (SectionPool) null)));
                        }
                        ByteBuf input = own.nextBoolean()
                                ? Unpooled.wrappedBuffer(wire)
                                : ByteBufAllocator.DEFAULT
                                        .directBuffer(wire.length)
                                        .writeBytes(wire);
                        PalettedSection read;
                        try {
                            read = own.nextBoolean()
                                    ? PalettedSection.readBlocks(input, (SectionPool) null)
                                    : PalettedSection.readCanonicalBlocks(input, null, new SectionPool.ReadCounts());
                        } finally {
                            input.release();
                        }
                        int[] expected = reference.clone();
                        assertArrayEquals(expected, values(read));
                        for (int i = 0, writes = own.nextInt(4) == 0 ? 0 : 1 + own.nextInt(64); i < writes; i++) {
                            int index = own.nextInt(4096);
                            int state = own.nextInt(STATES);
                            assertEquals(expected[index], read.getAndSet(index, state));
                            expected[index] = state;
                        }
                        assertArrayEquals(expected, values(read));
                        if (loops % 64 == 0) {
                            for (var entry : entries()) {
                                byte[] bytes = written(entry.section());
                                assertEquals(
                                        entry.fingerprint(),
                                        SectionStore.fingerprint(Unpooled.wrappedBuffer(bytes), false),
                                        "an entry pairs a fingerprint with another container");
                                String now = hex(bytes) + fields(entry.section());
                                assertEquals(seen.computeIfAbsent(entry.section(), section -> now), now);
                            }
                        }
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (var worker : workers) worker.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        for (int i = 0; i < contents; i++) {
            var read = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wires.get(i)), (SectionPool) null);
            assertEquals(written.get(i), hex(written(read)));
        }
        assertTrue(SectionStore.hitCounter.sum() - hitsBefore > 1000, "hits");
    }

    private static void offer(byte[] wire, ProtocolVersion version, IntUnaryOperator mapper, int target) {
        try {
            PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire), version, SOURCE, mapper, target, null);
        } catch (RuntimeException rejected) {
            // A container the read rejects stores nothing.
        }
    }

    @SuppressWarnings("unchecked")
    static List<SectionStore.Entry> entries() {
        try {
            Field field = SectionStore.class.getDeclaredField("ENTRIES");
            field.setAccessible(true);
            var array = (AtomicReferenceArray<SectionStore.Entry>) field.get(null);
            var entries = new ArrayList<SectionStore.Entry>();
            for (int i = 0; i < array.length(); i++) if (array.get(i) != null) entries.add(array.get(i));
            return entries;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** The entries held now, each with its written bytes and every field. */
    private record Held(List<SectionStore.Entry> entries, List<String> snapshot) {}

    private static Held held() {
        var entries = entries();
        return new Held(entries, snapshot(entries));
    }

    private static List<String> snapshot(List<SectionStore.Entry> entries) {
        var snapshot = new ArrayList<String>();
        for (var entry : entries)
            snapshot.add(entry.fingerprint() + ":" + hex(written(entry.section())) + ":" + fields(entry.section()));
        return snapshot;
    }

    /** The entries {@code before} held still hold the same contents and fields. */
    private static void assertUnchanged(Held before) {
        assertEquals(before.snapshot(), snapshot(before.entries()), "a stored section changed");
    }

    private static String fields(PalettedSection section) {
        var out = new StringBuilder();
        for (Field field : PalettedSection.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            try {
                Object value = field.get(section);
                out.append(field.getName()).append('=');
                if (value instanceof int[] ints) out.append(Arrays.toString(ints));
                else if (value instanceof long[] longs) out.append(Arrays.toString(longs));
                else out.append(value);
                out.append(' ');
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        return out.toString();
    }

    private static long[] counters() {
        return new long[] {
            SectionStore.lookupCounter.sum(),
            SectionStore.candidateCounter.sum(),
            SectionStore.hitCounter.sum(),
            SectionStore.offerCounter.sum(),
            SectionStore.cloneCounter.sum()
        };
    }

    private static long[] since(long[] start) {
        var now = counters();
        for (int i = 0; i < now.length; i++) now[i] -= start[i];
        return now;
    }

    private static int[] values(PalettedSection section) {
        int[] values = new int[4096];
        for (int i = 0; i < values.length; i++) values[i] = section.get(i);
        return values;
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) if (candidate == value) return true;
        return false;
    }

    private static int maximum(int[] values) {
        int maximum = 0;
        for (int value : values) maximum = Math.max(maximum, value);
        return maximum;
    }

    /** The palette of a local container. */
    private static int[] palette(byte[] wire) {
        var input = Unpooled.wrappedBuffer(wire);
        input.readByte();
        int[] palette = new int[Wire.readVarInt(input)];
        for (int i = 0; i < palette.length; i++) palette[i] = Wire.readVarInt(input);
        return palette;
    }

    /** A local 26.3 container of model states as a server writes it, at width {@code bits}, 2+ distinct states. */
    private static byte[] local(SplittableRandom random, int bits) {
        return localOf(random, bits, STATES);
    }

    /** As {@link #local}, in source IDs below {@link #SOURCE}. */
    private static byte[] sourceLocal(SplittableRandom random, int bits) {
        return localOf(random, bits, SOURCE);
    }

    private static byte[] localOf(SplittableRandom random, int bits, int registry) {
        int[] values = new int[4096];
        int distinct = 2 + random.nextInt((1 << bits) - 1);
        int[] mix = new int[distinct];
        for (int i = 0; i < distinct; i++) mix[i] = random.nextInt(registry);
        for (int i = 0; i < values.length; i++) values[i] = i < distinct ? mix[i] : mix[random.nextInt(distinct)];
        var palette = new LinkedHashMap<Integer, Integer>();
        for (int value : values) palette.putIfAbsent(value, palette.size());
        int width = Math.max(bits, Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1)));
        if (width > 8) return localOf(random, bits, registry);
        ByteBuf out = Unpooled.buffer();
        try {
            out.writeByte(width);
            Wire.writeVarInt(out, palette.size());
            for (int value : palette.keySet()) Wire.writeVarInt(out, value);
            int perWord = 64 / width;
            long[] words = new long[(4096 + perWord - 1) / perWord];
            for (int i = 0; i < values.length; i++)
                words[i / perWord] |= (long) palette.get(values[i]) << (i % perWord * width);
            for (long word : words) out.writeLong(word);
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    /** A copy of a 26.3 container written with a word array length prefix of {@code extra} more words. */
    private static byte[] lengthPrefixed(byte[] wire, int extra) {
        var input = Unpooled.wrappedBuffer(wire);
        int bits = input.readByte();
        int entries = Wire.readVarInt(input);
        for (int i = 0; i < entries; i++) Wire.readVarInt(input);
        int wordsAt = input.readerIndex();
        int perWord = 64 / bits;
        ByteBuf out = Unpooled.buffer();
        try {
            out.writeBytes(wire, 0, wordsAt);
            Wire.writeVarInt(out, (4096 + perWord - 1) / perWord + extra);
            out.writeBytes(wire, wordsAt, wire.length - wordsAt);
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    private static byte[] global(SplittableRandom random) {
        ByteBuf out = Unpooled.buffer();
        try {
            int bits = 32 - Integer.numberOfLeadingZeros(STATES - 1);
            out.writeByte(bits);
            int perWord = 64 / bits;
            for (int word = 0, index = 0; word < (4096 + perWord - 1) / perWord; word++) {
                long value = 0;
                for (int slot = 0; slot < perWord && index < 4096; slot++, index++)
                    value |= (long) random.nextInt(STATES) << (slot * bits);
                out.writeLong(value);
            }
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    private static byte[] biome(SplittableRandom random) {
        ByteBuf out = Unpooled.buffer();
        try {
            out.writeByte(2);
            Wire.writeVarInt(out, 4);
            for (int i = 0; i < 4; i++) Wire.writeVarInt(out, random.nextInt(64));
            out.writeLong(random.nextLong());
            out.writeLong(random.nextLong());
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    /**
     * {@code wire} with one observable difference: a word, a palette entry, one palette entry more, the next wider bit
     * width with the same values, a wider palette capacity, or a different last word.
     */
    private static byte[] nearMiss(SplittableRandom random, byte[] wire, int kind) {
        var read = PalettedSection.readBlocks(Unpooled.wrappedBuffer(wire));
        int[] values = values(read);
        int[] palette = palette(wire);
        int bits = wire[0];
        return switch (kind) {
            case 0 -> {
                byte[] changed = wire.clone();
                int word = changed.length - 8 * (1 + random.nextInt(64 * 4 / bits));
                changed[word + random.nextInt(8)] ^= (byte) (1 << random.nextInt(8));
                yield changed;
            }
            case 1 -> {
                int[] remapped = values.clone();
                int victim = palette[random.nextInt(palette.length)];
                int replacement = (victim + 1 + random.nextInt(STATES - 1)) % STATES;
                while (contains(palette, replacement)) replacement = (replacement + 1) % STATES;
                for (int i = 0; i < remapped.length; i++) if (remapped[i] == victim) remapped[i] = replacement;
                yield written(remapped, bits, palette.length);
            }
            case 2 -> {
                if (palette.length == 1 << bits) yield written(values, Math.min(8, bits + 1), palette.length + 1);
                yield written(values, bits, palette.length + 1);
            }
            case 3, 4 -> bits == 8 ? written(values, 8, palette.length + 1) : written(values, bits + 1, palette.length);
            default -> {
                byte[] changed = wire.clone();
                changed[changed.length - 1 - random.nextInt(8)] ^= 1;
                yield changed;
            }
        };
    }

    /**
     * {@code values} as a local container of width {@code bits} whose palette is the first-seen order of the values
     * followed by unused states up to {@code entries} entries.
     */
    private static byte[] written(int[] values, int bits, int entries) {
        var palette = new LinkedHashMap<Integer, Integer>();
        for (int value : values) palette.putIfAbsent(value, palette.size());
        for (int state = 1; palette.size() < entries; state++) palette.putIfAbsent(state, palette.size());
        ByteBuf out = Unpooled.buffer();
        try {
            out.writeByte(bits);
            Wire.writeVarInt(out, palette.size());
            for (int value : palette.keySet()) Wire.writeVarInt(out, value);
            int perWord = 64 / bits;
            long[] words = new long[(4096 + perWord - 1) / perWord];
            for (int i = 0; i < values.length; i++)
                words[i / perWord] |= (long) palette.get(values[i]) << (i % perWord * bits);
            for (long word : words) out.writeLong(word);
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    /** The stored wire itself, or a copy with one change: a byte, a truncation or trailing bytes. */
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

    /** The section's written bytes, every value and every field but the sharing flags, or the failure. */
    private static String outcome(Supplier<PalettedSection> read) {
        try {
            var section = read.get();
            var values = new StringBuilder(hex(written(section)));
            for (int i = 0; i < 4096; i++) values.append(',').append(section.get(i));
            values.append(' ').append(observable(section));
            return values.toString();
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    /** Every field a reader of the section sees; storage sharing and its flags are left out. */
    static String observable(PalettedSection section) {
        var out = new StringBuilder();
        for (Field field : PalettedSection.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName();
            if (name.equals("sharedStorage") || name.equals("sharedByPool") || name.equals("sharedByStore")) continue;
            field.setAccessible(true);
            try {
                Object value = field.get(section);
                out.append(name).append('=');
                if (value instanceof int[] ints) out.append(Arrays.toString(ints));
                else if (value instanceof long[] longs) out.append(Arrays.toString(longs));
                else out.append(value);
                out.append(' ');
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        return out.toString();
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

    static byte[] written(PalettedSection section) {
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

    static String hex(byte[] bytes) {
        var text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
            text.append(Character.forDigit(value >> 4 & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }
}
