package ac.cult.cultac.player;

/** Ids of pings CultAC did not write and has not seen answered, oldest first; the owning player guards it. */
final class ForeignPings {
    static final int CAPACITY = 1024;
    private static final int MASK = CAPACITY - 1;
    private static final int BUCKET_BITS = 12;

    private final int[] ids = new int[CAPACITY];
    /** How many of {@link #ids} fall in each bucket; an empty bucket proves its ids absent without a scan. */
    private final short[] buckets = new short[1 << BUCKET_BITS];
    private int head;
    private int size;

    /** Records {@code id}, forgetting the oldest id when full. */
    void add(int id) {
        if (size == CAPACITY) {
            buckets[bucket(ids[head])]--;
            head = (head + 1) & MASK;
            size--;
        }
        ids[(head + size) & MASK] = id;
        size++;
        buckets[bucket(id)]++;
    }

    boolean contains(int id) {
        if (buckets[bucket(id)] == 0) return false;
        for (int i = 0; i < size; i++) {
            if (ids[(head + i) & MASK] == id) return true;
        }
        return false;
    }

    /** Forgets the oldest record of {@code id}; pongs arrive in ping order, so it is usually the head. */
    void remove(int id) {
        if (buckets[bucket(id)] == 0) return;
        for (int i = 0; i < size; i++) {
            if (ids[(head + i) & MASK] != id) continue;
            for (int j = i; j > 0; j--) ids[(head + j) & MASK] = ids[(head + j - 1) & MASK];
            head = (head + 1) & MASK;
            size--;
            buckets[bucket(id)]--;
            return;
        }
    }

    private static int bucket(int id) {
        return (id * 0x9E3779B9) >>> (32 - BUCKET_BITS);
    }
}
