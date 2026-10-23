package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.value.BlockPos;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Owned section-local snapshot. Value objects are created only for consumers of the List API. */
final class PackedSectionUpdates extends AbstractList<WorldPackets.BlockUpdate> implements RandomAccess {
    private final int originX, originY, originZ;
    private final int[] entries;

    private PackedSectionUpdates(long section, int[] entries) {
        originX = (int) (section >> 42) << 4;
        originY = (int) (section << 44 >> 44) << 4;
        originZ = (int) (section << 22 >> 42) << 4;
        this.entries = entries;
    }

    static List<WorldPackets.BlockUpdate> read(long section, int count, LongSupplier input, IntUnaryOperator states) {
        if (count == 0) return List.of();
        if (!WorldPackets.BlockUpdate.hasCompactStateIds()) {
            // Future model registries must retain every valid ID.
            var values = new ArrayList<WorldPackets.BlockUpdate>(count);
            for (int i = 0; i < count; i++) {
                long entry = input.getAsLong();
                values.add(new WorldPackets.BlockUpdate(
                        BlockPos.fromSection(section, (int) (entry & 4095)), states.applyAsInt((int) (entry >>> 12))));
            }
            return List.copyOf(values);
        }
        // The array is created here and never exposed, so no defensive second copy is needed.
        int[] entries = new int[count];
        for (int i = 0; i < count; i++) {
            long entry = input.getAsLong();
            int state = WorldPackets.BlockUpdate.normalizeState(states.applyAsInt((int) (entry >>> 12)));
            entries[i] = (state << 12) | (int) (entry & 4095);
        }
        return new PackedSectionUpdates(section, entries);
    }

    int x(int index) {
        return originX + (entries[index] >>> 8 & 15);
    }

    int y(int index) {
        return originY + (entries[index] & 15);
    }

    int z(int index) {
        return originZ + (entries[index] >>> 4 & 15);
    }

    void runUpdates(WorldPackets.BlockUpdateConsumer consumer) {
        for (int entry : entries) {
            consumer.accept(
                    originX + (entry >>> 8 & 15), originY + (entry & 15), originZ + (entry >>> 4 & 15), entry >>> 12);
        }
    }

    @Override
    public int size() {
        return entries.length;
    }

    @Override
    public WorldPackets.BlockUpdate get(int index) {
        return new WorldPackets.BlockUpdate(new BlockPos(x(index), y(index), z(index)), entries[index] >>> 12);
    }

    // Match List.copyOf's immutable surface, including mutation attempts on an empty view.
    @Override
    public WorldPackets.BlockUpdate set(int index, WorldPackets.BlockUpdate value) {
        throw immutable();
    }

    @Override
    public void add(int index, WorldPackets.BlockUpdate value) {
        throw immutable();
    }

    @Override
    public boolean add(WorldPackets.BlockUpdate value) {
        throw immutable();
    }

    @Override
    public boolean addAll(Collection<? extends WorldPackets.BlockUpdate> values) {
        throw immutable();
    }

    @Override
    public boolean addAll(int index, Collection<? extends WorldPackets.BlockUpdate> values) {
        throw immutable();
    }

    @Override
    public WorldPackets.BlockUpdate remove(int index) {
        throw immutable();
    }

    @Override
    public boolean remove(Object value) {
        throw immutable();
    }

    @Override
    public boolean removeAll(Collection<?> values) {
        throw immutable();
    }

    @Override
    public boolean retainAll(Collection<?> values) {
        throw immutable();
    }

    @Override
    public boolean removeIf(Predicate<? super WorldPackets.BlockUpdate> predicate) {
        throw immutable();
    }

    @Override
    public void replaceAll(UnaryOperator<WorldPackets.BlockUpdate> operator) {
        throw immutable();
    }

    @Override
    public void sort(Comparator<? super WorldPackets.BlockUpdate> comparator) {
        throw immutable();
    }

    @Override
    public void clear() {
        throw immutable();
    }

    @Override
    public List<WorldPackets.BlockUpdate> subList(int from, int to) {
        return Collections.unmodifiableList(super.subList(from, to));
    }

    @Override
    public Iterator<WorldPackets.BlockUpdate> iterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<WorldPackets.BlockUpdate> listIterator(int index) {
        if (index < 0 || index > size()) throw new IndexOutOfBoundsException(index);
        return new ListIterator<>() {
            int cursor = index;

            public boolean hasNext() {
                return cursor < size();
            }

            public WorldPackets.BlockUpdate next() {
                if (!hasNext()) throw new NoSuchElementException();
                return get(cursor++);
            }

            public boolean hasPrevious() {
                return cursor > 0;
            }

            public WorldPackets.BlockUpdate previous() {
                if (!hasPrevious()) throw new NoSuchElementException();
                return get(--cursor);
            }

            public int nextIndex() {
                return cursor;
            }

            public int previousIndex() {
                return cursor - 1;
            }

            public void remove() {
                throw immutable();
            }

            public void set(WorldPackets.BlockUpdate value) {
                throw immutable();
            }

            public void add(WorldPackets.BlockUpdate value) {
                throw immutable();
            }
        };
    }

    private static UnsupportedOperationException immutable() {
        return new UnsupportedOperationException();
    }
}
