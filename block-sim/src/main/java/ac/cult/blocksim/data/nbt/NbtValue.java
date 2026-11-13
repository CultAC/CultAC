package ac.cult.blocksim.data.nbt;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        /** Below this size a copy is a 16-bin HashMap whose bins hold fewer entries than treeify. */
        private static final int ADOPTED = 8;

        public Compound {
            // CompoundTag reads into a default HashMap. Its iteration decides which
            // duplicate namespace alias wins in persistent component/recipe codecs.
            // Map.copyOf randomizes that order, even though the values stay equal.
            if (values instanceof Adopted adopted) {
                var map = adopted.map;
                map.forEach((key, value) -> {
                    Objects.requireNonNull(key);
                    Objects.requireNonNull(value);
                });
                values = map.isEmpty() ? Map.of() : Collections.unmodifiableMap(map);
            } else {
                var copy = new HashMap<String, NbtValue>();
                values.forEach((key, value) -> copy.put(Objects.requireNonNull(key),
                        Objects.requireNonNull(value)));
                values = copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
            }
        }

        /**
         * Equal to {@code new Compound(values)}, iterating in the same order. A small {@link HashMap} that
         * already iterates as its copy would is kept, not copied: the caller hands it over and never changes it
         * again.
         */
        public static Compound of(Map<String, NbtValue> values) {
            return values.getClass() == HashMap.class && values.size() < ADOPTED && copyOrder(values)
                    ? new Compound(new Adopted((HashMap<String, NbtValue>) values))
                    : new Compound(values);
        }

        /** Whether {@code values} iterates by ascending bin of a 16-bin HashMap, the order its copy iterates in. */
        private static boolean copyOrder(Map<String, NbtValue> values) {
            int previous = 0;
            for (String key : values.keySet()) {
                int hash = key == null ? 0 : key.hashCode();
                int bin = (hash ^ hash >>> 16) & 15;
                if (bin < previous) return false;
                previous = bin;
            }
            return true;
        }

        private static final class Adopted extends AbstractMap<String, NbtValue> {
            private final HashMap<String, NbtValue> map;

            private Adopted(HashMap<String, NbtValue> map) {
                this.map = map;
            }

            @Override
            public Set<Entry<String, NbtValue>> entrySet() {
                return map.entrySet();
            }
        }
    }
}
