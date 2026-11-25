package ac.cult.blocksim.data.nbt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CompoundAdoptionTest {
    /** Keys whose hashes collide or share a 16-bin index, plus ordinary component field names. */
    private static final List<String> KEYS = List.of(
            "Aa", "BB", "AaAa", "BBBB", "AaBB", "BBAa", "text", "color", "italic", "bold", "extra", "with",
            "minecraft:lore", "minecraft:custom_name", "minecraft:damage", "id", "count", "slot", "item", "a", "b", "q",
            "type", "separator", "hover_event", "underlined", "strikethrough", "obfuscated", "font", "insertion");

    private static NbtValue leaf(int index) {
        return index % 2 == 0 ? new NbtValue.Text("v" + index) : new NbtValue.Numeric(NbtValue.Kind.INT, index);
    }

    private static void assertSameCompound(Map<String, NbtValue> source) {
        var direct = new NbtValue.Compound(source);
        var adopted = NbtValue.Compound.of(source);
        assertEquals(direct, adopted);
        assertEquals(direct.hashCode(), adopted.hashCode());
        assertEquals(new ArrayList<>(direct.values().keySet()), new ArrayList<>(adopted.values().keySet()));
        assertEquals(new ArrayList<>(direct.values().values()), new ArrayList<>(adopted.values().values()));
        assertEquals(direct.values().getClass(), adopted.values().getClass());
        assertEquals(direct.values().toString(), adopted.values().toString());
        assertThrows(UnsupportedOperationException.class, () -> adopted.values().put("x", leaf(1)));
    }

    @Test
    void adoptedMapsIterateAsTheirCopies() {
        var random = new Random(7);
        for (int round = 0; round < 4000; round++) {
            int size = random.nextInt(14);
            var keys = new ArrayList<>(KEYS);
            Collections.shuffle(keys, random);
            var plain = new HashMap<String, NbtValue>();
            var sized = new HashMap<String, NbtValue>(4 << random.nextInt(6));
            var shrunk = new HashMap<String, NbtValue>();
            var linked = new LinkedHashMap<String, NbtValue>();
            var sorted = new TreeMap<String, NbtValue>();
            for (int index = 0; index < size; index++) {
                var value = leaf(index);
                plain.put(keys.get(index), value);
                sized.put(keys.get(index), value);
                linked.put(keys.get(index), value);
                sorted.put(keys.get(index), value);
            }
            for (int index = 0; index < 20; index++) shrunk.put(keys.get(index), leaf(index));
            for (int index = size; index < 20; index++) shrunk.remove(keys.get(index));
            for (var map : List.<Map<String, NbtValue>>of(plain, sized, shrunk, linked, sorted)) assertSameCompound(map);
        }
        assertSameCompound(Map.of());
        assertSameCompound(Map.of("a", leaf(0), "b", leaf(1)));
    }

    @Test
    void nullsFailAsTheConstructorDoes() {
        var nullKey = new HashMap<String, NbtValue>();
        nullKey.put("a", leaf(0));
        nullKey.put(null, leaf(1));
        var nullValue = new HashMap<String, NbtValue>();
        nullValue.put("a", leaf(0));
        nullValue.put("b", null);
        for (var map : List.of(nullKey, nullValue)) {
            var direct = assertThrows(NullPointerException.class, () -> new NbtValue.Compound(new HashMap<>(map)));
            var adopted = assertThrows(NullPointerException.class, () -> NbtValue.Compound.of(map));
            assertEquals(direct.getMessage(), adopted.getMessage());
        }
    }
}
