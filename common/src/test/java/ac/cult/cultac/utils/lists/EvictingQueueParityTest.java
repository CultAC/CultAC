package ac.cult.cultac.utils.lists;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The ring buffer answers every call CultAC makes exactly as the ArrayList-based queue it replaced. */
class EvictingQueueParityTest {
    /** The queue as it was: an ArrayList that drops its head once it outgrows {@code maxSize}. */
    private static final class OldEvictingQueue<K> extends ArrayList<K> {
        private final int maxSize;

        OldEvictingQueue(int size) {
            this.maxSize = size;
        }

        @Override
        public boolean add(K k) {
            boolean r = super.add(k);
            if (size() > maxSize) {
                removeRange(0, size() - maxSize);
            }
            return r;
        }

        K getYoungest() {
            return get(size() - 1);
        }

        K getOldest() {
            return get(0);
        }
    }

    @Test
    void randomOperationsMatchTheArrayListQueue() {
        for (int maxSize : new int[] {0, 1, 2, 3, 4, 5, 10, 11, 15, 16, 20, 40, 60, 100}) {
            for (long seed = 0; seed < 40; seed++) {
                Random random = new Random(seed * 1_000_003L + maxSize);
                var old = new OldEvictingQueue<Long>(maxSize);
                var queue = new EvictingQueue<Long>(maxSize);
                for (int step = 0; step < 2_000; step++) {
                    int op = random.nextInt(100);
                    String at = "maxSize " + maxSize + " seed " + seed + " step " + step + " op " + op;
                    if (op < 60) {
                        Long value = random.nextInt(8) == 0 ? null : (long) random.nextInt(50) - 10;
                        assertEquals(old.add(value), queue.add(value), at);
                    } else if (op < 63) {
                        old.clear();
                        queue.clear();
                    } else if (op < 70 && !old.isEmpty()) {
                        int index = random.nextInt(old.size());
                        assertEquals(old.remove(index), queue.remove(index), at);
                    } else if (op < 74) {
                        Long value = (long) random.nextInt(50) - 10;
                        assertEquals(old.remove(value), queue.remove(value), at);
                    } else if (op < 78 && !old.isEmpty()) {
                        int index = random.nextInt(old.size());
                        Long value = (long) random.nextInt(50);
                        assertEquals(old.set(index, value), queue.set(index, value), at);
                    } else if (op < 82) {
                        long bound = random.nextInt(50) - 10;
                        Iterator<Long> a = old.iterator();
                        Iterator<Long> b = queue.iterator();
                        while (a.hasNext()) {
                            assertTrue(b.hasNext(), at);
                            Long x = a.next();
                            assertEquals(x, b.next(), at);
                            if (x != null && x < bound) {
                                a.remove();
                                b.remove();
                            }
                        }
                        assertEquals(a.hasNext(), b.hasNext(), at);
                    } else if (op < 84) {
                        long bound = random.nextInt(50) - 10;
                        assertEquals(
                                old.removeIf(x -> x != null && x > bound),
                                queue.removeIf(x -> x != null && x > bound),
                                at);
                    }
                    assertSame(old, queue, at);
                }
            }
        }
    }

    @Test
    void outOfRangeAccessThrowsLikeTheArrayListQueue() {
        for (int maxSize : new int[] {0, 1, 3, 60}) {
            var old = new OldEvictingQueue<Long>(maxSize);
            var queue = new EvictingQueue<Long>(maxSize);
            for (int i = 0; i < maxSize + 5; i++) {
                for (int index : new int[] {-1, old.size(), old.size() + 1}) {
                    assertThrows(IndexOutOfBoundsException.class, () -> old.get(index));
                    assertThrows(IndexOutOfBoundsException.class, () -> queue.get(index));
                    assertThrows(IndexOutOfBoundsException.class, () -> old.set(index, 1L));
                    assertThrows(IndexOutOfBoundsException.class, () -> queue.set(index, 1L));
                    assertThrows(IndexOutOfBoundsException.class, () -> old.remove(index));
                    assertThrows(IndexOutOfBoundsException.class, () -> queue.remove(index));
                }
                if (old.isEmpty()) {
                    assertThrows(IndexOutOfBoundsException.class, old::getOldest);
                    assertThrows(IndexOutOfBoundsException.class, queue::getOldest);
                    assertThrows(IndexOutOfBoundsException.class, old::getYoungest);
                    assertThrows(IndexOutOfBoundsException.class, queue::getYoungest);
                }
                old.add((long) i);
                queue.add((long) i);
                assertSame(old, queue, "maxSize " + maxSize + " add " + i);
            }
        }
    }

    @Test
    void addingWhileIteratingFailsLikeTheArrayListQueue() {
        for (int size : new int[] {1, 3, 20}) {
            for (int fill = 1; fill <= size + 2; fill++) {
                var old = new OldEvictingQueue<Long>(size);
                var queue = new EvictingQueue<Long>(size);
                for (long i = 0; i < fill; i++) {
                    old.add(i);
                    queue.add(i);
                }
                assertEquals(failsOnAdd(old), failsOnAdd(queue), "size " + size + " fill " + fill);
                assertEquals(failsOnClear(old), failsOnClear(queue), "size " + size + " fill " + fill);
            }
        }
    }

    private static boolean failsOnAdd(List<Long> list) {
        try {
            for (Long value : list) {
                if (value == 0) list.add(99L);
            }
            return false;
        } catch (ConcurrentModificationException expected) {
            return true;
        }
    }

    private static boolean failsOnClear(List<Long> list) {
        try {
            for (Long ignored : list) list.clear();
            return false;
        } catch (ConcurrentModificationException expected) {
            return true;
        }
    }

    private static void assertSame(OldEvictingQueue<Long> old, EvictingQueue<Long> queue, String at) {
        assertEquals(old.size(), queue.size(), at);
        assertEquals(old.isEmpty(), queue.isEmpty(), at);
        assertEquals(new ArrayList<>(old), new ArrayList<>(queue), at);
        for (int i = 0; i < old.size(); i++) assertEquals(old.get(i), queue.get(i), at);
        assertEquals(old, queue, at);
        assertEquals(queue, old, at);
        assertEquals(old.hashCode(), queue.hashCode(), at);
        assertEquals(old.toString(), queue.toString(), at);
        assertArrayEquals(old.toArray(), queue.toArray(), at);
        for (long probe = -11; probe < 41; probe += 7) {
            assertEquals(old.contains(probe), queue.contains(probe), at);
            assertEquals(old.indexOf(probe), queue.indexOf(probe), at);
            assertEquals(old.lastIndexOf(probe), queue.lastIndexOf(probe), at);
        }
        if (!old.isEmpty()) {
            assertEquals(old.getOldest(), queue.getOldest(), at);
            assertEquals(old.getYoungest(), queue.getYoungest(), at);
            if (!old.contains(null)) assertEquals(Collections.max(old), Collections.max(queue), at);
        }
    }
}
