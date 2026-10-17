package ac.cult.cultac.utils.latency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * The section pools of every dimension, one per section position, shared by every player. A dimension's pools change
 * under its own lock, taken before any pool's; lookups for a block write take no dimension lock. A dimension whose
 * last pool goes is retired, and a caller that finds it retired looks it up again.
 */
final class SectionPoolRegistry {
    private static final ConcurrentHashMap<String, Dimension> poolsByDimension = new ConcurrentHashMap<>();

    private SectionPoolRegistry() {}

    static SectionPool forChunk(String dimension, int chunkX, int sectionIndex, int chunkZ) {
        while (true) {
            Dimension pools = poolsByDimension.computeIfAbsent(dimension, name -> new Dimension());
            synchronized (pools) {
                if (pools.retired) continue;
                return pools.entry(chunkX, sectionIndex, chunkZ).pool;
            }
        }
    }

    static void retainChunk(String dimension, int chunkX, int chunkZ, int sectionCount) {
        while (true) {
            Dimension pools = poolsByDimension.computeIfAbsent(dimension, name -> new Dimension());
            synchronized (pools) {
                if (pools.retired) continue;
                for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                    PoolEntry retained = pools.entry(chunkX, sectionIndex, chunkZ);
                    synchronized (retained.pool) {
                        retained.references++;
                    }
                }
                return;
            }
        }
    }

    static void releaseChunk(String dimension, int chunkX, int chunkZ, int sectionCount) {
        Dimension pools = poolsByDimension.get(dimension);
        if (pools == null) return;
        synchronized (pools) {
            if (pools.retired) return;
            Column column = pools.columns.get(Columns.key(chunkX, chunkZ));
            if (column != null) {
                AtomicReferenceArray<PoolEntry> entries = column.entries;
                for (int sectionIndex = 0; sectionIndex < Math.min(sectionCount, entries.length()); sectionIndex++) {
                    PoolEntry entry = entries.get(sectionIndex);
                    if (entry == null) continue;
                    boolean released;
                    synchronized (entry.pool) {
                        released = --entry.references <= 0;
                    }
                    if (released) pools.remove(column, chunkX, sectionIndex, chunkZ);
                }
            }
            if (pools.size == 0) {
                pools.retired = true;
                poolsByDimension.remove(dimension, pools);
            }
        }
    }

    static boolean tryMakeExclusiveForMutation(
            String dimension, int chunkX, int sectionIndex, int chunkZ, CompensatedWorld.CachedSection section) {
        PoolEntry entry = find(dimension, chunkX, sectionIndex, chunkZ);
        if (entry == null) return false;

        synchronized (entry.pool) {
            return entry.references == 1 && entry.pool.detachForSingleReferenceMutation(section);
        }
    }

    static void releaseSectionReference(
            String dimension, int chunkX, int sectionIndex, int chunkZ, CompensatedWorld.CachedSection section) {
        PoolEntry entry = find(dimension, chunkX, sectionIndex, chunkZ);
        if (entry != null) {
            entry.pool.releaseSectionReference(section);
        }
    }

    /** The pool entry at a section position, read without the dimension's lock. */
    private static PoolEntry find(String dimension, int chunkX, int sectionIndex, int chunkZ) {
        Dimension pools = poolsByDimension.get(dimension);
        return pools == null ? null : pools.find(chunkX, sectionIndex, chunkZ);
    }

    static int globalNonUniformPoolSize() {
        int total = 0;
        for (Dimension pools : poolsByDimension.values()) {
            for (PoolEntry entry : pools.entries()) {
                total += entry.pool.nonUniformPoolSize();
            }
        }
        return total;
    }

    static int globalUniformPoolSize() {
        return 0;
    }

    static int poolCount() {
        int total = 0;
        for (Dimension pools : poolsByDimension.values()) {
            synchronized (pools) {
                total += pools.size;
            }
        }
        return total;
    }

    static int totalReferences() {
        int total = 0;
        for (Dimension pools : poolsByDimension.values()) {
            for (PoolEntry entry : pools.entries()) {
                synchronized (entry.pool) {
                    total += entry.references;
                }
            }
        }
        return total;
    }

    static List<SectionPool.DimensionStats> dimensionStats() {
        List<SectionPool.DimensionStats> stats = new ArrayList<>();
        for (var dimensionEntry : poolsByDimension.entrySet()) {
            int references = 0;
            List<PoolEntry> entries = dimensionEntry.getValue().entries();
            for (PoolEntry poolEntry : entries) {
                synchronized (poolEntry.pool) {
                    references += poolEntry.references;
                }
            }
            stats.add(new SectionPool.DimensionStats(dimensionEntry.getKey(), entries.size(), references));
        }
        stats.sort(Comparator.comparing(stat -> stat.dimension().toString()));
        return stats;
    }

    /**
     * One dimension's pools by column, then section index. Changes happen under the dimension's own lock; {@link
     * #find} reads without it. Section indices below zero, which no column array holds, live in a map of their own.
     */
    private static final class Dimension {
        private final Columns columns = new Columns();
        private final ConcurrentHashMap<SectionCoordinate, PoolEntry> belowZero = new ConcurrentHashMap<>();
        private int size;
        private boolean retired;

        PoolEntry find(int chunkX, int sectionIndex, int chunkZ) {
            if (sectionIndex < 0) return belowZero.get(new SectionCoordinate(chunkX, sectionIndex, chunkZ));
            Column column = columns.get(Columns.key(chunkX, chunkZ));
            if (column == null) return null;
            AtomicReferenceArray<PoolEntry> entries = column.entries;
            return sectionIndex >= entries.length() ? null : entries.get(sectionIndex);
        }

        PoolEntry entry(int chunkX, int sectionIndex, int chunkZ) {
            if (sectionIndex < 0) {
                PoolEntry entry = belowZero.get(new SectionCoordinate(chunkX, sectionIndex, chunkZ));
                if (entry == null) {
                    entry = new PoolEntry();
                    belowZero.put(new SectionCoordinate(chunkX, sectionIndex, chunkZ), entry);
                    size++;
                }
                return entry;
            }
            long key = Columns.key(chunkX, chunkZ);
            Column column = columns.get(key);
            if (column == null) {
                column = new Column(sectionIndex + 1);
                columns.put(key, column);
            } else if (sectionIndex >= column.entries.length()) {
                column.grow(sectionIndex + 1);
            }
            PoolEntry entry = column.entries.get(sectionIndex);
            if (entry == null) {
                entry = new PoolEntry();
                column.entries.set(sectionIndex, entry);
                column.live++;
                size++;
            }
            return entry;
        }

        /** Only for a section index of zero or above; nothing below zero is released by column. */
        void remove(Column column, int chunkX, int sectionIndex, int chunkZ) {
            column.entries.set(sectionIndex, null);
            size--;
            if (--column.live == 0) columns.remove(Columns.key(chunkX, chunkZ));
        }

        synchronized List<PoolEntry> entries() {
            List<PoolEntry> entries = new ArrayList<>(size);
            columns.forEach(column -> {
                for (int i = 0; i < column.entries.length(); i++) {
                    PoolEntry entry = column.entries.get(i);
                    if (entry != null) entries.add(entry);
                }
            });
            entries.addAll(belowZero.values());
            return entries;
        }
    }

    /**
     * Columns by packed chunk position: an open-addressed table one writer at a time changes, under its dimension's
     * lock, and any thread reads without locking. A slot's column is published before its key, a removed column
     * leaves its key in place, and a grown table is published whole.
     */
    private static final class Columns {
        private static final long EMPTY = Long.MIN_VALUE;
        private static final int MIN_CAPACITY = 64;

        private volatile Table table = new Table(MIN_CAPACITY);
        /** The column whose key is {@link #EMPTY}, which no slot can hold. */
        private volatile Column emptyKeyColumn;

        private int live;
        /** Slots whose key is set, removed columns included; only the writer reads it. */
        private int used;

        static long key(int chunkX, int chunkZ) {
            return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
        }

        Column get(long key) {
            if (key == EMPTY) return emptyKeyColumn;
            Table current = table;
            int mask = current.keys.length() - 1;
            for (int slot = Table.hash(key) & mask; ; slot = slot + 1 & mask) {
                long found = current.keys.get(slot);
                if (found == EMPTY) return null;
                if (found == key) return current.columns.get(slot);
            }
        }

        void put(long key, Column column) {
            live++;
            if (key == EMPTY) {
                emptyKeyColumn = column;
                return;
            }
            Table current = table;
            int slot = current.slot(key);
            if (current.keys.get(slot) == key) {
                current.columns.set(slot, column);
                return;
            }
            if ((used + 1) * 2 > current.keys.length()) {
                current = rebuilt(Math.max(MIN_CAPACITY, Integer.highestOneBit(Math.max(1, live) * 4)));
                slot = current.slot(key);
            }
            current.columns.set(slot, column);
            current.keys.set(slot, key);
            used++;
        }

        void remove(long key) {
            live--;
            if (key == EMPTY) {
                emptyKeyColumn = null;
                return;
            }
            Table current = table;
            int slot = current.slot(key);
            if (current.keys.get(slot) == key) current.columns.set(slot, null);
        }

        void forEach(Consumer<Column> action) {
            Column empty = emptyKeyColumn;
            if (empty != null) action.accept(empty);
            Table current = table;
            for (int slot = 0; slot < current.keys.length(); slot++) {
                Column column = current.columns.get(slot);
                if (column != null) action.accept(column);
            }
        }

        /** A table of the live columns alone, published before any further change. */
        private Table rebuilt(int capacity) {
            Table old = table;
            Table grown = new Table(capacity);
            int count = 0;
            for (int slot = 0; slot < old.keys.length(); slot++) {
                Column column = old.columns.get(slot);
                if (column == null) continue;
                long key = old.keys.get(slot);
                int at = grown.slot(key);
                grown.columns.set(at, column);
                grown.keys.set(at, key);
                count++;
            }
            used = count;
            table = grown;
            return grown;
        }
    }

    private static final class Table {
        final AtomicLongArray keys;
        final AtomicReferenceArray<Column> columns;

        Table(int capacity) {
            keys = new AtomicLongArray(capacity);
            for (int slot = 0; slot < capacity; slot++) keys.set(slot, Columns.EMPTY);
            columns = new AtomicReferenceArray<>(capacity);
        }

        static int hash(long key) {
            long mixed = key * 0x9E3779B97F4A7C15L;
            return (int) (mixed ^ mixed >>> 32);
        }

        /** The slot holding {@code key}, or the empty slot it would take. */
        int slot(long key) {
            int mask = keys.length() - 1;
            int slot = hash(key) & mask;
            while (true) {
                long found = keys.get(slot);
                if (found == key || found == Columns.EMPTY) return slot;
                slot = slot + 1 & mask;
            }
        }
    }

    /** A column's pools by section index; readers see a grown array whole. */
    private static final class Column {
        private volatile AtomicReferenceArray<PoolEntry> entries;
        private int live;

        Column(int sections) {
            entries = new AtomicReferenceArray<>(Math.max(sections, 24));
        }

        void grow(int sections) {
            AtomicReferenceArray<PoolEntry> old = entries;
            AtomicReferenceArray<PoolEntry> grown = new AtomicReferenceArray<>(sections);
            for (int i = 0; i < old.length(); i++) grown.set(i, old.get(i));
            entries = grown;
        }
    }

    private static final class PoolEntry {
        private final SectionPool pool = new SectionPool();
        private int references;
    }

    private record SectionCoordinate(int chunkX, int sectionIndex, int chunkZ) {}
}
