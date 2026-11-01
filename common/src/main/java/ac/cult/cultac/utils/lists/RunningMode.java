package ac.cult.cultac.utils.lists;

import ac.cult.cultac.utils.data.Pair;
import it.unimi.dsi.fastutil.doubles.Double2IntMap;
import it.unimi.dsi.fastutil.doubles.Double2IntMaps;
import it.unimi.dsi.fastutil.doubles.Double2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;

// This class is copyright DefineOutside licensed under MIT
//
// This class calculates the running mode of a list in best case o(1) worst case o(n) time.
public class RunningMode {
    private static final double threshold = 1e-3;
    private final double[] addList;
    private int head;
    private int size;
    private final Double2IntMap popularityMap = new Double2IntOpenHashMap();
    private double modeValue;
    private int modeCount;

    private final int maxSize;

    public RunningMode(int maxSize) {
        if (maxSize == 0) throw new IllegalArgumentException("There's no mode to a size 0 list!");
        this.addList = new double[maxSize];
        this.maxSize = maxSize;
    }

    public int size() {
        return size;
    }

    public void add(double value) {
        pop();

        for (Double2IntMap.Entry entry : Double2IntMaps.fastIterable(popularityMap)) {
            if (Math.abs(entry.getDoubleKey() - value) < threshold) {
                entry.setValue(entry.getIntValue() + 1);
                append(entry.getDoubleKey());
                return;
            }
        }

        // Nothing found
        popularityMap.put(value, 1);
        append(value);
    }

    private void append(double value) {
        addList[(head + size) % maxSize] = value;
        size++;
    }

    private void pop() {
        if (size >= maxSize) {
            double type = addList[head];
            head = (head + 1) % maxSize;
            size--;
            int popularity = popularityMap.get(type); // Being null isn't possible
            if (popularity == 1) {
                popularityMap.remove(type); // Make sure not to leak memory
            } else {
                popularityMap.put(type, popularity - 1); // Decrease popularity
            }
        }
    }

    public @NotNull Pair<Double, Integer> getMode() {
        updateMode();
        return new Pair<>(modeCount == 0 ? null : modeValue, modeCount);
    }

    /** Finds the most popular value, read with {@link #modeValue()} and {@link #modeCount()}. */
    public void updateMode() {
        int max = 0;
        double mostPopular = 0;

        for (Double2IntMap.Entry entry : Double2IntMaps.fastIterable(popularityMap)) {
            if (entry.getIntValue() > max) {
                max = entry.getIntValue();
                mostPopular = entry.getDoubleKey();
            }
        }

        modeValue = mostPopular;
        modeCount = max;
    }

    /** The most popular value as of the last {@link #updateMode()}; meaningless while {@link #modeCount()} is 0. */
    public double modeValue() {
        return modeValue;
    }

    /** How often the most popular value occurs, as of the last {@link #updateMode()}. */
    public int modeCount() {
        return modeCount;
    }
}
