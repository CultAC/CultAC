package ac.cult.cultac.player;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Written CultAC pings awaiting their pong, in wire order, as primitives. Each entry has a sequence number, one
 * above the entry before it modulo 2^31, so an entry's position behind the oldest is the difference of the two.
 * It never holds one id twice. Confined to its player's lock.
 */
final class WrittenPings {
    private static final int SEQUENCES = Integer.MAX_VALUE;
    private int[] ids = new int[16];
    private int[] transactions = new int[16];
    private long[] sentAt = new long[16];
    // The sequence of the oldest entry, and of the next one added.
    private int head, next;
    private final Int2IntOpenHashMap sequenceById = new Int2IntOpenHashMap();

    WrittenPings() {
        sequenceById.defaultReturnValue(-1);
    }

    boolean isEmpty() {
        return head == next;
    }

    int size() {
        return (next - head) & SEQUENCES;
    }

    boolean contains(int id) {
        return sequenceById.containsKey(id);
    }

    /** The id of every entry, as a view. */
    IntSet ids() {
        return sequenceById.keySet();
    }

    /** The sequence of the entry of ping {@code id}, or -1 when there is none. */
    int sequence(int id) {
        return sequenceById.get(id);
    }

    int headSequence() {
        return head;
    }

    /** How many entries are older than the entry of sequence {@code sequence}. */
    int before(int sequence) {
        return (sequence - head) & SEQUENCES;
    }

    void add(int transaction, int id, long sentAt) {
        if (size() == ids.length) grow();
        int slot = next & (ids.length - 1);
        ids[slot] = id;
        transactions[slot] = transaction;
        this.sentAt[slot] = sentAt;
        sequenceById.put(id, next);
        next = (next + 1) & SEQUENCES;
    }

    int headId() {
        return ids[head & (ids.length - 1)];
    }

    int headTransaction() {
        return transactions[head & (ids.length - 1)];
    }

    long headSentAt() {
        return sentAt[head & (ids.length - 1)];
    }

    void removeHead() {
        sequenceById.remove(headId());
        head = (head + 1) & SEQUENCES;
    }

    private void grow() {
        int mask = ids.length - 1;
        int[] grownIds = new int[ids.length << 1];
        int[] grownTransactions = new int[grownIds.length];
        long[] grownSentAt = new long[grownIds.length];
        int grownMask = grownIds.length - 1;
        for (int sequence = head; sequence != next; sequence = (sequence + 1) & SEQUENCES) {
            grownIds[sequence & grownMask] = ids[sequence & mask];
            grownTransactions[sequence & grownMask] = transactions[sequence & mask];
            grownSentAt[sequence & grownMask] = sentAt[sequence & mask];
        }
        ids = grownIds;
        transactions = grownTransactions;
        sentAt = grownSentAt;
    }
}
