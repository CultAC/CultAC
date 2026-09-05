package ac.cult.cultac.utils.maps;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Insertion-ordered map that silently drops the eldest entry once it grows past a fixed
 * capacity. Used to bound per-connection ping bookkeeping without explicit pruning.
 */
public class EvictingMap<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    public EvictingMap(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("EvictingMap capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
    }

    public int getCapacity() {
        return capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;
    }
}
