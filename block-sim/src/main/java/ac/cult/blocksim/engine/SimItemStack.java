package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;

/** An action-local stack; callers copy it before running an independent prediction. */
public final class SimItemStack {
    private static final class Empty {
        private static final SimItemStack VALUE = new SimItemStack(ac.cult.blocksim.data.DataTables.defaults().items().stream()
                .filter(item -> item.key().equals("minecraft:air")).findFirst().orElseThrow(), 0, Components.EMPTY);
    }
    public static SimItemStack empty() { return Empty.VALUE; }
    public static final SimItemStack EMPTY = empty();
    private final ItemDefinition item;
    private int count;
    private Components components;
    private final Components prototype;
    private final java.util.function.Supplier<Components> visiblePrototype;
    private ComponentPatch componentPatch;
    private ac.cult.blocksim.data.ComponentWireEncoding wirePatch;

    public SimItemStack(ItemDefinition item, int count, Components components) {
        this(item, count, components, components);
    }
    public SimItemStack(ItemDefinition item, int count, Components prototype, Components components) {
        this(item, count, prototype, ComponentPatch.between(prototype, components), () -> prototype);
    }
    /** The patched map keeps its captured prototype; getPrototype reads the item's current holder. */
    public SimItemStack(ItemDefinition item, int count, Components capturedPrototype, ComponentPatch patch,
                        java.util.function.Supplier<Components> visiblePrototype) {
        this.item = java.util.Objects.requireNonNull(item);
        this.count = count;
        this.prototype = java.util.Objects.requireNonNull(capturedPrototype);
        this.componentPatch = java.util.Objects.requireNonNull(patch);
        this.components = patch.apply(capturedPrototype);
        this.visiblePrototype = java.util.Objects.requireNonNull(visiblePrototype);
    }
    public ItemDefinition definition() { return item; }
    /** Inventory API names preserve the existing menu algorithms during the type migration. */
    public ItemDefinition getItem() { return isEmpty() ? empty().definition() : item; }
    public int getCount() { return count(); }
    public java.util.Map<String, Integer> getEnchantments() { return ItemComponents.enchantments(components()); }
    public void setCount(int count) { count(count); }
    public int getMaxStackSize() { return maxStackSize(); }
    public int getDamageValue() { return damage(); }
    public int getMaxDamage() { return maxDamage(); }
    public boolean isDamageableItem() { return isDamageable(); }
    public static boolean isSameItemSameComponents(SimItemStack first, SimItemStack second) {
        return first.sameItemSameComponents(second);
    }
    public static boolean matches(SimItemStack first, SimItemStack second) { return first.matches(second); }
    public String itemKey() { return isEmpty() ? "minecraft:air" : item.key(); }
    public Components components() { return isEmpty() ? Components.EMPTY : components; }
    public void components(Components components) {
        wirePatch = null;
        componentPatch = ComponentPatch.between(prototype, java.util.Objects.requireNonNull(components));
        this.components = componentPatch.apply(prototype);
    }
    public Components prototype() { return isEmpty() ? Components.EMPTY : java.util.Objects.requireNonNull(visiblePrototype.get()); }
    public Components capturedPrototype() { return isEmpty() ? Components.EMPTY : prototype; }
    public ComponentPatch patch() { return isEmpty() ? ComponentPatch.EMPTY : componentPatch; }
    /** Only count changes may reuse a received packet's opaque component patch. */
    public ac.cult.blocksim.data.ComponentWireEncoding wirePatch() {
        return wirePatch;
    }
    public void wirePatch(ac.cult.blocksim.data.ComponentWireEncoding encoding) {
        wirePatch = encoding;
    }
    public void setComponent(String key, NbtValue value) {
        components(components.with(key, NbtJson.encode(value), value));
    }
    public void removeComponent(String key) { components(components.with(key, null)); }
    public void bundleContents(ac.cult.blocksim.data.BundleContents value) { components(components.withBundle(value)); }

    public boolean isEmpty() { return item.key().equals("minecraft:air") || count <= 0; }
    public boolean is(String key) { return itemKey().equals(key); }

    public int count() { return isEmpty() ? 0 : count; }

    public void count(int count) { this.count = count; }

    public void shrink(int amount) { count(count() - amount); }

    public void consume(int amount, SimPlayer owner) {
        if (owner == null || !owner.state().infiniteMaterials()) shrink(amount);
    }
    public int maxStackSize() { return components().integer("minecraft:max_stack_size", 1); }
    public boolean isDamageable() { return components().has("minecraft:max_damage") && !components().has("minecraft:unbreakable") && components().has("minecraft:damage"); }
    public int maxDamage() { return ItemComponents.maxDamage(components()); }
    public int damage() { return Math.min(Math.max(ItemComponents.damage(components()), 0), maxDamage()); }
    public void damage(int value) {
        setComponent("minecraft:damage", new NbtValue.Numeric(NbtValue.Kind.INT, Math.min(Math.max(value, 0), maxDamage())));
    }
    public boolean isDamaged() { return isDamageable() && damage() > 0; }
    public boolean nextDamageWillBreak() { return isDamageable() && damage() >= maxDamage() - 1; }
    public boolean isStackable() { return maxStackSize() > 1 && (!isDamageable() || !isDamaged()); }
    public boolean sameItemSameComponents(SimItemStack other) {
        if (!itemKey().equals(other.itemKey())) return false;
        return isEmpty() && other.isEmpty() || prototype.equals(other.prototype) && patch().equals(other.patch());
    }
    public void grow(int amount) { count(count() + amount); }
    public void limitSize(int maximum) { if (!isEmpty() && count() > maximum) count(maximum); }
    public SimItemStack split(int amount) {
        int taken = Math.min(count(), amount);
        var result = copyWithCount(taken);
        shrink(taken);
        return result;
    }
    public boolean matches(SimItemStack other) { return count() == other.count() && sameItemSameComponents(other); }
    public SimItemStack copyWithCount(int count) { if (isEmpty()) return empty(); var copy = copy(); copy.count(count); return copy; }
    public SimItemStack copyAndClear() { if (isEmpty()) return empty(); var copy = copy(); count(0); return copy; }
    public SimItemStack copy() {
        if (isEmpty()) return empty();
        var copy = new SimItemStack(item, count(), prototype, componentPatch, visiblePrototype);
        copy.wirePatch(wirePatch());
        return copy;
    }
}
