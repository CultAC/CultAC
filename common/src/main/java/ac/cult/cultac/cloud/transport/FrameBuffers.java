package ac.cult.cultac.cloud.transport;

/**
 * Arrays frame records are written into, shared by every socket on one {@link SharedTransport}. An array has one owner
 * at a time: the one that {@linkplain #take took} it, then whoever it was handed to, until that owner {@linkplain #give
 * gives} it back once nothing reads it, or lets it go. Only the first bytes its record names are ever read.
 */
final class FrameBuffers {
    /** Sizes kept for reuse: each power of two from 256 bytes to 64 KiB. */
    static final int MIN_SHIFT = 8;

    static final int MAX_SHIFT = 16;
    /** Bytes each size keeps for reuse at most. */
    static final int KEPT_BYTES = 512 * 1024;

    private final Shelf[] shelves = new Shelf[MAX_SHIFT - MIN_SHIFT + 1];

    FrameBuffers() {
        for (int shift = MIN_SHIFT; shift <= MAX_SHIFT; shift++)
            shelves[shift - MIN_SHIFT] = new Shelf(KEPT_BYTES >> shift);
    }

    /** An array of at least {@code length} bytes: the next kept size, or exactly {@code length} past the largest. */
    byte[] take(int length) {
        if (length > 1 << MAX_SHIFT) return new byte[length];
        int shift = length <= 1 << MIN_SHIFT ? MIN_SHIFT : 32 - Integer.numberOfLeadingZeros(length - 1);
        byte[] kept = shelves[shift - MIN_SHIFT].take();
        return kept != null ? kept : new byte[1 << shift];
    }

    /** Takes back an array from {@link #take} that nothing reads any more; one past the largest kept size is let go. */
    void give(byte[] array) {
        int length = array.length;
        if (length < 1 << MIN_SHIFT || length > 1 << MAX_SHIFT || Integer.bitCount(length) != 1) return;
        shelves[Integer.numberOfTrailingZeros(length) - MIN_SHIFT].give(array);
    }

    /** Arrays of one size waiting to be taken again. */
    private static final class Shelf {
        private final byte[][] arrays;
        private int count;

        Shelf(int capacity) {
            arrays = new byte[capacity][];
        }

        synchronized byte[] take() {
            if (count == 0) return null;
            byte[] array = arrays[--count];
            arrays[count] = null;
            return array;
        }

        synchronized void give(byte[] array) {
            if (count < arrays.length) arrays[count++] = array;
        }
    }
}
