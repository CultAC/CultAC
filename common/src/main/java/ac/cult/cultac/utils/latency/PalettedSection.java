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
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Connection-owned model IDs; shared sections are copied before mutation. */
public final class PalettedSection {
    private static final int BLOCK_STATES = DataTables.defaults().registry().stateCount();
    private final int size, minimumBits, maximumLocalBits, registrySize;
    private int bits, paletteSize;
    private int[] palette;
    private Int2IntOpenHashMap indices;
    private long[] words;

    private PalettedSection(int size, int minimumBits, int maximumLocalBits, int registrySize, int initial) {
        this.size = size;
        this.minimumBits = minimumBits;
        this.maximumLocalBits = maximumLocalBits;
        this.registrySize = registrySize;
        palette = new int[] {initial};
        paletteSize = 1;
        words = new long[0];
    }

    public static PalettedSection blocks() {
        return new PalettedSection(4096, 4, 8, BLOCK_STATES, 0);
    }

    /** Pinned 26.3 PalettedContainer.read: configuration chooses the in-memory width. */
    public static PalettedSection readBlocks(ByteBuf input) {
        return read(input, 4096, 4, 8, BLOCK_STATES, false);
    }

    public static int[] readBiomes(ByteBuf input, int registrySize) {
        var section = read(input, 64, 1, 3, registrySize, false);
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

    private static PalettedSection read(
            ByteBuf input,
            int size,
            int minimumBits,
            int maximumLocalBits,
            int registrySize,
            boolean lengthPrefixed) {
        var section = new PalettedSection(size, minimumBits, maximumLocalBits, registrySize, 0);
        int wireBits = input.readByte();
        section.configure(wireBits);
        if (section.bits == 0 && !section.isGlobal()) {
            section.palette[0] = section.readId(input);
            section.paletteSize = 1;
        } else if (!section.isGlobal()) {
            section.paletteSize =
                    Wire.readLength(input, section.bits <= 4 ? section.palette.length : input.readableBytes());
            if (section.paletteSize > section.palette.length) section.palette = new int[section.paletteSize];
            for (int i = 0; i < section.paletteSize; i++) section.palette[i] = section.readId(input);
            section.indexPalette();
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
        for (int i = 0; i < section.words.length; i++) section.words[i] = input.readLong();
        return section;
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
        if (isGlobal()) {
            var result = new PalettedSection(
                    size, minimumBits, maximumLocalBits, targetRegistrySize, mapped(mapper, get(0), targetRegistrySize));
            int targetBits = 32 - Integer.numberOfLeadingZeros(targetRegistrySize - 1);
            if (targetBits > maximumLocalBits) result.configure(-1);
            // A smaller target registry is a local configuration on the wire; grow into it.
            for (int i = 0; i < size; i++) {
                int id = mapped(mapper, get(i), targetRegistrySize);
                if (result.isGlobal()) result.setCode(i, id);
                else result.getAndSet(i, id);
            }
            return result;
        }
        var result = new PalettedSection(size, minimumBits, maximumLocalBits, targetRegistrySize, 0);
        result.bits = bits;
        result.paletteSize = paletteSize;
        result.palette = palette.clone();
        for (int i = 0; i < paletteSize; i++) result.palette[i] = mapped(mapper, palette[i], targetRegistrySize);
        result.words = words.clone();
        result.indexPalette();
        return result;
    }

    private static int mapped(IntUnaryOperator mapper, int id, int registrySize) {
        int result = mapper.applyAsInt(id);
        if (result < 0 || result >= registrySize)
            throw new IllegalArgumentException("Unknown section registry ID " + id + " -> " + result);
        return result;
    }

    private int readId(ByteBuf input) {
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
        words = bits == 0 ? new long[0] : new long[(size + 64 / bits - 1) / (64 / bits)];
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
        int perWord = 64 / bits;
        return (int) (words[index / perWord] >>> ((index % perWord) * bits) & ((1L << bits) - 1));
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
        int code = idFor(state);
        int previous = get(index);
        setCode(index, code);
        return previous;
    }

    private int idFor(int state) {
        if (isGlobal()) return state;
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
        var result = new PalettedSection(size, minimumBits, maximumLocalBits, registrySize, 0);
        result.bits = bits;
        result.palette = palette == null ? null : palette.clone();
        result.paletteSize = paletteSize;
        result.words = words.clone();
        result.indexPalette();
        return result;
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

    boolean isUniform() {
        int first = codeAt(0);
        for (int i = 1; i < size; i++) if (codeAt(i) != first) return false;
        return true;
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

    boolean sameContents(PalettedSection other) {
        return bits == other.bits
                && isGlobal() == other.isGlobal()
                && Arrays.equals(words, other.words)
                && (isGlobal()
                        || paletteSize == other.paletteSize
                                && Arrays.equals(palette, 0, paletteSize, other.palette, 0, other.paletteSize));
    }
}
