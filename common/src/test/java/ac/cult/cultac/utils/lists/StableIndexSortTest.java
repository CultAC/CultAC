package ac.cult.cultac.utils.lists;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** StableIndexSort leaves exactly an insertion sort's order, with O(n log n) comparisons. */
class StableIndexSortTest {
    @Test
    void matchesAStableInsertionSort() {
        Random random = new Random(7);
        for (int round = 0; round < 4_000; round++) {
            int count = round < 2_000 ? round % 40 : random.nextInt(1_100);
            // Few distinct keys, so most entries tie and only stability fixes their order.
            int[] keys = new int[count + random.nextInt(3)];
            int distinct = 1 + random.nextInt(round % 3 == 0 ? 4 : 1_000);
            for (int i = 0; i < keys.length; i++) keys[i] = random.nextInt(distinct);
            int[] order = shuffled(random, keys.length);
            int[] expected = order.clone();
            insertionSort(expected, count, (a, b) -> keys[a] < keys[b], new long[1]);
            int[] actual = order.clone();
            StableIndexSort.sort(actual, new int[count], count, (a, b) -> keys[a] < keys[b]);
            assertArrayEquals(expected, actual, "count " + count);
        }
    }

    @Test
    void comparesAtMostNLogN() {
        for (int count : new int[] {16, 64, 256, 1_024}) {
            int[] reversed = new int[count];
            for (int i = 0; i < count; i++) reversed[i] = count - 1 - i;
            long[] merge = new long[1], insertion = new long[1];
            StableIndexSort.Precedes byIndex = (a, b) -> a < b;
            StableIndexSort.sort(reversed.clone(), new int[count], count, (a, b) -> {
                merge[0]++;
                return byIndex.test(a, b);
            });
            insertionSort(reversed.clone(), count, byIndex, insertion);
            long bound = (long) count * (32 - Integer.numberOfLeadingZeros(count - 1));
            assertTrue(merge[0] <= bound, count + " entries took " + merge[0] + " comparisons");
            assertTrue(insertion[0] > bound, "an insertion sort of " + count + " takes " + insertion[0]);
        }
    }

    private static void insertionSort(int[] order, int count, StableIndexSort.Precedes precedes, long[] compares) {
        for (int i = 1; i < count; i++) {
            int actor = order[i];
            int j = i - 1;
            while (j >= 0) {
                compares[0]++;
                if (!precedes.test(actor, order[j])) break;
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = actor;
        }
    }

    private static int[] shuffled(Random random, int count) {
        int[] order = new int[count];
        Arrays.setAll(order, i -> i);
        for (int i = count - 1; i > 0; i--) {
            int j = random.nextInt(i + 1), swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        return order;
    }
}
