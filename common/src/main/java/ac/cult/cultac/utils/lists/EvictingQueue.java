package ac.cult.cultac.utils.lists;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Objects;
import java.util.RandomAccess;

// https://stackoverflow.com/a/21047889
// License: Originally CC By-SA 4.0 licensed as GPL
public class EvictingQueue<K> extends AbstractList<K> implements RandomAccess {
    private static final Object[] EMPTY = {};

    private final int maxSize;
    private Object[] elements = EMPTY;
    private int head;
    private int size;

    public EvictingQueue(int size) {
        this.maxSize = size;
    }

    @Override
    public boolean add(K k) {
        modCount++;
        if (maxSize <= 0) return true;
        if (size == elements.length) {
            if (size == maxSize) {
                elements[head] = k;
                head = head + 1 == size ? 0 : head + 1;
                return true;
            }
            grow();
        }
        elements[slot(size)] = k;
        size++;
        return true;
    }

    private void grow() {
        Object[] grown = new Object[Math.min(maxSize, Math.max(10, elements.length + (elements.length >> 1)))];
        for (int i = 0; i < size; i++) grown[i] = elements[slot(i)];
        elements = grown;
        head = 0;
    }

    private int slot(int index) {
        int slot = head + index;
        return slot >= elements.length ? slot - elements.length : slot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K get(int index) {
        Objects.checkIndex(index, size);
        return (K) elements[slot(index)];
    }

    @Override
    @SuppressWarnings("unchecked")
    public K set(int index, K k) {
        Objects.checkIndex(index, size);
        int slot = slot(index);
        K previous = (K) elements[slot];
        elements[slot] = k;
        return previous;
    }

    @Override
    public K remove(int index) {
        K removed = get(index);
        modCount++;
        for (int i = index; i < size - 1; i++) elements[slot(i)] = elements[slot(i + 1)];
        elements[slot(size - 1)] = null;
        size--;
        return removed;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        modCount++;
        for (int i = 0; i < size; i++) elements[slot(i)] = null;
        head = 0;
        size = 0;
    }

    /** Unsupported. */
    @Override
    public boolean addAll(Collection<? extends K> c) {
        throw new UnsupportedOperationException();
    }

    public K getYoungest() {
        return get(size() - 1);
    }

    public K getOldest() {
        return get(0);
    }
}
