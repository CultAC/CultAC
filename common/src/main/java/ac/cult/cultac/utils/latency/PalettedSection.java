/*
 * Palette growth and packed-long layout adapted from PacketEvents DataPalette
 * and BitStorage at 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Model IDs whose storage a section pool may share across connections; shared storage is copied before mutation. A
 * canonical section, which the canonical reads give for a single-entry container, is shared between threads: it is
 * never written, {@link #getAndSet} on it throws and {@link #copyOnWrite} gives an own copy.
 */
public final class PalettedSection {
    private static final int BLOCK_STATES = DataTables.defaults().registry().stateCount();
    private static final AtomicReferenceArray<PalettedSection> BLOCK_SINGLES = new AtomicReferenceArray<>(BLOCK_STATES);
    private static final int BIOME_IDS = 1024, BIOME_WAYS = 4;
    private static final AtomicReferenceArray<PalettedSection> BIOME_SINGLES =
            new AtomicReferenceArray<>(BIOME_IDS * BIOME_WAYS);
    private static final int[] VALUES_PER_WORD = new int[33];
    private static final long[] WORD_RECIPROCALS = new long[33];
    private static final long ABSENT_CODE_WEIGHT = 1L << 50;
    private static final long[] NO_WORDS = new long[0];
    private static final VarHandle BIG_ENDIAN_LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    // Host-owned threads outlive a runtime generation, so their map holds only host-loaded types.
    private static final FastThreadLocal<WeakReference<byte[]>> WORD_BYTES = new FastThreadLocal<>();
    private static final FastThreadLocal<WeakReference<long[]>> CODE_WEIGHTS = new FastThreadLocal<>();
    private static final int SCRATCH_BYTES = 1024 * Long.BYTES;
    private static final int SCRATCH_CODES = 1 << 8;
    static final int MATCHED = -2;
    static final int NO_WORD_COPY = -1;

    static {
        for (int bits = 1; bits <= 32; bits++) {
            int perWord = 64 / bits;
            VALUES_PER_WORD[bits] = perWord;
            WORD_RECIPROCALS[bits] = ((1L << 32) + perWord - 1) / perWord;
        }
    }

    private final int size, minimumBits, maximumLocalBits;
    private int registrySize, bits, paletteSize;
    private int[] palette;
    private Int2IntOpenHashMap indices;
    private long[] words;
    private boolean sharedStorage;
    private boolean sharedByPool;
    private boolean canonical;

    private PalettedSection(int size, int minimumBits, int maximumLocalBits, int registrySize, int initial) {
        this(size, minimumBits, maximumLocalBits, registrySize, 0, new int[] {initial}, 1, NO_WORDS);
    }

    private PalettedSection(
            int size,
            int minimumBits,
            int maximumLocalBits,
            int registrySize,
            int bits,
            int[] palette,
            int paletteSize,
            long[] words) {
        this.size = size;
        this.minimumBits = minimumBits;
        this.maximumLocalBits = maximumLocalBits;
        this.registrySize = registrySize;
        this.bits = bits;
        this.palette = palette;
        this.paletteSize = paletteSize;
        this.words = words;
    }

    public static PalettedSection blocks() {
        return new PalettedSection(4096, 4, 8, BLOCK_STATES, 0);
    }

    /** Pinned 26.3 PalettedContainer.read: configuration chooses the in-memory width. */
    public static PalettedSection readBlocks(ByteBuf input) {
        return read(input, 4096, 4, 8, BLOCK_STATES, false);
    }

    public static int[] readBiomes(ByteBuf input, int registrySize) {
        var section = readCanonical(input, 64, 1, 3, registrySize, false, false, null, registrySize);
        int[] result = new int[64];
        for (int i = 0; i < result.length; i++) result[i] = section.get(i);
        return result;
    }

    /**
     * {@link #readBiomes(ByteBuf, int)}'s values; a single-entry palette gives {@link CachedChunk#uniformBiomes}'s
     * shared array, which no caller may write.
     */
    public static int[] readBiomeValues(ByteBuf input, int registrySize) {
        var section = readCanonical(input, 64, 1, 3, registrySize, false, false, null, registrySize);
        int single = section.singleValue();
        if (single >= 0) return CachedChunk.uniformBiomes(single);
        int[] result = new int[64];
        for (int i = 0; i < result.length; i++) result[i] = section.get(i);
        return result;
    }

    /** A block container in {@code version}'s framing; global entries use that version's registry size. */
    public static PalettedSection readBlocks(ByteBuf input, ProtocolVersion version, int registrySize) {
        return read(input, 4096, 4, 8, registrySize, !version.atLeast(ProtocolVersion.V1_21_5));
    }

    public static PalettedSection readBiomes(ByteBuf input, ProtocolVersion version, int registrySize) {
        return read(input, 64, 1, 3, registrySize, !version.atLeast(ProtocolVersion.V1_21_5));
    }

    /** {@link #readBlocks(ByteBuf, ProtocolVersion, int)} then {@link #remap}, remapping the read storage in place. */
    public static PalettedSection readBlocks(
            ByteBuf input, ProtocolVersion version, int registrySize, IntUnaryOperator mapper, int targetRegistrySize) {
        return readBlocks(input, version, registrySize).remap(mapper, targetRegistrySize, true);
    }

    /**
     * As {@link #readBlocks(ByteBuf, ProtocolVersion, int, IntUnaryOperator, int)}, sharing the storage of a section in
     * {@code pool} when the container holds exactly its contents. {@code mapper} must be pure and non-blocking: it runs
     * under the pool's lock, possibly once per pooled candidate.
     */
    public static PalettedSection readBlocks(
            ByteBuf input,
            ProtocolVersion version,
            int registrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize,
            SectionPool pool) {
        return readBlocksPooled(input, version, registrySize, mapper, targetRegistrySize, pool)
                .states();
    }

    /** A block container read; {@code pooledFluidCount} is that of the pooled section it shares storage with, or -1. */
    public record PooledRead(PalettedSection states, int pooledFluidCount) {}

    /**
     * As {@link #readBlocks(ByteBuf, ProtocolVersion, int, IntUnaryOperator, int, SectionPool)}, also giving a matched
     * pooled section's fluid count, which wires before 26.1 do not carry.
     */
    public static PooledRead readBlocksPooled(
            ByteBuf input,
            ProtocolVersion version,
            int registrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize,
            SectionPool pool) {
        return readBlocksPooled(input, version, registrySize, mapper, targetRegistrySize, pool, false);
    }

    /**
     * As {@link #readBlocksPooled(ByteBuf, ProtocolVersion, int, IntUnaryOperator, int, SectionPool)}; a single-entry
     * container gives a canonical section.
     */
    public static PooledRead readCanonicalBlocksPooled(
            ByteBuf input,
            ProtocolVersion version,
            int registrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize,
            SectionPool pool) {
        return readBlocksPooled(input, version, registrySize, mapper, targetRegistrySize, pool, true);
    }

    private static PooledRead readBlocksPooled(
            ByteBuf input,
            ProtocolVersion version,
            int registrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize,
            SectionPool pool,
            boolean canonical) {
        boolean lengthPrefixed = !version.atLeast(ProtocolVersion.V1_21_5);
        if (pool != null) {
            var shared = pool.sharedBlocks(input, lengthPrefixed, registrySize, mapper, targetRegistrySize);
            if (shared != null) return shared;
        }
        var read = canonical
                ? readCanonical(input, 4096, 4, 8, registrySize, lengthPrefixed, true, mapper, targetRegistrySize)
                : readBlocks(input, version, registrySize, mapper, targetRegistrySize);
        return new PooledRead(pool != null ? countedFullRead(read) : read, -1);
    }

    /** As {@link #readBlocks(ByteBuf)}, sharing the storage of a section in {@code pool} that the container holds. */
    public static PalettedSection readBlocks(ByteBuf input, SectionPool pool) {
        if (pool != null) {
            var shared = pool.sharedBlocks(input, false, BLOCK_STATES, IntUnaryOperator.identity(), BLOCK_STATES);
            if (shared != null) return shared.states();
            return countedFullRead(readBlocks(input));
        }
        return readBlocks(input);
    }

    private static PalettedSection countedFullRead(PalettedSection read) {
        if (read.bits != 0) SectionPool.multiValueFullReadCounter.increment();
        return read;
    }

    /** {@link #readBiomes(ByteBuf, ProtocolVersion, int)} then {@link #remap}, remapping the read storage in place. */
    public static PalettedSection readBiomes(
            ByteBuf input, ProtocolVersion version, int registrySize, IntUnaryOperator mapper, int targetRegistrySize) {
        return readBiomes(input, version, registrySize).remap(mapper, targetRegistrySize, true);
    }

    /**
     * As {@link #readBiomes(ByteBuf, ProtocolVersion, int, IntUnaryOperator, int)}; a single-entry container gives a
     * canonical section.
     */
    public static PalettedSection readCanonicalBiomes(
            ByteBuf input, ProtocolVersion version, int registrySize, IntUnaryOperator mapper, int targetRegistrySize) {
        return readCanonical(
                input,
                64,
                1,
                3,
                registrySize,
                !version.atLeast(ProtocolVersion.V1_21_5),
                true,
                mapper,
                targetRegistrySize);
    }

    /**
     * {@link #read} then, when {@code remap} holds, {@link #remap} in place: the same reads, mapper calls and failures
     * in the same order. A single-entry container gives the canonical section for its entry.
     */
    private static PalettedSection readCanonical(
            ByteBuf input,
            int size,
            int minimumBits,
            int maximumLocalBits,
            int registrySize,
            boolean lengthPrefixed,
            boolean remap,
            IntUnaryOperator mapper,
            int targetRegistrySize) {
        if (!input.isReadable() || input.getByte(input.readerIndex()) != 0) {
            var section = read(input, size, minimumBits, maximumLocalBits, registrySize, lengthPrefixed);
            return remap ? section.remap(mapper, targetRegistrySize, true) : section;
        }
        input.skipBytes(1);
        int value = readId(input, registrySize);
        if (lengthPrefixed) {
            int length = Wire.readLength(input, input.readableBytes() / Long.BYTES);
            if (length != 0) input.skipBytes(length * Long.BYTES);
        }
        if (!remap) return single(size, minimumBits, maximumLocalBits, registrySize, value);
        return single(
                size, minimumBits, maximumLocalBits, targetRegistrySize, mapped(mapper, value, targetRegistrySize));
    }

    /** The canonical section holding {@code value} at every index; published whole, so any thread may read it. */
    private static PalettedSection single(
            int size, int minimumBits, int maximumLocalBits, int registrySize, int value) {
        boolean blocks = size == 4096;
        var singles = blocks ? BLOCK_SINGLES : BIOME_SINGLES;
        int slot = blocks ? value : value < BIOME_IDS ? value * BIOME_WAYS + (registrySize & BIOME_WAYS - 1) : -1;
        boolean cached = slot >= 0 && slot < singles.length();
        var held = cached ? singles.get(slot) : null;
        if (held != null
                && held.size == size
                && held.minimumBits == minimumBits
                && held.maximumLocalBits == maximumLocalBits
                && held.registrySize == registrySize) return held;
        var created = new PalettedSection(size, minimumBits, maximumLocalBits, registrySize, value);
        created.sharedStorage = true;
        created.canonical = true;
        if (cached) singles.set(slot, created);
        return created;
    }

    private static PalettedSection read(
            ByteBuf input, int size, int minimumBits, int maximumLocalBits, int registrySize, boolean lengthPrefixed) {
        var section = new PalettedSection(size, minimumBits, maximumLocalBits, registrySize, 0, null, 0, NO_WORDS);
        int wireBits = input.readByte();
        section.configure(wireBits);
        if (section.bits == 0 && !section.isGlobal()) {
            section.palette[0] = readId(input, registrySize);
            section.paletteSize = 1;
        } else if (!section.isGlobal()) {
            section.paletteSize =
                    Wire.readLength(input, section.bits <= 4 ? section.palette.length : input.readableBytes());
            if (section.paletteSize > section.palette.length) section.palette = new int[section.paletteSize];
            for (int i = 0; i < section.paletteSize; i++) section.palette[i] = readId(input, registrySize);
        }
        if (lengthPrefixed) {
            // 1.21.2-1.21.4 PalettedContainer.read discards readLongArray's result when its
            // length differs from the configured storage, which then stays zeroed.
            int length = Wire.readLength(input, input.readableBytes() / Long.BYTES);
            if (length != section.words.length) {
                input.skipBytes(length * Long.BYTES);
                return section;
            }
        }
        // Since 1.21.5 arrays have a fixed length derived from the configuration.
        int bytes = section.words.length * Long.BYTES;
        if (bytes > 0 && input.readableBytes() >= bytes) {
            readWords(input, section.words);
            input.skipBytes(bytes);
        } else {
            for (int i = 0; i < section.words.length; i++) section.words[i] = input.readLong();
        }
        return section;
    }

    private static void readWords(ByteBuf input, long[] words) {
        int index = input.readerIndex();
        byte[] array = input.hasArray() ? input.array() : copiedBytes(input, index, words.length * Long.BYTES);
        if (array == null) {
            for (int i = 0; i < words.length; i++) words[i] = input.getLong(index + i * Long.BYTES);
            return;
        }
        int offset = input.hasArray() ? input.arrayOffset() + index : 0;
        for (int i = 0; i < words.length; i++) words[i] = (long) BIG_ENDIAN_LONGS.get(array, offset + i * Long.BYTES);
    }

    /**
     * This thread's copy of the {@code length} bytes at {@code index}, null when they exceed the scratch; only the
     * calling method reads it.
     */
    private static byte[] copiedBytes(ByteBuf input, int index, int length) {
        if (length > SCRATCH_BYTES) return null;
        byte[] bytes = heldBytes();
        if (bytes == null) {
            bytes = new byte[SCRATCH_BYTES];
            WORD_BYTES.set(new WeakReference<>(bytes));
        }
        input.getBytes(index, bytes, 0, length);
        return bytes;
    }

    private static byte[] heldBytes() {
        WeakReference<byte[]> reference = WORD_BYTES.get();
        return reference == null ? null : reference.get();
    }

    /** Pinned 26.3 PalettedContainer.write framing: bits, palette, fixed-length words. */
    public void write(ByteBuf output) {
        output.writeByte(bits);
        if (!isGlobal()) {
            if (bits == 0) Wire.writeVarInt(output, palette[0]);
            else {
                Wire.writeVarInt(output, paletteSize);
                for (int i = 0; i < paletteSize; i++) Wire.writeVarInt(output, palette[i]);
            }
        }
        for (long word : words) output.writeLong(word);
    }

    /**
     * The same entries under another registry's IDs. Local palettes keep their layout; a global
     * container is repacked at the target registry's width.
     */
    public PalettedSection remap(IntUnaryOperator mapper, int targetRegistrySize) {
        return remap(mapper, targetRegistrySize, false);
    }

    private PalettedSection remap(IntUnaryOperator mapper, int targetRegistrySize, boolean reuseStorage) {
        if (isGlobal()) {
            int targetBits = 32 - Integer.numberOfLeadingZeros(targetRegistrySize - 1);
            if (reuseStorage && !sharedStorage && targetBits > maximumLocalBits && targetBits == bits)
                return remapGlobalWords(mapper, targetRegistrySize);
            var result = new PalettedSection(
                    size,
                    minimumBits,
                    maximumLocalBits,
                    targetRegistrySize,
                    mapped(mapper, get(0), targetRegistrySize));
            if (targetBits > maximumLocalBits) result.configure(-1);
            // A smaller target registry is a local configuration on the wire; grow into it.
            for (int i = 0; i < size; i++) {
                int id = mapped(mapper, get(i), targetRegistrySize);
                if (result.isGlobal()) result.setCode(i, id);
                else result.getAndSet(i, id);
            }
            return result;
        }
        var result = reuseStorage && !canonical
                ? this
                : new PalettedSection(
                        size,
                        minimumBits,
                        maximumLocalBits,
                        targetRegistrySize,
                        bits,
                        palette.clone(),
                        paletteSize,
                        words.clone());
        for (int i = 0; i < paletteSize; i++) result.palette[i] = mapped(mapper, palette[i], targetRegistrySize);
        result.registrySize = targetRegistrySize;
        return result;
    }

    /**
     * {@link #remap}'s global repack written into this section's own words, for a target of this width: the same
     * mapper calls and failures in the same order, and unused bits cleared.
     */
    private PalettedSection remapGlobalWords(IntUnaryOperator mapper, int targetRegistrySize) {
        mapped(mapper, get(0), targetRegistrySize);
        int perWord = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int word = 0, index = 0; word < words.length; word++) {
            long source = words[word], packed = 0;
            int end = Math.min(perWord, size - index) * bits;
            for (int shift = 0; shift < end; shift += bits, index++) {
                int code = (int) (source >>> shift & mask);
                if (code >= registrySize) throw new IllegalStateException("Missing section registry ID " + code);
                packed |= (long) mapped(mapper, code, targetRegistrySize) << shift;
            }
            words[word] = packed;
        }
        registrySize = targetRegistrySize;
        return this;
    }

    private static int mapped(IntUnaryOperator mapper, int id, int registrySize) {
        int result = mapper.applyAsInt(id);
        if (result < 0 || result >= registrySize)
            throw new IllegalArgumentException("Unknown section registry ID " + id + " -> " + result);
        return result;
    }

    private static int readId(ByteBuf input, int registrySize) {
        int id = Wire.readVarInt(input);
        if (id < 0 || id >= registrySize) throw new IllegalArgumentException("Unknown section registry ID " + id);
        return id;
    }

    private void configure(int requestedBits) {
        bits = requestedBits == 0
                ? 0
                : requestedBits >= 1 && requestedBits <= maximumLocalBits
                        ? Math.max(minimumBits, requestedBits)
                        : 32 - Integer.numberOfLeadingZeros(registrySize - 1);
        boolean global = requestedBits < 0 || requestedBits > maximumLocalBits;
        palette = global ? null : new int[1 << bits];
        paletteSize = 0;
        indices = null;
        words = bits == 0 ? NO_WORDS : new long[(size + 64 / bits - 1) / (64 / bits)];
    }

    private boolean isGlobal() {
        return palette == null;
    }

    private void indexPalette() {
        if (bits <= 4 || isGlobal()) return;
        indices = new Int2IntOpenHashMap(paletteSize);
        indices.defaultReturnValue(-1);
        for (int i = 0; i < paletteSize; i++) indices.putIfAbsent(palette[i], i);
    }

    private int codeAt(int index) {
        if (bits == 0) return 0;
        int word = wordIndex(index, bits);
        return (int) (words[word] >>> ((index - word * VALUES_PER_WORD[bits]) * bits) & ((1L << bits) - 1));
    }

    /** {@code index / (64 / bits)}, exact for {@code 0 <= index < 65536}. */
    static int wordIndex(int index, int bits) {
        return (int) (index * WORD_RECIPROCALS[bits] >>> 32);
    }

    private void setCode(int index, int code) {
        if (bits == 0) return;
        int perWord = 64 / bits, shift = (index % perWord) * bits;
        long mask = (1L << bits) - 1;
        if (code < 0 || code > mask)
            throw new IllegalArgumentException("Section palette index does not fit " + bits + " bits");
        words[index / perWord] = words[index / perWord] & ~(mask << shift) | ((long) code & mask) << shift;
    }

    public int get(int index) {
        int code = codeAt(index);
        if (isGlobal()) {
            if (code >= registrySize) throw new IllegalStateException("Missing section registry ID " + code);
            return code;
        }
        if (code >= paletteSize) throw new IllegalStateException("Missing section palette entry " + code);
        return palette[code];
    }

    public int getAndSet(int index, int state) {
        if (sharedStorage) ownStorage();
        int code = idFor(state);
        int previous = get(index);
        setCode(index, code);
        return previous;
    }

    private int idFor(int state) {
        if (isGlobal()) return state;
        if (indices == null) indexPalette();
        int existing = -1;
        if (indices != null) existing = indices.get(state);
        else
            for (int i = 0; i < paletteSize; i++)
                if (palette[i] == state) {
                    existing = i;
                    break;
                }
        if (existing >= 0) return existing;
        if (paletteSize < 1 << bits) {
            int added = paletteSize++;
            palette[added] = state;
            if (indices != null) indices.put(state, added);
            return added;
        }
        int[] oldPalette = palette;
        long[] oldWords = words;
        int oldBits = bits;
        configure(bits + 1);
        if (bits > 4 && !isGlobal()) indexPalette();
        // Repack in storage order, preserving the client's palette assignment order.
        for (int i = 0; i < size; i++) {
            int oldCode = oldBits == 0
                    ? 0
                    : (int) (oldWords[i / (64 / oldBits)] >>> ((i % (64 / oldBits)) * oldBits) & ((1L << oldBits) - 1));
            setCode(i, idFor(oldPalette[oldCode]));
        }
        return idFor(state);
    }

    public PalettedSection copy() {
        return new PalettedSection(
                size,
                minimumBits,
                maximumLocalBits,
                registrySize,
                bits,
                palette == null ? null : palette.clone(),
                paletteSize,
                words.clone());
    }

    /** As {@link #copy}, sharing this section's storage until either section is next written. */
    public PalettedSection copyOnWrite() {
        if (canonical) return owned();
        var result = new PalettedSection(
                size, minimumBits, maximumLocalBits, registrySize, bits, palette, paletteSize, words);
        result.sharedStorage = true;
        sharedStorage = true;
        sharedByPool = false;
        return result;
    }

    /** As {@link #copyOnWrite}, for a pooled section whose storage a wire read shares. */
    PalettedSection copyOnWriteFromPool() {
        if (canonical) return owned();
        boolean byPool = sharedByPool || !sharedStorage;
        var result = copyOnWrite();
        result.sharedByPool = true;
        sharedByPool = byPool;
        return result;
    }

    /** This section, or an own copy of a canonical one. */
    PalettedSection owned() {
        return canonical ? new PalettedSection(size, minimumBits, maximumLocalBits, registrySize, palette[0]) : this;
    }

    /** Whether this section holds exactly what {@code single}, a single-entry section, holds, in every field. */
    boolean sameSingle(PalettedSection single) {
        return bits == 0
                && !isGlobal()
                && paletteSize == 1
                && palette.length == 1
                && words.length == 0
                && size == single.size
                && minimumBits == single.minimumBits
                && maximumLocalBits == single.maximumLocalBits
                && registrySize == single.registrySize
                && palette[0] == single.singleValue();
    }

    /** The entry {@link #forEachCount} reports for all {@link #size()} indices, or -1 when it reports others. */
    int loneEntry() {
        return paletteSize == 1 ? palette[0] : -1;
    }

    int size() {
        return size;
    }

    private void ownStorage() {
        if (canonical) throw new IllegalStateException("A canonical section is never written");
        if (sharedByPool) {
            SectionPool.sharedCloneCounter.increment();
            sharedByPool = false;
        }
        if (palette != null) palette = palette.clone();
        words = words.clone();
        sharedStorage = false;
    }

    public PalettedSection translated(IntUnaryOperator mapper) {
        var changes = new Int2IntOpenHashMap();
        forEachCount((id, count) -> changes.put(id, mapper.applyAsInt(id)));
        if (changes.int2IntEntrySet().stream().allMatch(entry -> entry.getIntKey() == entry.getIntValue())) return this;
        var result =
                new PalettedSection(size, minimumBits, maximumLocalBits, registrySize, isGlobal() ? 0 : palette[0]);
        for (int i = 0; i < size; i++) result.getAndSet(i, changes.get(get(i)));
        return result;
    }

    /** The value at every index of a single-entry local palette; -1 for any other section. */
    public int singleValue() {
        return bits == 0 && !isGlobal() && paletteSize > 0 ? palette[0] : -1;
    }

    /** False for a global palette, whose entries are only known after reading every index. */
    public boolean localPaletteWithin(int limit) {
        if (isGlobal()) return false;
        for (int i = 0; i < paletteSize; i++) if (palette[i] < 0 || palette[i] >= limit) return false;
        return true;
    }

    public boolean maybeHas(IntPredicate predicate) {
        if (isGlobal()) return true;
        for (int i = 0; i < paletteSize; i++) if (predicate.test(palette[i])) return true;
        return false;
    }

    @FunctionalInterface
    public interface CountConsumer {
        void accept(int state, int count);
    }

    public void forEachCount(CountConsumer consumer) {
        if (paletteSize == 1) {
            consumer.accept(palette[0], size);
            return;
        }
        var counts = new Int2IntOpenHashMap();
        for (int i = 0; i < size; i++) counts.addTo(codeAt(i), 1);
        counts.int2IntEntrySet().forEach(entry -> {
            int code = entry.getIntKey();
            if (code >= (isGlobal() ? registrySize : paletteSize))
                throw new IllegalStateException("Missing section palette entry " + code);
            consumer.accept(isGlobal() ? code : palette[code], entry.getIntValue());
        });
    }

    /**
     * As {@link #forEachCount}, in palette order, when the palette is local with every entry in [0, {@code limit})
     * and every stored code has an entry; any other section takes {@link #forEachCount} itself.
     */
    public void forEachCountWithin(int limit, CountConsumer consumer) {
        int perWord = bits == 0 ? 0 : 64 / bits;
        if (paletteSize == 1
                || bits == 0
                || !localPaletteWithin(limit)
                || words.length != (size + perWord - 1) / perWord) {
            forEachCount(consumer);
            return;
        }
        int[] counts = new int[1 << bits];
        long mask = (1L << bits) - 1;
        int index = 0;
        for (long word : words) {
            for (int shift = 0; shift < perWord * bits && index < size; shift += bits, index++)
                counts[(int) (word >>> shift & mask)]++;
        }
        int entries = Math.min(paletteSize, counts.length);
        for (int code = entries; code < counts.length; code++) {
            if (counts[code] != 0) {
                forEachCount(consumer);
                return;
            }
        }
        for (int code = 0; code < entries; code++) if (counts[code] != 0) consumer.accept(palette[code], counts[code]);
    }

    /**
     * The sum of {@code weightByState} over every stored state, when the palette is local with every entry inside the
     * table, every stored code has an entry and every weight is in [0, 2^38); -1 for any other section.
     */
    public long weightSumWithin(long[] weightByState) {
        int perWord = bits == 0 ? 0 : 64 / bits;
        if (paletteSize == 1
                || bits == 0
                || !localPaletteWithin(weightByState.length)
                || words.length != (size + perWord - 1) / perWord) return -1;
        long[] weights = codeWeights(1 << bits);
        for (int code = 0, entries = Math.min(paletteSize, 1 << bits); code < entries; code++) {
            long weight = weightByState[palette[code]];
            if (weight < 0 || weight >= ABSENT_CODE_WEIGHT / 4096) return -1;
            weights[code] = weight;
        }
        long sum;
        if (size != words.length * perWord) sum = packedSum(weights, perWord);
        else if (bits == 4) sum = nibbleSum(weights);
        else if (bits == 8) sum = byteSum(weights);
        else sum = packedSum(weights, perWord);
        return sum >= ABSENT_CODE_WEIGHT ? -1 : sum;
    }

    /** This thread's code weight table, its first {@code codes} entries absent; only the calling method reads it. */
    private static long[] codeWeights(int codes) {
        WeakReference<long[]> reference = CODE_WEIGHTS.get();
        long[] weights = reference == null ? null : reference.get();
        if (codes > SCRATCH_CODES) weights = new long[codes];
        else if (weights == null) {
            weights = new long[SCRATCH_CODES];
            CODE_WEIGHTS.set(new WeakReference<>(weights));
        }
        Arrays.fill(weights, 0, codes, ABSENT_CODE_WEIGHT);
        return weights;
    }

    private long nibbleSum(long[] weights) {
        long even = 0, odd = 0;
        for (long word : words) {
            for (int shift = 0; shift < 64; shift += 8) {
                even += weights[(int) (word >>> shift) & 15];
                odd += weights[(int) (word >>> (shift + 4)) & 15];
            }
        }
        return even + odd;
    }

    private long byteSum(long[] weights) {
        long even = 0, odd = 0;
        for (long word : words) {
            for (int shift = 0; shift < 64; shift += 16) {
                even += weights[(int) (word >>> shift) & 255];
                odd += weights[(int) (word >>> (shift + 8)) & 255];
            }
        }
        return even + odd;
    }

    private long packedSum(long[] weights, int perWord) {
        long mask = (1L << bits) - 1, sum = 0;
        int full = size / perWord;
        for (int index = 0; index < full; index++) {
            long word = words[index];
            for (int slot = 0; slot < perWord; slot++, word >>>= bits) sum += weights[(int) (word & mask)];
        }
        if (full < words.length) {
            long word = words[full];
            for (int slot = full * perWord; slot < size; slot++, word >>>= bits) sum += weights[(int) (word & mask)];
        }
        return sum;
    }

    boolean isUniform() {
        if (bits == 0) return true;
        int first = codeAt(0), perWord = 64 / bits, full = size / perWord;
        if (words.length != (size + perWord - 1) / perWord) {
            for (int i = 1; i < size; i++) if (codeAt(i) != first) return false;
            return true;
        }
        long pattern = 0;
        for (int slot = 0; slot < perWord; slot++) pattern |= (long) first << (slot * bits);
        long used = perWord * bits == 64 ? -1L : (1L << (perWord * bits)) - 1;
        for (int index = 0; index < full; index++) if ((words[index] & used) != pattern) return false;
        if (full == words.length) return true;
        long tail = (1L << ((size - full * perWord) * bits)) - 1;
        return (words[full] & tail) == (pattern & tail);
    }

    int contentHash() {
        int hash = 31 * (31 + bits) + size;
        hash = 31 * hash + Arrays.hashCode(words);
        hash = 31 * hash + (isGlobal() ? 3 : bits == 0 ? 0 : bits <= 4 ? 1 : 2);
        if (!isGlobal()) {
            hash = 31 * hash + paletteSize;
            for (int i = 0; i < paletteSize; i++) hash = 31 * hash + palette[i];
        }
        return hash;
    }

    /**
     * Whether the block container at the reader index, read as {@link #read} then remapped by {@code mapper} into
     * {@code targetRegistrySize} IDs, would equal this local section in every field; the reader index is then past the
     * container, and otherwise unchanged. A container that read would reject never matches.
     *
     * <p>{@code copiedAt} is the reader index of the words this thread's scratch holds from an earlier call on the same
     * container, or {@link #NO_WORD_COPY}; the result is {@link #MATCHED}, or the value to pass to the next such call.
     */
    int skipIfEncodes(
            ByteBuf input,
            boolean lengthPrefixed,
            int sourceRegistrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize,
            int copiedAt) {
        int start = input.readerIndex();
        int result = NO_WORD_COPY;
        try {
            result = encodedAt(input, lengthPrefixed, sourceRegistrySize, mapper, targetRegistrySize, copiedAt);
            if (result == MATCHED) return MATCHED;
        } catch (RuntimeException rejected) {
            // The full read that follows raises the same failure from the same reader index.
        }
        input.readerIndex(start);
        return result;
    }

    private int encodedAt(
            ByteBuf input,
            boolean lengthPrefixed,
            int sourceRegistrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize,
            int copiedAt) {
        if (size != 4096
                || minimumBits != 4
                || maximumLocalBits != 8
                || registrySize != targetRegistrySize
                || isGlobal()
                || bits == 0
                || paletteSize == 0) return copiedAt;
        int wireBits = input.readByte();
        if (wireBits < 1 || wireBits > maximumLocalBits || Math.max(minimumBits, wireBits) != bits) return copiedAt;
        int capacity = 1 << bits;
        int entries = Wire.readLength(input, bits <= 4 ? capacity : input.readableBytes());
        if (entries != paletteSize || palette.length != Math.max(capacity, entries)) return copiedAt;
        for (int i = 0; i < entries; i++) {
            int id = Wire.readVarInt(input);
            if (id < 0 || id >= sourceRegistrySize) return copiedAt;
            int mapped = mapper.applyAsInt(id);
            if (mapped != palette[i] || mapped < 0 || mapped >= targetRegistrySize) return copiedAt;
        }
        int perWord = 64 / bits, length = (size + perWord - 1) / perWord;
        if (words.length != length) return copiedAt;
        if (lengthPrefixed && Wire.readLength(input, input.readableBytes() / Long.BYTES) != length) return copiedAt;
        int bytes = length * Long.BYTES;
        if (input.readableBytes() < bytes) return copiedAt;
        int index = input.readerIndex();
        int held = copiedAt;
        byte[] array = input.hasArray() ? input.array() : index == copiedAt ? heldBytes() : null;
        if (array == null && !input.hasArray()) {
            array = copiedBytes(input, index, bytes);
            if (array != null) held = index;
        }
        if (array == null) {
            for (int i = 0; i < length; i++) if (input.getLong(index + i * Long.BYTES) != words[i]) return held;
        } else {
            int offset = input.hasArray() ? input.arrayOffset() + index : 0;
            for (int i = 0; i < length; i++)
                if ((long) BIG_ENDIAN_LONGS.get(array, offset + i * Long.BYTES) != words[i]) return held;
        }
        input.skipBytes(bytes);
        return MATCHED;
    }

    boolean sameContents(PalettedSection other) {
        return bits == other.bits
                && isGlobal() == other.isGlobal()
                && Arrays.equals(words, other.words)
                && (isGlobal()
                        || paletteSize == other.paletteSize
                                && Arrays.equals(palette, 0, paletteSize, other.palette, 0, other.paletteSize));
    }
}
