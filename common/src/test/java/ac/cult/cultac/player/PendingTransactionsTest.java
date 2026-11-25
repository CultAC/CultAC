package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link PendingTransactions} against a {@link LinkedList} that inserts behind the last written entry by its index:
 * appends, adds at the head, inserts behind entries near the head, in the middle and near the tail of a backlog of
 * thousands, and behind entries already polled, interleaved with polls.
 */
class PendingTransactionsTest {
    private static final class Entry extends PendingTransactions.Link {
        private final int serial;

        private Entry(int serial) {
            this.serial = serial;
        }

        @Override
        public String toString() {
            return "#" + serial;
        }
    }

    @Test
    void keepsTheOrderOfAListInsertingBehindTheLastWrittenEntryByIndex() {
        for (long seed = 1; seed <= 3; seed++) {
            Random random = new Random(seed);
            PendingTransactions<Entry> queue = new PendingTransactions<>();
            LinkedList<Entry> reference = new LinkedList<>();
            List<Entry> polled = new ArrayList<>();
            int serial = 0;
            while (serial < 5_000) {
                Entry entry = new Entry(serial++);
                queue.add(entry);
                reference.add(entry);
            }
            for (int step = 0; step < 12_000; step++) {
                int operation = random.nextInt(10);
                if (operation < 3) {
                    Entry entry = new Entry(serial++);
                    queue.add(entry);
                    reference.add(entry);
                } else if (operation < 4) {
                    Entry entry = new Entry(serial++);
                    queue.addFirst(entry);
                    reference.addFirst(entry);
                } else if (operation < 8) {
                    insertBehind(queue, reference, lastWritten(random, reference, polled), new Entry(serial++));
                } else {
                    for (int n = random.nextInt(8); n > 0; n--) {
                        Entry oldest = reference.poll();
                        assertSame(oldest, queue.poll());
                        if (oldest != null) polled.add(oldest);
                    }
                }
                assertEquals(reference.size(), queue.size());
                assertSame(reference.peek(), queue.peek());
                if (step % 64 == 0) assertEquals(reference, new ArrayList<>(queue));
            }
            assertEquals(reference, new ArrayList<>(queue));
            while (!reference.isEmpty()) assertSame(reference.poll(), queue.poll());
            assertNull(queue.poll());
            assertTrue(queue.isEmpty());
        }
    }

    private static void insertBehind(
            PendingTransactions<Entry> queue, LinkedList<Entry> reference, Entry previous, Entry entry) {
        int index = previous == null ? -1 : reference.indexOf(previous);
        assertEquals(index >= 0, queue.contains(previous));
        if (index < 0) {
            if (previous != null) assertThrows(IllegalArgumentException.class, () -> queue.addAfter(previous, entry));
            queue.addFirst(entry);
            reference.addFirst(entry);
        } else {
            queue.addAfter(previous, entry);
            reference.add(index + 1, entry);
        }
    }

    private static Entry lastWritten(Random random, LinkedList<Entry> reference, List<Entry> polled) {
        int size = reference.size();
        int choice = random.nextInt(6);
        if (choice == 0) return null;
        if (choice == 1) return polled.isEmpty() ? null : polled.get(random.nextInt(polled.size()));
        if (size == 0) return null;
        return reference.get(
                switch (choice) {
                    case 2 -> random.nextInt(Math.min(8, size));
                    case 3 -> size / 2 + random.nextInt(Math.min(8, size - size / 2));
                    case 4 -> size - 1 - random.nextInt(Math.min(8, size));
                    default -> random.nextInt(size);
                });
    }
}
