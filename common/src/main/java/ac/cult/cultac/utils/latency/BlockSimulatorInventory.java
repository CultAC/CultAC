package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.SimInventory;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.utils.inventory.Inventory;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** One action's inventory snapshot, using the packet layer's compensated storage. */
public final class BlockSimulatorInventory {
    private BlockSimulatorInventory() {}

    public static SimInventory capture(CompensatedInventory inventory, boolean infiniteMaterials, ItemRegistry items) {
        return capture(inventory, infiniteMaterials, items, false);
    }

    /** Completed client destruction reads the main hand; preserve offhand use without copying other slots. */
    public static SimInventory captureHands(
            CompensatedInventory inventory, boolean infiniteMaterials, ItemRegistry items) {
        return capture(inventory, infiniteMaterials, items, true);
    }

    private static SimInventory capture(
            CompensatedInventory inventory, boolean infiniteMaterials, ItemRegistry items, boolean onlyHands) {
        var slots = new ArrayList<SimItemStack>(43);
        var copies = new IdentityHashMap<SimItemStack, SimItemStack>();
        var empty = items.empty();
        var storage = inventory.inventory.getInventoryStorage();
        for (int index = 0; index < 43; index++) {
            if (onlyHands && index != inventory.inventory.selected && index != 40) {
                slots.add(empty);
                continue;
            }
            var stack = storage.getItem(storageSlot(index));
            slots.add(stack == null || stack.isEmpty() ? empty : copies.computeIfAbsent(stack, SimItemStack::copy));
        }
        return new SimInventory(slots, inventory.inventory.selected, infiniteMaterials, empty);
    }

    public static void apply(CompensatedInventory inventory, SimInventory before, SimInventory after) {
        var changed = resolveChanges(before, after);
        var storage = inventory.inventory.getInventoryStorage();
        changed.forEach((index, stack) -> storage.setItem(storageSlot(index), stack));
    }

    /** Resolve all detached changes before applying world or inventory mutations. */
    public static java.util.Map<Integer, SimItemStack> resolveChanges(SimInventory before, SimInventory after) {
        var copies = new IdentityHashMap<SimItemStack, SimItemStack>();
        var changes = new java.util.TreeMap<Integer, SimItemStack>();
        for (int index = 0; index < 43; index++) {
            var old = before.get(index);
            var changed = after.get(index);
            if (old.count() == changed.count()
                    && old.itemKey().equals(changed.itemKey())
                    && old.capturedPrototype().equals(changed.capturedPrototype())
                    && old.capturedPrototype()
                            .encodedNbt()
                            .equals(changed.capturedPrototype().encodedNbt())
                    && old.patch().equals(changed.patch())
                    && old.patch()
                            .added()
                            .encodedNbt()
                            .equals(changed.patch().added().encodedNbt())) continue;
            var stack = copies.computeIfAbsent(changed, SimItemStack::copy);
            changes.put(index, stack);
        }
        return java.util.Collections.unmodifiableMap(changes);
    }

    public static int storageSlot(int slot) {
        if (slot < 0 || slot >= 43) throw new IllegalArgumentException("Invalid player inventory slot " + slot);
        if (slot < 9) return slot + Inventory.HOTBAR_OFFSET;
        if (slot < 36) return slot;
        return switch (slot) {
            case 36 -> Inventory.SLOT_BOOTS;
            case 37 -> Inventory.SLOT_LEGGINGS;
            case 38 -> Inventory.SLOT_CHESTPLATE;
            case 39 -> Inventory.SLOT_HELMET;
            case 40 -> Inventory.SLOT_OFFHAND;
            case 41 -> Inventory.SLOT_BODY;
            case 42 -> Inventory.SLOT_SADDLE;
            default -> throw new AssertionError(slot);
        };
    }
}
