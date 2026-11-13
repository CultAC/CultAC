package ac.cult.blocksim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ComponentWireEncoding;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntBiFunction;
import org.junit.jupiter.api.Test;

class ComponentOverlaySizeTest {
    private static final ItemRegistry ITEMS = new ItemRegistry(DataTables.defaults());
    private static final Set<String> NUMERIC = Set.of(
            "minecraft:max_stack_size", "minecraft:damage", "minecraft:max_damage", "minecraft:repair_cost");

    private static int counted(Map<?, ?> map) {
        int count = 0;
        for (var ignored : map.entrySet()) count++;
        return count;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> maps(Components components) throws Exception {
        var maps = new ArrayList<Map<String, ?>>();
        for (String name : List.of("values", "encodedNbt", "layouts", "wireEncodings")) {
            Field field = Components.class.getDeclaredField(name);
            field.setAccessible(true);
            maps.add((Map<String, ?>) field.get(components));
        }
        return maps;
    }

    private static ComponentPatch patch(Random random, Components prototype) {
        var keys = new ArrayList<>(prototype.keys());
        var added = new HashMap<String, NbtValue>();
        var removed = new HashSet<String>();
        for (String key : keys) {
            int pick = random.nextInt(6);
            if (pick == 0) removed.add(key);
            else if (pick == 1 && NUMERIC.contains(key))
                added.put(key, new NbtValue.Numeric(NbtValue.Kind.INT, random.nextInt(9)));
        }
        for (int extra = random.nextInt(4); extra > 0; extra--)
            added.put("minecraft:custom_data", new NbtValue.Compound(Map.of("k", new NbtValue.Text("v" + extra))));
        if (random.nextBoolean()) removed.add("minecraft:not_in_prototype");
        var compound = new HashMap<String, NbtValue>(added);
        removed.forEach(key -> compound.put("!" + key, new NbtValue.Compound(Map.of())));
        return ComponentPatch.fromNbt(new NbtValue.Compound(compound));
    }

    @Test
    void overlaySizesCountTheirEntries() throws Exception {
        var random = new Random(11);
        var items = List.of("minecraft:diamond_sword", "minecraft:stone", "minecraft:elytra", "minecraft:bow",
                "minecraft:shield", "minecraft:golden_apple", "minecraft:potion", "minecraft:bundle");
        for (int round = 0; round < 3000; round++) {
            String item = items.get(random.nextInt(items.size()));
            var prototype = ITEMS.defaults(item);
            var patch = patch(random, prototype);
            var stack = new SimItemStack(ITEMS.item(item), 1, prototype, patch, () -> prototype);
            var overlay = stack.components();
            var materialized = patch.apply(prototype);
            assertEquals(materialized.keys(), overlay.keys());
            assertEquals(materialized.keys().size(), overlay.keys().size());
            assertEquals(materialized, overlay);
            var overlayMaps = maps(overlay);
            var materializedMaps = maps(materialized);
            for (int index = 0; index < overlayMaps.size(); index++) {
                var map = overlayMaps.get(index);
                assertEquals(counted(map), map.size());
                assertEquals(map.isEmpty(), counted(map) == 0);
                assertEquals(materializedMaps.get(index), map);
                assertEquals(map, materializedMaps.get(index));
                assertEquals(materializedMaps.get(index).hashCode(), map.hashCode());
                assertEquals(map.size(), map.entrySet().size());
                assertEquals(map.size(), map.keySet().size());
                assertEquals(map.size(), map.values().size());
            }
        }
    }

    @Test
    void racingReadersAgreeOnSize() throws Exception {
        var random = new Random(3);
        var prototype = ITEMS.defaults("minecraft:diamond_sword");
        for (int round = 0; round < 200; round++) {
            var patch = patch(random, prototype);
            var overlay = new SimItemStack(ITEMS.item("minecraft:diamond_sword"), 1, prototype, patch, () -> prototype)
                    .components();
            int expected = patch.apply(prototype).keys().size();
            var start = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var threads = new ArrayList<Thread>();
            for (int thread = 0; thread < 4; thread++) {
                var reader = new Thread(() -> {
                    try {
                        start.await();
                        if (overlay.keys().size() != expected) throw new AssertionError("size");
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                reader.start();
                threads.add(reader);
            }
            start.countDown();
            for (var thread : threads) thread.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test
    void adoptedWireEncodingsBehaveAsCopiedOnes() {
        byte[] bytes = {3, 1, 4, 1, 5};
        Map<String, Map<Integer, String>> references =
                Map.of("minecraft:enchantment", Map.of(2, "minecraft:sharpness", 5, "minecraft:unbreaking"));
        var child = new ComponentWireEncoding(774, new byte[] {9}, Map.of());
        Map<String, Map<String, ComponentWireEncoding>> items = Map.of("0", Map.of("minecraft:damage", child));
        var copied = new ComponentWireEncoding(774, bytes.clone(), references, items);
        var adopted = ComponentWireEncoding.adopting(774, bytes.clone(), references, items);
        assertArrayEquals(copied.bytes(), adopted.bytes());
        assertNotSame(adopted.bytes(), adopted.bytes());
        assertEquals(copied.buffer(), adopted.buffer());
        assertFalse(adopted.buffer().hasArray() && !adopted.buffer().isReadOnly());
        assertEquals(copied.item("0"), adopted.item("0"));
        assertEquals(copied.item("1"), adopted.item("1"));
        for (var ids : List.<ToIntBiFunction<String, String>>of(
                (registry, name) -> name.equals("minecraft:sharpness") ? 2 : 5,
                (registry, name) -> 2,
                (registry, name) -> -1))
            for (int protocol : new int[] {773, 774})
                assertEquals(copied.matches(protocol, ids), adopted.matches(protocol, ids));
        assertThrows(NullPointerException.class, () -> ComponentWireEncoding.adopting(774, null, Map.of(), Map.of()));
        assertThrows(NullPointerException.class, () -> new ComponentWireEncoding(774, null, Map.of(), Map.of()));
    }
}
