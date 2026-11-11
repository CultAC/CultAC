package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link ClientItemUseState} fed as {@link ItemUseInventory#prepare} feeds it, the hotbar only when
 * {@link ClientItemUseState#preparesFromHotbar()}, against its base form fed the hotbar on every prepare: the same
 * snapshots, completions and state after every step of seeded use sequences.
 */
class ItemUsePrepareEquivalenceTest {
    private static final String[] ITEMS = {
        "minecraft:golden_apple",
        "minecraft:bow",
        "minecraft:shield",
        "minecraft:brush",
        "minecraft:diamond_sword",
        "minecraft:bread",
        "minecraft:crossbow",
        "minecraft:spyglass"
    };

    @Test
    void preparingWithoutTheUnreadHotbarMatchesTheBaseForm() throws Exception {
        int withoutHotbar = 0, withHotbar = 0, cached = 0;
        for (ClientVersion version : new ClientVersion[] {ClientVersion.V_1_21_2, ClientVersion.V_1_21_11}) {
            for (int seed = 0; seed < 12; seed++) {
                Random random = new Random(seed * 31L + version.ordinal());
                var state = new ClientItemUseState();
                var base = new BaseClientItemUseState();
                var ids = new IdentityHashMap<Object, Integer>();
                ids.put(SimItemStack.EMPTY, 0);
                List<SimItemStack> hotbar = new ArrayList<>();
                for (int slot = 0; slot < 9; slot++) hotbar.add(stack(random, ids));
                SimItemStack off = stack(random, ids);
                int selected = 0;
                int tick = 0;
                for (int step = 0; step < 600; step++) {
                    SimItemStack main = hotbar.get(selected);
                    String op;
                    switch (random.nextInt(9)) {
                        case 0 -> {
                            Hand hand = random.nextBoolean() ? Hand.MAIN_HAND : Hand.OFF_HAND;
                            SimItemStack held = hand == Hand.MAIN_HAND ? main : off;
                            int remaining = ClientItemUseState.duration(held);
                            boolean certain = random.nextBoolean();
                            state.start(hand, held, remaining, certain);
                            base.start(hand, held, remaining, certain);
                            op = "start " + hand + " " + certain;
                        }
                        case 1 -> {
                            boolean active = random.nextInt(3) != 0;
                            Hand hand = random.nextBoolean() ? Hand.MAIN_HAND : Hand.OFF_HAND;
                            state.serverFlags(active, hand, main, off, List.copyOf(hotbar));
                            base.serverFlags(active, hand, main, off, List.copyOf(hotbar));
                            op = "flags " + active + " " + hand;
                        }
                        case 2 -> {
                            state.release();
                            base.release();
                            op = "release";
                        }
                        case 3 -> {
                            state.claimedSlotChanged();
                            base.claimedSlotChanged();
                            selected = random.nextInt(9);
                            op = "select " + selected;
                        }
                        case 4 -> {
                            int slot = random.nextInt(10);
                            if (slot == 9) off = stack(random, ids);
                            else hotbar.set(slot, stack(random, ids));
                            op = "replace " + slot;
                        }
                        case 5 -> {
                            var completions = state.completions(List.copyOf(hotbar), off);
                            var expected = base.completions(List.copyOf(hotbar), off);
                            assertEquals(dump(expected, ids), dump(completions, ids), "completions at " + step);
                            op = "completions";
                        }
                        default -> {
                            if (random.nextInt(5) != 0) tick++;
                            int proof = random.nextInt(100);
                            boolean reads = state.preparesFromHotbar();
                            if (state.preparedAt(tick) != null) cached++;
                            else if (reads) withHotbar++;
                            else withoutHotbar++;
                            var snapshot = state.prepare(
                                    tick, proof, version, main, off, reads ? List.copyOf(hotbar) : List.of());
                            var expected = base.prepare(tick, proof, version, main, off, List.copyOf(hotbar));
                            assertEquals(dump(expected, ids), dump(snapshot, ids), "snapshot at " + step);
                            op = "prepare " + tick;
                        }
                    }
                    assertEquals(dump(base, ids), dump(state, ids), "state after step " + step + " " + op);
                }
            }
        }
        assertTrue(
                withoutHotbar > 100 && withHotbar > 100 && cached > 10,
                withoutHotbar + " " + withHotbar + " " + cached);
    }

    private static SimItemStack stack(Random random, Map<Object, Integer> ids) {
        if (random.nextInt(6) == 0) return SimItemStack.EMPTY;
        var stack = ItemUtil.modelItems().stack(ITEMS[random.nextInt(ITEMS.length)], 1 + random.nextInt(3));
        ids.put(stack, ids.size());
        return stack;
    }

    /** Field values, list classes, record components; stacks by item, count and identity. */
    private static String dump(Object value, Map<Object, Integer> ids) throws Exception {
        if (value == null) return "null";
        if (value instanceof SimItemStack stack) {
            Integer id = ids.get(stack);
            return stack.itemKey() + "x" + stack.count() + "#" + (id == null ? "copy" : id);
        }
        if (value instanceof List<?> list) {
            var out = new StringBuilder(list.getClass().getName()).append('[');
            for (Object element : list) out.append(dump(element, ids)).append(',');
            return out.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            return "map" + map.size();
        }
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            var out = new StringBuilder(type.getSimpleName()).append('(');
            for (RecordComponent component : type.getRecordComponents()) {
                component.getAccessor().setAccessible(true);
                out.append(component.getName())
                        .append('=')
                        .append(dump(component.getAccessor().invoke(value), ids))
                        .append(' ');
            }
            return out.append(')').toString();
        }
        if (value instanceof ClientItemUseState || value instanceof BaseClientItemUseState) {
            var out = new StringBuilder();
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                out.append(field.getName())
                        .append('=')
                        .append(dump(field.get(value), ids))
                        .append(' ');
            }
            return out.toString();
        }
        return String.valueOf(value);
    }
}
