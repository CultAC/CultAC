package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * One connection's recently decoded clientbound stacks, by their wire bytes, for the decode of one decoder, protocol
 * version, item registry and registry generation. Confined to the connection's packet executor, like the registry
 * state the decode reads.
 */
final class ItemStackCache {
    static final int ENTRIES = 64;
    static final int MAX_BYTES = 256;
    /** Bound on the summed {@link #retained} sizes of the held stacks. */
    static final int BUDGET = 160 * 1024;

    private final int[] hashes = new int[ENTRIES];
    private final byte[][] keys = new byte[ENTRIES][];
    private final SimItemStack[] stacks = new SimItemStack[ENTRIES];
    private final long[] used = new long[ENTRIES];
    private final int[] sizes = new int[ENTRIES];
    private long clock;
    private int held;
    private boolean observing;
    private WireValueDecoder decoder;
    private ProtocolVersion version;
    private ItemRegistry items;
    private Object registries;

    /** Holds stacks only for this decode; any other drops what it held. */
    void bind(WireValueDecoder decoder, ProtocolVersion version, ItemRegistry items, Object registries) {
        if (decoder == this.decoder && version == this.version && items == this.items && registries == this.registries)
            return;
        clear();
        this.decoder = decoder;
        this.version = version;
        this.items = items;
        this.registries = registries;
    }

    void clear() {
        Arrays.fill(keys, null);
        Arrays.fill(stacks, null);
        Arrays.fill(used, 0);
        held = 0;
    }

    /** Upper bound on the heap a held stack keeps, from its wire length and its decoded component trees. */
    static int retained(int length, NbtValue components, NbtValue layouts) {
        int[] tree = new int[2];
        walk(components, tree);
        walk(layouts, tree);
        return 256 + 4 * length + 192 * tree[0] + 4 * tree[1] + (tree[0] > 1 ? 1024 : 0);
    }

    /** Adds the nodes under {@code value} to {@code tree[0]} and their key and text characters to {@code tree[1]}. */
    private static void walk(NbtValue value, int[] tree) {
        if (value == null) return;
        tree[0]++;
        if (value instanceof NbtValue.Compound compound) {
            for (var entry : compound.values().entrySet()) {
                tree[0]++;
                tree[1] += entry.getKey().length();
                walk(entry.getValue(), tree);
            }
        } else if (value instanceof NbtValue.Sequence sequence) {
            for (var child : sequence.values()) walk(child, tree);
        } else if (value instanceof NbtValue.PrimitiveArray array) {
            tree[0] += array.values().size();
        } else if (value instanceof NbtValue.Text text) {
            tree[1] += text.value().length();
        }
    }

    int held() {
        return held;
    }

    /** Whether sent stacks are being decoded without reading or changing what this cache holds. */
    boolean observing() {
        return observing;
    }

    /** The result of {@code read}, whose sent stacks neither reuse nor change the held stacks. */
    <T> T observe(Supplier<T> read) {
        boolean outer = observing;
        observing = true;
        try {
            return read.get();
        } finally {
            observing = outer;
        }
    }

    /** The held stack decoded from {@code input}'s bytes {@code start} until {@code end}, or null. */
    SimItemStack get(ByteBuf input, int start, int end) {
        int length = end - start;
        if (length > MAX_BYTES) return null;
        int hash = hash(input, start, end);
        for (int slot = 0; slot < ENTRIES; slot++) {
            byte[] key = keys[slot];
            if (key != null && hashes[slot] == hash && key.length == length && same(key, input, start)) {
                used[slot] = ++clock;
                return stacks[slot];
            }
        }
        return null;
    }

    /**
     * Holds {@code stack}, which no other holder may change and which keeps at most {@code size} bytes, dropping the
     * least recently used stacks until it fits.
     */
    void put(ByteBuf input, int start, int end, SimItemStack stack, int size) {
        int length = end - start;
        if (length > MAX_BYTES || size > BUDGET) return;
        int slot;
        while (true) {
            slot = -1;
            int oldest = -1;
            for (int candidate = 0; candidate < ENTRIES; candidate++) {
                if (keys[candidate] == null) {
                    if (slot < 0) slot = candidate;
                } else if (oldest < 0 || used[candidate] < used[oldest]) oldest = candidate;
            }
            if (slot >= 0 && held + size <= BUDGET) break;
            held -= sizes[oldest];
            keys[oldest] = null;
            stacks[oldest] = null;
        }
        byte[] key = new byte[length];
        input.getBytes(start, key);
        hashes[slot] = hash(input, start, end);
        keys[slot] = key;
        stacks[slot] = stack;
        sizes[slot] = size;
        held += size;
        used[slot] = ++clock;
    }

    private static int hash(ByteBuf input, int start, int end) {
        int hash = end - start, index = start;
        for (; index + 4 <= end; index += 4) hash = hash * 31 + input.getInt(index);
        for (; index < end; index++) hash = hash * 31 + input.getByte(index);
        return hash;
    }

    private static boolean same(byte[] key, ByteBuf input, int start) {
        for (int index = 0; index < key.length; index++) if (key[index] != input.getByte(start + index)) return false;
        return true;
    }
}
