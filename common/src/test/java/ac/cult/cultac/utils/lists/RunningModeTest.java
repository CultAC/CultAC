package ac.cult.cultac.utils.lists;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.utils.data.Pair;
import it.unimi.dsi.fastutil.doubles.Double2IntMap;
import it.unimi.dsi.fastutil.doubles.Double2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The running mode keeps the reference algorithm's window, tie order and threshold merging. */
class RunningModeTest {
    @Test
    void matchesTheReferenceModeOnEveryAdd() {
        Random random = new Random(21);
        double[] divisors = {0.0050, 0.0075, 0.00751, 0.0125, 0.0200, 0.0, 0.0300};
        RunningMode mode = new RunningMode(80);
        Reference reference = new Reference(80);
        for (int i = 0; i < 20_000; i++) {
            double value = divisors[random.nextInt(divisors.length)] + (random.nextInt(4) == 0 ? 0.0004 : 0);
            mode.add(value);
            reference.add(value);
            assertEquals(reference.size(), mode.size());
            Pair<Double, Integer> expected = reference.getMode();
            mode.updateMode();
            assertEquals(expected.second(), mode.modeCount(), "count at " + i);
            if (expected.second() > 0) assertEquals(expected.first(), mode.modeValue(), "value at " + i);
            assertEquals(expected, mode.getMode());
        }
    }

    /** The reference algorithm: a boxed window queue and entry iteration. */
    private static final class Reference {
        private final ArrayDeque<Double> addList = new ArrayDeque<>();
        private final Double2IntMap popularityMap = new Double2IntOpenHashMap();
        private final int maxSize;

        Reference(int maxSize) {
            this.maxSize = maxSize;
        }

        int size() {
            return addList.size();
        }

        void add(double value) {
            if (addList.size() >= maxSize) {
                double type = addList.remove();
                int popularity = popularityMap.get(type);
                if (popularity == 1) popularityMap.remove(type);
                else popularityMap.put(type, popularity - 1);
            }
            for (Double2IntMap.Entry entry : popularityMap.double2IntEntrySet()) {
                if (Math.abs(entry.getDoubleKey() - value) < 1e-3) {
                    entry.setValue(entry.getIntValue() + 1);
                    addList.add(entry.getDoubleKey());
                    return;
                }
            }
            popularityMap.put(value, 1);
            addList.add(value);
        }

        Pair<Double, Integer> getMode() {
            int max = 0;
            Double mostPopular = null;
            for (Double2IntMap.Entry entry : popularityMap.double2IntEntrySet()) {
                if (entry.getIntValue() > max) {
                    max = entry.getIntValue();
                    mostPopular = entry.getDoubleKey();
                }
            }
            return new Pair<>(mostPopular, max);
        }
    }
}
