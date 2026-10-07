package ac.cult.blocksim.engine;

import java.util.ArrayList;
import java.util.List;

/** Native inventory indices 0–35 and equipment indices 36–42, including offhand at 40. */
public final class SimInventory {
    private final List<SimItemStack> slots;
    private final int selected;
    private final boolean infiniteMaterials;
    private final SimItemStack empty;
    public SimInventory(List<SimItemStack> slots, int selected, boolean infiniteMaterials, SimItemStack empty) {
        if (slots.size() != 43 || selected < 0 || selected >= 9 || !empty.isEmpty()) throw new IllegalArgumentException("Invalid native inventory snapshot");
        this.slots = new ArrayList<>(slots); this.selected = selected; this.infiniteMaterials = infiniteMaterials; this.empty = empty;
        this.slots.forEach(java.util.Objects::requireNonNull);
    }
    public int selected() { return selected; }
    public boolean infiniteMaterials() { return infiniteMaterials; }
    SimItemStack empty() { return empty; }
    /** Normally constructed native-player fixture with empty remaining slots. */
    static SimInventory fixture(SimItemStack main, SimItemStack off, boolean infinite) {
        var empty = main.copyWithCount(0); var slots = new ArrayList<SimItemStack>(43);
        // Native Inventory starts with shared ItemStack.EMPTY slots; adding an
        // item replaces the empty slot before mutating its count.
        for (int i = 0; i < 43; i++) slots.add(empty);
        slots.set(0, main); slots.set(40, off);
        return new SimInventory(slots,0,infinite,empty);
    }
    public List<SimItemStack> slots() { return List.copyOf(slots); }
    public SimItemStack get(int index) { return index < slots.size() ? slots.get(index) : empty; }
    public void set(int index, SimItemStack stack) { if (index < slots.size()) slots.set(index, java.util.Objects.requireNonNull(stack)); }
    private boolean hasSpace(SimItemStack current, SimItemStack incoming) {
        return !current.isEmpty() && current.sameItemSameComponents(incoming) && current.isStackable() && current.count() < maxStackSize(current);
    }
    private int maxStackSize() { return 99; }
    private int maxStackSize(SimItemStack stack) { return Math.min(maxStackSize(), stack.maxStackSize()); }
    public int freeSlot() { for (int i = 0; i < 36; i++) if (get(i).isEmpty()) return i; return -1; }
    public int slotWithRemainingSpace(SimItemStack incoming) {
        if (hasSpace(get(selected), incoming)) return selected;
        if (hasSpace(get(40), incoming)) return 40;
        for (int i = 0; i < 36; i++) if (hasSpace(get(i), incoming)) return i;
        return -1;
    }
    private int addResource(SimItemStack incoming) {
        int slot = slotWithRemainingSpace(incoming);
        if (slot == -1) slot = freeSlot();
        return slot == -1 ? incoming.count() : addResource(slot, incoming);
    }
    private int addResource(int slot, SimItemStack incoming) {
        int count = incoming.count(); var current = get(slot);
        if (current.isEmpty()) { current = incoming.copyWithCount(0); set(slot, current); }
        int add = Math.min(count, maxStackSize(current) - current.count());
        if (add == 0) return count;
        current.grow(add); return count - add;
    }
    public boolean add(SimItemStack incoming) { return add(-1, incoming); }
    public boolean add(int slot, SimItemStack incoming) {
        if (incoming.isEmpty()) return false;
        if (incoming.isDamaged()) {
            if (slot == -1) slot = freeSlot();
            if (slot >= 0) { slots.subList(0,36).set(slot, incoming.copyAndClear()); return true; }
            if (infiniteMaterials) { incoming.count(0); return true; }
            return false;
        }
        int last;
        do {
            last = incoming.count(); incoming.count(slot == -1 ? addResource(incoming) : addResource(slot, incoming));
        } while (!incoming.isEmpty() && incoming.count() < last);
        if (incoming.count() == last && infiniteMaterials) { incoming.count(0); return true; }
        return incoming.count() < last;
    }
    public boolean contains(SimItemStack search) {
        for (var stack : slots) if (!stack.isEmpty() && stack.sameItemSameComponents(search)) return true;
        return false;
    }
}
