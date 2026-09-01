package com.noxcrew.packet;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Implements a simple multimap that is thread-safe. */
// Originally licensed under LGPL from Noxesium
// https://github.com/Noxcrew/noxesium/blob/main/paper/packet/src/main/kotlin/com/noxcrew/packet/ThreadsafeMultimap.kt
public final class ThreadsafeMultimap<K, V> implements Multimap<K, V> {
    private final Map<K, Collection<V>> backing;

    public ThreadsafeMultimap() {
        this(new ConcurrentHashMap<>());
    }

    public ThreadsafeMultimap(Map<K, Collection<V>> backing) {
        this.backing = backing;
    }

    @Override
    public boolean put(K key, V value) {
        return backing.computeIfAbsent(key, Values::new).add(value);
    }

    @Override
    public int size() {
        int size = 0;
        for (Collection<V> values : backing.values()) {
            size += values.size();
        }
        return size;
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return backing.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return backing.containsValue(value);
    }

    @Override
    public boolean containsEntry(Object key, Object value) {
        Collection<V> values = backing.get(key);
        return values != null && values.contains(value);
    }

    @Override
    public Collection<V> replaceValues(K key, Iterable<? extends V> values) {
        Values newValues = new Values(key);
        for (V value : values) {
            newValues.add(value);
        }
        backing.put(key, newValues);
        return newValues;
    }

    @Override
    public boolean putAll(Multimap<? extends K, ? extends V> multimap) {
        boolean changed = false;
        for (Map.Entry<? extends K, ? extends V> entry : multimap.entries()) {
            if (put(entry.getKey(), entry.getValue())) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean putAll(K key, Iterable<? extends V> values) {
        boolean changed = false;
        for (V value : values) {
            if (put(key, value)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean remove(Object key, Object value) {
        Collection<V> values = backing.get(key);
        if (values == null || !values.remove(value)) {
            return false;
        }
        backing.values().removeIf(Collection::isEmpty);
        return true;
    }

    @Override
    public Collection<V> removeAll(Object key) {
        Collection<V> removed = backing.remove(key);
        return removed == null ? new HashSet<>() : new HashSet<>(removed);
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public Collection<V> get(K key) {
        Collection<V> values = backing.get(key);
        return values == null ? new Values(key) : values;
    }

    @Override
    public Set<K> keySet() {
        return backing.keySet();
    }

    @Override
    public Multiset<K> keys() {
        return HashMultiset.create(backing.keySet());
    }

    @Override
    public Collection<V> values() {
        return new MultimapValueCollection();
    }

    @Override
    public Collection<Map.Entry<K, V>> entries() {
        throw new UnsupportedOperationException("Iterating over entries of threadsafe multimap is not supported");
    }

    @Override
    public Map<K, Collection<V>> asMap() {
        return backing;
    }

    private final class Values extends AbstractCollection<V> {
        private final K key;
        private final CopyOnWriteArrayList<V> inner;

        private Values(K key) {
            this.key = key;
            this.inner = new CopyOnWriteArrayList<>();
        }

        @Override
        public int size() {
            return inner.size();
        }

        @Override
        public boolean add(V element) {
            if (isEmpty()) {
                if (!backing.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Fetching two mutable values and editing both is not allowed in the ThreadsafeMultimap"
                    );
                }
                backing.put(key, this);
            }
            return inner.add(element);
        }

        @Override
        public void clear() {
            inner.clear();
        }

        @Override
        public boolean isEmpty() {
            return inner.isEmpty();
        }

        @Override
        public Iterator<V> iterator() {
            Iterator<V> iterator = inner.iterator();
            return new Iterator<>() {
                private V last;

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public V next() {
                    last = iterator.next();
                    return last;
                }

                @Override
                public void remove() {
                    inner.remove(last);
                }
            };
        }

        @Override
        public boolean remove(Object element) {
            boolean result = inner.remove(element);
            if (result && isEmpty()) {
                backing.remove(key);
            }
            return result;
        }

        @Override
        public boolean contains(Object element) {
            return inner.contains(element);
        }

        @Override
        public boolean containsAll(Collection<?> elements) {
            return inner.containsAll(elements);
        }

        @Override
        public boolean retainAll(Collection<?> elements) {
            boolean changed = false;
            for (V element : inner) {
                if (!elements.contains(element) && remove(element)) {
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> elements) {
            boolean changed = false;
            for (Object element : elements) {
                if (remove(element)) {
                    changed = true;
                }
            }
            return changed;
        }
    }

    private final class MultimapValueCollection extends AbstractCollection<V> {
        @Override
        public int size() {
            return ThreadsafeMultimap.this.size();
        }

        @Override
        public void clear() {
            backing.clear();
        }

        @Override
        public boolean isEmpty() {
            for (Collection<V> values : backing.values()) {
                if (!values.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Iterator<V> iterator() {
            Iterator<Map.Entry<K, Collection<V>>> iterator = backing.entrySet().iterator();
            return new Iterator<>() {
                private Iterator<V> currentSubIterator;
                private K lastKey;
                private V lastValue;

                @Override
                public boolean hasNext() {
                    if (currentSubIterator != null && currentSubIterator.hasNext()) {
                        return true;
                    }

                    if (!iterator.hasNext()) {
                        return false;
                    }
                    Map.Entry<K, Collection<V>> next = iterator.next();
                    lastKey = next.getKey();
                    currentSubIterator = next.getValue().iterator();
                    return hasNext();
                }

                @Override
                public V next() {
                    if (currentSubIterator != null && currentSubIterator.hasNext()) {
                        lastValue = currentSubIterator.next();
                        if (!currentSubIterator.hasNext()) {
                            currentSubIterator = null;
                        }
                        return lastValue;
                    }

                    Map.Entry<K, Collection<V>> next = iterator.next();
                    lastKey = next.getKey();
                    currentSubIterator = next.getValue().iterator();
                    return next();
                }

                @Override
                public void remove() {
                    ThreadsafeMultimap.this.remove(lastKey, lastValue);
                }
            };
        }

        @Override
        public boolean remove(Object element) {
            boolean changed = false;
            for (K key : Set.copyOf(backing.keySet())) {
                Collection<V> values = backing.get(key);
                if (values != null && values.remove(element)) {
                    changed = true;

                    if (values.isEmpty()) {
                        backing.remove(key);
                    }
                }
            }
            return changed;
        }

        @Override
        public boolean add(V element) {
            throw new UnsupportedOperationException("Cannot add to a multimap values() object");
        }

        @Override
        public boolean addAll(Collection<? extends V> elements) {
            throw new UnsupportedOperationException("Cannot add to a multimap values() object");
        }

        @Override
        public boolean contains(Object element) {
            for (Collection<V> values : backing.values()) {
                if (values.contains(element)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean containsAll(Collection<?> elements) {
            for (Object element : elements) {
                if (!contains(element)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean retainAll(Collection<?> elements) {
            boolean changed = false;
            for (V element : this) {
                if (!elements.contains(element) && remove(element)) {
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> elements) {
            boolean changed = false;
            for (Object element : elements) {
                if (remove(element)) {
                    changed = true;
                }
            }
            return changed;
        }
    }
}
