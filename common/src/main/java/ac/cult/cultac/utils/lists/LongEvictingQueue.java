package ac.cult.cultac.utils.lists;

/**
 * Primitive {@link EvictingQueue} for longs: keeps the newest {@code capacity} values in a ring,
 * indexed oldest first like the list it replaces.
 */
public final class LongEvictingQueue {
    private final long[] values;
    private int head;
    private int size;

    public LongEvictingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.values = new long[capacity];
    }

    public void add(long value) {
        int tail = head + size;
        if (tail >= values.length) tail -= values.length;
        values[tail] = value;
        if (size < values.length) {
            size++;
        } else if (++head == values.length) {
            head = 0;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Index 0 is the oldest retained value. */
    public long get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index " + index + ", size " + size);
        int slot = head + index;
        if (slot >= values.length) slot -= values.length;
        return values[slot];
    }

    public long getOldest() {
        return get(0);
    }

    public long getYoungest() {
        return get(size - 1);
    }

    public void clear() {
        head = 0;
        size = 0;
    }
}
