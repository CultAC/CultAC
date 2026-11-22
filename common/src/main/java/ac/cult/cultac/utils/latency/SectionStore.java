package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntUnaryOperator;

/**
 * Block storage of local multi-entry containers that full reads produced, shared by content with later reads of the
 * same container on any connection. An entry is immutable and holds only primitive arrays; a read shares its storage
 * only when {@link PalettedSection#skipIfEncodes} finds that the container, read and remapped as the read would, equals
 * the entry in every field. The fingerprint only picks the set to compare. It also holds the value arrays of
 * multi-entry 64-cell sections, each shared only with a section whose every value equals it.
 */
public final class SectionStore {
    static final int SETS = 4096, WAYS = 2;
    static final long NOT_STORED = 0;
    private static final int FINGERPRINT_WORDS = 8;
    private static final AtomicReferenceArray<Entry> ENTRIES = new AtomicReferenceArray<>(SETS * WAYS);
    static final int VALUE_SETS = 2048;
    private static final AtomicReferenceArray<Values> VALUES = new AtomicReferenceArray<>(VALUE_SETS * WAYS);

    /** Containers whose fingerprint was looked up. */
    public static final LongAdder lookupCounter = new LongAdder();
    /** Entries compared with a looked-up container. */
    public static final LongAdder candidateCounter = new LongAdder();
    /** Looked-up containers that share an entry's storage. */
    public static final LongAdder hitCounter = new LongAdder();
    /** Full reads that became entries. */
    public static final LongAdder offerCounter = new LongAdder();
    /** Storage clones on a write that an unshared read would have written in place. */
    public static final LongAdder cloneCounter = new LongAdder();
    /** Multi-entry 64-cell sections whose values were looked up. */
    public static final LongAdder valueLookupCounter = new LongAdder();
    /** Looked-up sections given a stored array. */
    public static final LongAdder valueHitCounter = new LongAdder();

    private SectionStore() {}

    /** One stored section and the fingerprint of the container it was read from. */
    record Entry(long fingerprint, PalettedSection section) {}

    /** The values of a 64-cell section, never written, and the fingerprint of the storage they were read from. */
    record Values(long fingerprint, int[] values) {}

    /**
     * A hash of the local multi-entry block container at the reader index, its word array length-prefixed when
     * {@code lengthPrefixed}; {@link #NOT_STORED} for any other container, a truncated one, and one whose words a read
     * would leave zeroed. The reader index is unchanged.
     */
    static long fingerprint(ByteBuf input, boolean lengthPrefixed) {
        int start = input.readerIndex();
        if (!input.isReadable()) return NOT_STORED;
        int wireBits = input.getByte(start);
        if (wireBits < 1 || wireBits > 8) return NOT_STORED;
        try {
            input.skipBytes(1);
            int bits = Math.max(4, wireBits);
            int entries = Wire.readLength(input, bits <= 4 ? 1 << bits : input.readableBytes());
            if (entries == 0) return NOT_STORED;
            long hash = mix(wireBits | (lengthPrefixed ? 1L << 8 : 0) | (long) entries << 16);
            for (int i = 0; i < entries; i++) hash = mix(hash ^ (Wire.readVarInt(input) & 0xFFFFFFFFL));
            int perWord = 64 / bits, length = (4096 + perWord - 1) / perWord;
            if (lengthPrefixed && Wire.readLength(input, input.readableBytes() / Long.BYTES) != length)
                return NOT_STORED;
            if (input.readableBytes() < length * Long.BYTES) return NOT_STORED;
            int words = input.readerIndex();
            for (int i = 0; i < FINGERPRINT_WORDS; i++) {
                int word = i * (length - 1) / (FINGERPRINT_WORDS - 1);
                hash = mix(hash ^ input.getLong(words + word * Long.BYTES));
            }
            return hash == NOT_STORED ? 1 : hash;
        } catch (RuntimeException malformed) {
            return NOT_STORED;
        } finally {
            input.readerIndex(start);
        }
    }

    static long mix(long value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        return mixed ^ mixed >>> 29;
    }

    /**
     * A section sharing the storage of the entry that the container at the reader index, with {@code fingerprint},
     * holds exactly, read past that container; null, with the reader index unchanged, when no entry does.
     * {@code mapper} must be pure and non-blocking.
     */
    static PalettedSection shared(
            ByteBuf input,
            boolean lengthPrefixed,
            long fingerprint,
            int sourceRegistrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize) {
        if (fingerprint == NOT_STORED) return null;
        lookupCounter.increment();
        int set = set(fingerprint);
        for (int way = 0; way < WAYS; way++) {
            Entry entry = ENTRIES.get(set + way);
            if (entry == null || entry.fingerprint() != fingerprint) continue;
            candidateCounter.increment();
            var section = entry.section();
            int result = section.skipIfEncodes(
                    input,
                    lengthPrefixed,
                    sourceRegistrySize,
                    mapper,
                    targetRegistrySize,
                    PalettedSection.NO_WORD_COPY);
            if (result == PalettedSection.MATCHED) {
                hitCounter.increment();
                return section.storeView();
            }
        }
        return null;
    }

    /**
     * Stores {@code read}, the calling thread's own full read of the container with {@code fingerprint}, which then
     * shares its storage with the entry; nothing for {@link #NOT_STORED} or a section the store does not hold.
     */
    static void offer(long fingerprint, PalettedSection read) {
        if (fingerprint == NOT_STORED) return;
        var twin = read.storeTwin();
        if (twin == null) return;
        int set = set(fingerprint);
        Entry newest = ENTRIES.get(set);
        if (newest != null) ENTRIES.set(set + 1, newest);
        ENTRIES.set(set, new Entry(fingerprint, twin));
        offerCounter.increment();
    }

    private static int set(long fingerprint) {
        return (int) (fingerprint >>> 40 & SETS - 1) * WAYS;
    }

    /**
     * The stored array that holds exactly {@code section}'s values, compared index by index; otherwise a new array of
     * them, which then becomes the newest entry of its set. {@code fingerprint} only picks the set and the candidates.
     */
    static int[] values(PalettedSection section, long fingerprint) {
        valueLookupCounter.increment();
        int set = (int) (fingerprint >>> 40 & VALUE_SETS - 1) * WAYS;
        for (int way = 0; way < WAYS; way++) {
            Values entry = VALUES.get(set + way);
            if (entry != null && entry.fingerprint() == fingerprint && section.holdsValues(entry.values())) {
                valueHitCounter.increment();
                return entry.values();
            }
        }
        int[] values = section.values();
        Values newest = VALUES.get(set);
        if (newest != null) VALUES.set(set + 1, newest);
        VALUES.set(set, new Values(fingerprint, values));
        return values;
    }

    /** Drops every entry; reads after it share nothing until new full reads are stored. */
    public static void clear() {
        for (int i = 0; i < ENTRIES.length(); i++) ENTRIES.set(i, null);
        for (int i = 0; i < VALUES.length(); i++) VALUES.set(i, null);
    }

    /** Entries held now. */
    public static int size() {
        int held = 0;
        for (int i = 0; i < ENTRIES.length(); i++) if (ENTRIES.get(i) != null) held++;
        return held;
    }
}
