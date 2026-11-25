package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.network.FrameCopies;
import com.sun.management.HotSpotDiagnosticMXBean;
import io.netty.buffer.ByteBuf;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Private packet copies and terrain records owned by one capture frame until it is sent, dropped or reset: each a range
 * of one of the pool's chunks, which its frame's packets, or its terrain records in chunks of their own, fill in order.
 * Reusable capacity, bookkeeping included, is paid from the player's {@link Budget}, which draws on one budget for the
 * process; a copy without room is a fresh array.
 */
final class CapturePacketCopies {
    static final int MAX_PACKET_BYTES = 512;
    /** Reusable capacity of one pool: its chunks. */
    static final int MAX_RETAINED_BYTES = 8 * 1024;
    static final int MAX_CHUNKS = 4;
    /** Reusable capacity of one player's pools, bookkeeping included. */
    static final long PLAYER_BYTES = 64 * 1024;
    /** Reusable capacity of every pool in the process, bookkeeping included. */
    static final long PROCESS_BYTES = 64L * 1024 * 1024;
    /** A size unused this long gives its capacity back when its frame is released. */
    static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** The running JVM's object layout, or the widest HotSpot layout when it cannot be read. */
    private static final Layout LAYOUT = Layout.current();
    /** A pool's own bookkeeping: its fields, its chunk index and its per-chunk last uses. */
    static final int POOL_BYTES = LAYOUT.objectBytes(5 * LAYOUT.referenceBytes + 8 * Integer.BYTES + Long.BYTES + 1)
            + referencesBytes(MAX_CHUNKS)
            + LAYOUT.arrayBytes(Integer.BYTES * MAX_CHUNKS);
    /** A chunk's length: {@link #MAX_CHUNKS} of them, headers included, are {@link #MAX_RETAINED_BYTES}. */
    static final int CHUNK_BYTES = MAX_RETAINED_BYTES / MAX_CHUNKS - LAYOUT.arrayBase;
    /** Terrain records' chunks: each 16 KiB with its header, at most this many a pool. */
    static final int TERRAIN_CHUNK_BYTES = 16 * 1024 - LAYOUT.arrayBase;

    static final int MAX_TERRAIN_CHUNKS = 4;
    /** Room in the player's budget that terrain chunks leave for packet chunks. */
    static final long PACKET_RESERVE_BYTES = 16 * 1024;
    /** A pool's terrain bookkeeping: its chunk index and per-chunk last uses. */
    static final int TERRAIN_BOOK_BYTES =
            referencesBytes(MAX_TERRAIN_CHUNKS) + LAYOUT.arrayBytes(Integer.BYTES * MAX_TERRAIN_CHUNKS);

    /** A chunk's last use is kept in units of 2^20 ns, which wrap only after about 25 days. */
    private static final int STAMP_SHIFT = 20;

    private static final int IDLE_STAMPS = (int) (IDLE_NANOS >> STAMP_SHIFT);
    private static final AtomicLong processBytes = new AtomicLong();

    private final Budget budget;
    private final byte[][] chunks = new byte[MAX_CHUNKS][];
    private final int[] lastUsed = new int[MAX_CHUNKS];
    /** Chunks held, the one being filled and where in it the next copy goes. */
    private int held;

    private int chunk;
    private int position;
    /** Where in its array the last copy starts. */
    private int offset;

    /** Terrain chunks, held, the one being filled and where in it the next record goes; null until the first. */
    private byte[][] terrainChunks;

    private int[] terrainUsed;
    private int terrainHeld;
    private int terrainChunk;
    private int terrainPosition;

    private int retainedBytes;
    private long lastUse;
    private boolean released;

    private CapturePacketCopies(Budget budget, long nanos) {
        this.budget = budget;
        this.lastUse = nanos;
    }

    /**
     * A pool whose bookkeeping {@code budget} pays for; null when it has no room. It is charged only once it exists,
     * so a failed allocation leaves both budgets as they were.
     */
    static CapturePacketCopies of(Budget budget, long nanos) {
        if (!budget.fits(POOL_BYTES)) return null;
        CapturePacketCopies pool = new CapturePacketCopies(budget, nanos);
        return budget.take(POOL_BYTES) ? pool : null;
    }

    /**
     * The array holding a copy of {@code source}'s readable bytes from {@link #offset()}: a chunk the frame owns, or a
     * fresh array of exactly their length.
     */
    byte[] copy(ByteBuf source, long nanos) {
        int length = source.readableBytes();
        offset = 0;
        if (length > MAX_PACKET_BYTES) return FrameCopies.bytes(source);
        lastUse = nanos;
        if (chunk == held || position + length > CHUNK_BYTES) {
            int next = chunk == held ? chunk : chunk + 1;
            if (next == MAX_CHUNKS) return FrameCopies.bytes(source);
            if (next == held) {
                int cost = arrayBytes(CHUNK_BYTES);
                if (!budget.fits(cost)) return FrameCopies.bytes(source);
                byte[] grown = new byte[CHUNK_BYTES];
                if (!budget.take(cost)) return FrameCopies.bytes(source);
                chunks[held++] = grown;
                retainedBytes += cost;
            }
            chunk = next;
            position = 0;
        }
        byte[] bytes = chunks[chunk];
        FrameCopies.copyInto(source, bytes, position);
        offset = position;
        position += length;
        lastUsed[chunk] = stamp(nanos);
        return bytes;
    }

    /**
     * Room for {@code length} bytes of a terrain record, from {@link #offset()} in the array returned: a terrain chunk
     * the frame owns, or a fresh array of exactly that length. Terrain chunks leave the player's budget
     * {@link #PACKET_RESERVE_BYTES} for packet chunks.
     */
    byte[] reserve(int length, long nanos) {
        offset = 0;
        if (length > TERRAIN_CHUNK_BYTES) return new byte[length];
        lastUse = nanos;
        if (terrainChunks == null) {
            if (!budget.fits(TERRAIN_BOOK_BYTES + PACKET_RESERVE_BYTES)) return new byte[length];
            byte[][] chunks = new byte[MAX_TERRAIN_CHUNKS][];
            int[] used = new int[MAX_TERRAIN_CHUNKS];
            if (!budget.take(TERRAIN_BOOK_BYTES)) return new byte[length];
            terrainChunks = chunks;
            terrainUsed = used;
            retainedBytes += TERRAIN_BOOK_BYTES;
        }
        if (terrainChunk == terrainHeld || terrainPosition + length > TERRAIN_CHUNK_BYTES) {
            int next = terrainChunk == terrainHeld ? terrainChunk : terrainChunk + 1;
            if (next == MAX_TERRAIN_CHUNKS) return new byte[length];
            if (next == terrainHeld) {
                int cost = arrayBytes(TERRAIN_CHUNK_BYTES);
                if (!budget.fits(cost + PACKET_RESERVE_BYTES)) return new byte[length];
                byte[] grown = new byte[TERRAIN_CHUNK_BYTES];
                if (!budget.take(cost)) return new byte[length];
                terrainChunks[terrainHeld++] = grown;
                retainedBytes += cost;
            }
            terrainChunk = next;
            terrainPosition = 0;
        }
        offset = terrainPosition;
        terrainPosition += length;
        terrainUsed[terrainChunk] = stamp(nanos);
        return terrainChunks[terrainChunk];
    }

    /** The last {@link #reserve}'s room goes unused: it is free again unless the pool was cleared since. */
    void abandon(byte[] bytes, int at, int length) {
        if (terrainChunk < terrainHeld
                && bytes == terrainChunks[terrainChunk]
                && at + length == terrainPosition
                && length > 0) terrainPosition = at;
    }

    /** Where the last {@link #copy}'s or {@link #reserve}'s bytes start in the array it returned. */
    int offset() {
        return offset;
    }

    /**
     * The frame that owned the copies is done with them, so their chunks may be reused; trailing chunks unused for
     * {@link #IDLE_NANOS} at {@code nanos} give their capacity back.
     */
    void clear(long nanos) {
        int now = stamp(nanos);
        while (held > 0 && now - lastUsed[held - 1] > IDLE_STAMPS) {
            chunks[--held] = null;
            int freed = arrayBytes(CHUNK_BYTES);
            retainedBytes -= freed;
            budget.give(freed);
        }
        while (terrainHeld > 0 && now - terrainUsed[terrainHeld - 1] > IDLE_STAMPS) {
            terrainChunks[--terrainHeld] = null;
            int freed = arrayBytes(TERRAIN_CHUNK_BYTES);
            retainedBytes -= freed;
            budget.give(freed);
        }
        chunk = 0;
        position = 0;
        terrainChunk = 0;
        terrainPosition = 0;
    }

    /** Gives every byte of the pool back, once; the frame that owned it is gone, and the pool is not used again. */
    void release() {
        if (released) return;
        released = true;
        Arrays.fill(chunks, null);
        terrainChunks = null;
        terrainUsed = null;
        budget.give(retainedBytes + (long) POOL_BYTES);
        retainedBytes = 0;
        held = 0;
        chunk = 0;
        position = 0;
        terrainHeld = 0;
        terrainChunk = 0;
        terrainPosition = 0;
    }

    long lastUse() {
        return lastUse;
    }

    int retainedBytes() {
        return retainedBytes;
    }

    static long processBytes() {
        return processBytes.get();
    }

    private static int stamp(long nanos) {
        return (int) (nanos >> STAMP_SHIFT);
    }

    private static int arrayBytes(int length) {
        return LAYOUT.arrayBytes(length);
    }

    private static int referencesBytes(int count) {
        return LAYOUT.align(LAYOUT.referenceArrayBase + LAYOUT.referenceBytes * count);
    }

    /** Header, reference and alignment sizes of HotSpot's object layouts. */
    private record Layout(int objectHeader, int arrayBase, int referenceBytes, int referenceArrayBase, int alignment) {
        private static final Layout WIDEST = new Layout(16, 20, 8, 24, 8);

        static Layout current() {
            try {
                HotSpotDiagnosticMXBean vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
                boolean compact = flag(vm, "UseCompactObjectHeaders");
                boolean classPointers = compact || flag(vm, "UseCompressedClassPointers");
                int references = flag(vm, "UseCompressedOops") ? 4 : 8;
                int alignment = Integer.parseInt(
                        vm.getVMOption("ObjectAlignmentInBytes").getValue());
                int header = compact ? 8 : classPointers ? 12 : 16;
                int length = header + Integer.BYTES;
                return new Layout(
                        header, length, references, (length + references - 1) / references * references, alignment);
            } catch (RuntimeException | LinkageError unknown) {
                return WIDEST;
            }
        }

        private static boolean flag(HotSpotDiagnosticMXBean vm, String name) {
            try {
                return Boolean.parseBoolean(vm.getVMOption(name).getValue());
            } catch (IllegalArgumentException absent) {
                return false;
            }
        }

        int objectBytes(int fields) {
            return align(objectHeader + fields);
        }

        int arrayBytes(int bytes) {
            return align(arrayBase + bytes);
        }

        int align(int bytes) {
            return (bytes + alignment - 1) / alignment * alignment;
        }
    }

    /** One player's share of the reusable capacity; used only on its stream's event loop. */
    static final class Budget {
        private long bytes;

        boolean fits(long amount) {
            return bytes + amount <= PLAYER_BYTES && processBytes.get() + amount <= PROCESS_BYTES;
        }

        boolean take(long amount) {
            if (!fits(amount)) return false;
            if (processBytes.addAndGet(amount) > PROCESS_BYTES) {
                processBytes.addAndGet(-amount);
                return false;
            }
            bytes += amount;
            return true;
        }

        void give(long amount) {
            bytes -= amount;
            processBytes.addAndGet(-amount);
        }

        long bytes() {
            return bytes;
        }
    }
}
