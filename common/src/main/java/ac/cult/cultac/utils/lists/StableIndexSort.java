package ac.cult.cultac.utils.lists;

/** A stable sort of int indexes by a strict weak order, in O(n log n) comparisons and without allocating. */
public final class StableIndexSort {
    @FunctionalInterface
    public interface Precedes {
        /** Whether index {@code a} sorts strictly before index {@code b}. */
        boolean test(int a, int b);
    }

    private StableIndexSort() {}

    /**
     * Sorts the first {@code count} entries of {@code order}; entries neither precedes keep their relative order, as an
     * insertion sort leaves them. {@code scratch} holds at least {@code count} entries.
     */
    public static void sort(int[] order, int[] scratch, int count, Precedes precedes) {
        int[] from = order, to = scratch;
        for (int width = 1; width < count; width <<= 1) {
            for (int start = 0; start < count; start += width << 1) {
                int mid = Math.min(start + width, count), end = Math.min(start + (width << 1), count);
                int i = start, j = mid, k = start;
                // Bottom-up merge: the right run's entry goes first only when it strictly precedes the left's.
                while (i < mid && j < end) to[k++] = precedes.test(from[j], from[i]) ? from[j++] : from[i++];
                while (i < mid) to[k++] = from[i++];
                while (j < end) to[k++] = from[j++];
            }
            int[] swap = from;
            from = to;
            to = swap;
        }
        if (from != order) System.arraycopy(from, 0, order, 0, count);
    }
}
