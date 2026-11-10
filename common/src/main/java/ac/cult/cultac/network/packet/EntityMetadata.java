package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Consumed values plus owned entry bytes, so untouched metadata never needs re-encoding. */
public record EntityMetadata(int id, List<Entry> packedItems) implements ClientboundPacket {
    public EntityMetadata {
        if (!(packedItems instanceof Entries)) packedItems = List.copyOf(packedItems);
    }

    /**
     * The immutable list of entries {@code 0..size-1}, entry {@code i} as {@link Entry#of(int, Object, byte[], int,
     * int)} makes it from index {@code indices[i]}, value {@code values[i]} and the {@code lengths[i]} bytes of
     * {@code payload} from index {@code offsets[i] - payloadStart}. Only {@code payload} is kept.
     */
    public static List<Entry> entries(
            int size, int[] indices, Object[] values, int[] offsets, int[] lengths, byte[] payload, int payloadStart) {
        for (int i = 0; i < size; i++)
            Objects.checkFromIndexSize(offsets[i] - payloadStart, lengths[i], payload.length);
        return build(size, indices, values, offsets, lengths, payload, -payloadStart);
    }

    /**
     * The immutable list of entries {@code 0..size-1}, entry {@code i} as {@link Entry#of(int, Object, FrameLease,
     * int, int)} makes it from index {@code indices[i]}, value {@code values[i]} and the {@code lengths[i]} bytes of
     * {@code lease}'s frame from absolute index {@code offsets[i]}. Only {@code lease} is kept.
     */
    public static List<Entry> entries(
            int size, int[] indices, Object[] values, int[] offsets, int[] lengths, FrameLease lease) {
        for (int i = 0; i < size; i++)
            Objects.checkFromIndexSize(offsets[i], lengths[i], lease.frame().writerIndex());
        return build(size, indices, values, offsets, lengths, lease, 0);
    }

    private static List<Entry> build(
            int size, int[] indices, Object[] values, int[] offsets, int[] lengths, Object payload, int shift) {
        switch (size) {
            case 0:
                return List.of();
            case 1:
                return List.of(new Entry(indices[0], values[0], payload, offsets[0] + shift, lengths[0]));
            case 2:
                return List.of(
                        new Entry(indices[0], values[0], payload, offsets[0] + shift, lengths[0]),
                        new Entry(indices[1], values[1], payload, offsets[1] + shift, lengths[1]));
            default:
                var packed = Entries.pack(size, indices, values, offsets, lengths, payload, shift);
                if (packed != null) return packed;
                var entries = new Entry[size];
                for (int i = 0; i < size; i++)
                    entries[i] = new Entry(indices[i], values[i], payload, offsets[i] + shift, lengths[i]);
                return List.of(entries);
        }
    }

    /**
     * Entries decoded from one packet's contiguous bytes, held as indices, ends and values.
     * {@link EntityMetadata#packedItems()} may be this type. It is an immutable list equal to the list of their {@link Entry} objects, and behaves as
     * {@link List#of} does for every list operation; it makes the objects once, on first element access, and lookups
     * by index make none.
     */
    public static final class Entries extends AbstractCollection<Entry> implements List<Entry>, RandomAccess {
        private static final int MAX_END = (1 << 23) - 1;
        private static final VarHandle MATERIALIZED;

        static {
            try {
                MATERIALIZED = MethodHandles.lookup().findVarHandle(Entries.class, "materialized", Object[].class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /** A read-only {@link ByteBuffer}, a {@code byte[]} or a {@link FrameLease}. */
        private final Object payload;
        /** Where the first entry's bytes begin in {@link #payload}. */
        private final int start;
        /** Per entry, its end relative to {@link #start} shifted left by 8 bits, with its index in the low byte. */
        private final int[] packed;

        private final Object[] values;
        /** Bit {@code i} is set when an entry has index {@code i < 64}. */
        private final long present;

        /** {@link Entry} objects in an {@code Object[]}, the array class {@link List#of} lists hold. */
        @SuppressWarnings("unused") // Through MATERIALIZED.
        private Object[] materialized;

        private Entries(Object payload, int start, int[] packed, Object[] values, long present) {
            this.payload = payload;
            this.start = start;
            this.packed = packed;
            this.values = values;
            this.present = present;
        }

        /** Null unless every index fits a byte and each entry's bytes directly follow the previous entry's. */
        private static Entries pack(
                int size, int[] indices, Object[] values, int[] offsets, int[] lengths, Object payload, int shift) {
            int first = offsets[0];
            int[] packed = new int[size];
            long present = 0;
            for (int i = 0, expected = first; i < size; i++) {
                int index = indices[i];
                if ((index & ~0xFF) != 0 || offsets[i] != expected) return null;
                int end = offsets[i] + lengths[i] - first;
                if (end < 0 || end > MAX_END) return null;
                packed[i] = end << 8 | index;
                if (index < 64) present |= 1L << index;
                expected = offsets[i] + lengths[i];
            }
            return new Entries(payload, first + shift, packed, Arrays.copyOf(values, size), present);
        }

        /** Whether an entry has {@code index}. */
        public boolean has(int index) {
            return (index & ~63) == 0 ? (present & 1L << index) != 0 : position(index) >= 0;
        }

        /** The value of the first entry with {@code index}; null when there is none. */
        public Object value(int index) {
            int position = position(index);
            return position < 0 ? null : values[position];
        }

        /** The first entry with {@code index}; null when there is none. */
        public Entry entry(int index) {
            int position = position(index);
            return position < 0 ? null : (Entry) materialized()[position];
        }

        private int position(int index) {
            if ((index & ~63) == 0 && (present & 1L << index) == 0) return -1;
            int[] packed = this.packed;
            for (int i = 0; i < packed.length; i++) if ((packed[i] & 0xFF) == index) return i;
            return -1;
        }

        private Object[] materialized() {
            var entries = (Object[]) MATERIALIZED.getAcquire(this);
            if (entries != null) return entries;
            entries = new Object[packed.length];
            for (int i = 0, from = 0; i < entries.length; i++) {
                int end = packed[i] >>> 8;
                entries[i] = new Entry(packed[i] & 0xFF, values[i], payload, start + from, end - from);
                from = end;
            }
            var witness = (Object[]) MATERIALIZED.compareAndExchangeRelease(this, null, entries);
            return witness == null ? entries : witness;
        }

        /** The {@link List#of} list of the same entries, for the views it makes. */
        @SuppressWarnings("unchecked")
        private List<Entry> listOf() {
            return (List<Entry>) (List<?>) List.of(materialized());
        }

        @Override
        public Entry get(int index) {
            if (index < 0 || index >= packed.length)
                throw new ArrayIndexOutOfBoundsException(
                        "Index " + index + " out of bounds for length " + packed.length);
            return (Entry) materialized()[index];
        }

        @Override
        public int size() {
            return packed.length;
        }

        @Override
        public boolean isEmpty() {
            return packed.length == 0;
        }

        @Override
        public int indexOf(Object o) {
            if (o == null) throw new NullPointerException();
            Object[] entries = materialized();
            for (int i = 0; i < entries.length; i++) if (o.equals(entries[i])) return i;
            return -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            if (o == null) throw new NullPointerException();
            Object[] entries = materialized();
            for (int i = entries.length - 1; i >= 0; i--) if (o.equals(entries[i])) return i;
            return -1;
        }

        @Override
        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }

        @Override
        public Object[] toArray() {
            return Arrays.asList(materialized()).toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return Arrays.asList(materialized()).toArray(a);
        }

        @Override
        public Iterator<Entry> iterator() {
            return new Itr(this, false, 0);
        }

        @Override
        public ListIterator<Entry> listIterator() {
            return listIterator(0);
        }

        @Override
        public ListIterator<Entry> listIterator(int index) {
            if (index < 0 || index > packed.length)
                throw new IndexOutOfBoundsException("Index: " + index + " Size: " + packed.length);
            return new Itr(this, true, index);
        }

        @Override
        public List<Entry> subList(int fromIndex, int toIndex) {
            return listOf().subList(fromIndex, toIndex);
        }

        @Override
        public List<Entry> reversed() {
            return listOf().reversed();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof List<?> list)) return false;
            Iterator<?> other = list.iterator();
            for (int i = 0, s = size(); i < s; i++) if (!other.hasNext() || !get(i).equals(other.next())) return false;
            return !other.hasNext();
        }

        @Override
        public int hashCode() {
            int hash = 1;
            for (int i = 0, s = size(); i < s; i++) hash = 31 * hash + get(i).hashCode();
            return hash;
        }

        @Override
        public boolean add(Entry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(int index, Entry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(Collection<? extends Entry> entries) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(int index, Collection<? extends Entry> entries) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry remove(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry removeFirst() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry removeLast() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(Collection<?> entries) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(Predicate<? super Entry> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(Collection<?> entries) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceAll(UnaryOperator<Entry> operator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry set(int index, Entry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sort(Comparator<? super Entry> comparator) {
            throw new UnsupportedOperationException();
        }

        /** Iterates as a {@link List#of} list's iterators do: every mutator is unsupported. */
        private static final class Itr implements ListIterator<Entry> {
            private final Entries list;
            private final boolean listIterator;
            private int cursor;

            Itr(Entries list, boolean listIterator, int cursor) {
                this.list = list;
                this.listIterator = listIterator;
                this.cursor = cursor;
            }

            @Override
            public boolean hasNext() {
                return cursor != list.packed.length;
            }

            @Override
            public Entry next() {
                try {
                    int i = cursor;
                    Entry next = list.get(i);
                    cursor = i + 1;
                    return next;
                } catch (IndexOutOfBoundsException e) {
                    throw new NoSuchElementException();
                }
            }

            @Override
            public boolean hasPrevious() {
                if (!listIterator) throw new UnsupportedOperationException();
                return cursor != 0;
            }

            @Override
            public Entry previous() {
                if (!listIterator) throw new UnsupportedOperationException();
                try {
                    int i = cursor - 1;
                    Entry previous = list.get(i);
                    cursor = i;
                    return previous;
                } catch (IndexOutOfBoundsException e) {
                    throw new NoSuchElementException();
                }
            }

            @Override
            public int nextIndex() {
                if (!listIterator) throw new UnsupportedOperationException();
                return cursor;
            }

            @Override
            public int previousIndex() {
                if (!listIterator) throw new UnsupportedOperationException();
                return cursor - 1;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void set(Entry entry) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void add(Entry entry) {
                throw new UnsupportedOperationException();
            }
        }
    }

    /**
     * One entry. A null value means the payload was traversed but is not consumed by the anticheat. Its bytes are a
     * range of a read-only payload, of an array, or of a lent frame, whose position and limit are never moved, so
     * views are cut on demand. Bytes of a lent frame are readable only until its lease ends; a holder that keeps the
     * entry past its frame and needs the bytes keeps {@link #detach()} instead.
     */
    public static final class Entry {
        private final int id;
        private final Object value;
        /** A read-only {@link ByteBuffer}, a {@code byte[]} or a {@link FrameLease}. */
        private final Object payload;
        private final int offset;
        private final int length;

        /** The entry's bytes are {@code bytes}' remaining bytes, kept through a read-only view of their own. */
        public Entry(int id, Object value, ByteBuffer bytes) {
            this(id, value, bytes.asReadOnlyBuffer(), bytes.position(), bytes.remaining());
        }

        private Entry(int id, Object value, Object payload, int offset, int length) {
            this.id = id;
            this.value = value;
            this.payload = payload;
            this.offset = offset;
            this.length = length;
        }

        /**
         * An entry whose bytes are {@code length} bytes of {@code payload} from absolute index {@code offset}. The
         * payload must be read-only, and nothing may change its contents, position or limit afterwards.
         */
        public static Entry of(int id, Object value, ByteBuffer payload, int offset, int length) {
            if (!payload.isReadOnly()) throw new IllegalArgumentException("Entry payload must be read-only");
            Objects.checkFromIndexSize(offset, length, payload.limit());
            return new Entry(id, value, payload, offset, length);
        }

        /**
         * An entry whose bytes are {@code length} bytes of {@code payload} from index {@code offset}. The entry keeps
         * the array: nothing may change its contents afterwards.
         */
        public static Entry of(int id, Object value, byte[] payload, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, payload.length);
            return new Entry(id, value, payload, offset, length);
        }

        /**
         * An entry whose bytes are {@code length} bytes of {@code lease}'s frame from absolute index {@code offset},
         * readable while the lease lasts. Nothing may change those bytes while it does.
         */
        public static Entry of(int id, Object value, FrameLease lease, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, lease.frame().writerIndex());
            return new Entry(id, value, lease, offset, length);
        }

        public int id() {
            return id;
        }

        public Object value() {
            return value;
        }

        /**
         * A fresh read-only view of the entry's bytes, at position 0.
         *
         * @throws IllegalStateException when the bytes were borrowed from a frame that has since been released
         */
        public ByteBuffer bytes() {
            if (payload instanceof ByteBuffer buffer) return buffer.slice(offset, length);
            if (payload instanceof FrameLease lease)
                return ByteBuffer.wrap(ByteBufUtil.getBytes(lease.frame(), offset, length))
                        .asReadOnlyBuffer();
            return ByteBuffer.wrap((byte[]) payload, offset, length).slice().asReadOnlyBuffer();
        }

        /**
         * Writes the entry's bytes to {@code output}.
         *
         * @throws IllegalStateException when the bytes were borrowed from a frame that has since been released
         */
        public void writeTo(ByteBuf output) {
            if (payload instanceof FrameLease lease) {
                ByteBuf frame = lease.frame();
                // Netty copies out of a direct buffer it cannot address through a fresh duplicate per call.
                if (!frame.hasArray() && frame.nioBufferCount() == 1)
                    output.writeBytes(frame.internalNioBuffer(offset, length));
                else output.writeBytes(frame, offset, length);
            } else if (payload instanceof byte[] array) output.writeBytes(array, offset, length);
            else output.writeBytes(((ByteBuffer) payload).slice(offset, length));
        }

        /**
         * This entry with bytes of its own: itself unless its bytes are borrowed from a frame, which must still be
         * live.
         */
        public Entry detach() {
            if (!(payload instanceof FrameLease lease)) return this;
            return new Entry(id, value, ByteBufUtil.getBytes(lease.frame(), offset, length), 0, length);
        }

        /** EntityDataSerializers.BYTE is serializer 0 in every supported observed version. */
        public static Entry byteValue(int index, byte value) {
            return new Entry(
                    index,
                    value,
                    ByteBuffer.allocate(3)
                            .put((byte) index)
                            .put((byte) 0)
                            .put(value)
                            .flip());
        }

        /** Serializer IDs are source-verified for every supported observed version. */
        public static Entry health(int index, ProtocolVersion version, float value) {
            int serializer = switch (version) {
                case V1_21_3, V1_21_4, V1_21_5, V1_21_6, V1_21_7, V1_21_9, V1_21_11, V26_1, V26_2, V26_3 -> 3;
            };
            return new Entry(
                    index,
                    value,
                    ByteBuffer.allocate(6)
                            .put((byte) index)
                            .put((byte) serializer)
                            .putFloat(value)
                            .flip());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry
                    && id == entry.id
                    && Objects.equals(value, entry.value)
                    && bytes().equals(entry.bytes());
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Integer.hashCode(id) + Objects.hashCode(value)) + bytes().hashCode();
        }

        @Override
        public String toString() {
            boolean released = payload instanceof FrameLease lease && !lease.live();
            return "Entry[id=" + id + ", value=" + value + ", bytes=" + (released ? "<released frame>" : bytes()) + "]";
        }
    }
}
