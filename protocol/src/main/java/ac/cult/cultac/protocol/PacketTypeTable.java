package ac.cult.cultac.protocol;

import java.util.Map;

/** An immutable map from packet types to values, looked up by {@link PacketType#index()}. */
public final class PacketTypeTable<V> {
    private static final PacketTypeTable<?> EMPTY = new PacketTypeTable<>(new Object[0]);

    private final Object[] values;

    private PacketTypeTable(Object[] values) {
        this.values = values;
    }

    @SuppressWarnings("unchecked")
    public static <V> PacketTypeTable<V> empty() {
        return (PacketTypeTable<V>) EMPTY;
    }

    /** The entries of {@code map}, which holds no null key or value. */
    public static <V> PacketTypeTable<V> of(Map<PacketType<?>, ? extends V> map) {
        int length = 0;
        for (PacketType<?> type : map.keySet()) length = Math.max(length, type.index() + 1);
        Object[] values = new Object[length];
        map.forEach((type, value) -> values[type.index()] = value);
        return new PacketTypeTable<>(values);
    }

    /** The value of {@code type}; null when it has none. */
    @SuppressWarnings("unchecked")
    public V get(PacketType<?> type) {
        int index = type.index();
        return index < values.length ? (V) values[index] : null;
    }
}
