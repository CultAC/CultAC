package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ForeignPingsTest {
    @Test
    void answersAsTheScannedRingDoes() {
        for (long seed = 1; seed <= 40; seed++) {
            var random = new SplittableRandom(seed);
            var pings = new ForeignPings();
            var reference = new ScannedRing();
            // Duplicates, evictions of duplicates, shared buckets and misses all occur.
            int distinct = 1 + random.nextInt(seed % 3 == 0 ? 8 : seed % 3 == 1 ? 3000 : 100_000);
            for (int step = 0; step < 20_000; step++) {
                int id = Short.MIN_VALUE - random.nextInt(distinct);
                int pick = random.nextInt(10);
                if (pick < 5) {
                    pings.add(id);
                    reference.add(id);
                } else if (pick < 8) {
                    pings.remove(id);
                    reference.remove(id);
                }
                assertEquals(reference.contains(id), pings.contains(id), "seed " + seed + " step " + step);
            }
            for (int id = Short.MIN_VALUE - distinct; id <= Short.MIN_VALUE; id++)
                assertEquals(reference.contains(id), pings.contains(id), "seed " + seed + " id " + id);
        }
    }

    @Test
    void evictionForgetsOnlyTheOldestCopy() {
        var pings = new ForeignPings();
        pings.add(-40_000);
        pings.add(-40_000);
        for (int i = 0; i < ForeignPings.CAPACITY - 2; i++) pings.add(-50_000 - i);
        pings.add(-60_000);
        assertEquals(true, pings.contains(-40_000));
        pings.add(-60_001);
        assertEquals(false, pings.contains(-40_000));
        pings.remove(-50_000);
        assertEquals(false, pings.contains(-50_000));
        assertEquals(true, pings.contains(-50_001));
    }

    /** The ring as a plain scan, the semantics {@link ForeignPings} keeps. */
    static final class ScannedRing {
        private static final int MASK = ForeignPings.CAPACITY - 1;
        private final int[] ids = new int[ForeignPings.CAPACITY];
        private int head;
        private int size;

        void add(int id) {
            if (size == ForeignPings.CAPACITY) {
                head = (head + 1) & MASK;
                size--;
            }
            ids[(head + size) & MASK] = id;
            size++;
        }

        boolean contains(int id) {
            for (int i = 0; i < size; i++) {
                if (ids[(head + i) & MASK] == id) return true;
            }
            return false;
        }

        void remove(int id) {
            for (int i = 0; i < size; i++) {
                if (ids[(head + i) & MASK] != id) continue;
                for (int j = i; j > 0; j--) ids[(head + j) & MASK] = ids[(head + j - 1) & MASK];
                head = (head + 1) & MASK;
                size--;
                return;
            }
        }
    }
}
