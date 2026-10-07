package ac.cult.blocksim.data.nbt;

import java.util.List;
import java.util.Map;

/** Typed portable NBT. Numeric kinds and primitive arrays must survive the packet boundary. */
public sealed interface NbtValue {
    enum Kind { BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, INT_ARRAY, LONG_ARRAY }
    record Numeric(Kind kind, Number value) implements NbtValue { }
    record Text(String value) implements NbtValue { }
    record Sequence(List<NbtValue> values) implements NbtValue {
        public Sequence { values = List.copyOf(values); }
    }
    record PrimitiveArray(Kind kind, List<Long> values) implements NbtValue {
        public PrimitiveArray { values = List.copyOf(values); }
    }
    record Compound(Map<String, NbtValue> values) implements NbtValue {
        public Compound {
            // CompoundTag reads into a default HashMap. Its iteration decides which
            // duplicate namespace alias wins in persistent component/recipe codecs.
            // Map.copyOf randomizes that order, even though the values stay equal.
            var copy = new java.util.HashMap<String, NbtValue>();
            values.forEach((key, value) -> copy.put(java.util.Objects.requireNonNull(key),
                    java.util.Objects.requireNonNull(value)));
            values = copy.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(copy);
        }
    }
}
