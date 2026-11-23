package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.BundleContents;
import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ComponentWireEncoding;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** An action-local stack; callers copy it before running an independent prediction. */
public final class SimItemStack {
    private static final class Empty {
        private static final SimItemStack VALUE = new SimItemStack(DataTables.defaults().items().stream()
                .filter(item -> item.key().equals("minecraft:air")).findFirst().orElseThrow(), 0, Components.EMPTY);
    }
    public static SimItemStack empty() { return Empty.VALUE; }
    public static final SimItemStack EMPTY = empty();
    private final ItemDefinition item;
    private int count;
    private Components components;
    private final Components prototype;
    private final Supplier<Components> visiblePrototype;
    private ComponentPatch componentPatch;
    private ComponentWireEncoding wirePatch;

    public SimItemStack(ItemDefinition item, int count, Components components) {
        this(item, count, components, components);
    }
    public SimItemStack(ItemDefinition item, int count, Components prototype, Components components) {
        this(item, count, prototype, ComponentPatch.between(prototype, components), () -> prototype);
    }
    /** The patched map keeps its captured prototype; getPrototype reads the item's current holder. */
    public SimItemStack(ItemDefinition item, int count, Components capturedPrototype, ComponentPatch patch,
                        Supplier<Components> visiblePrototype) {
        this.item = Objects.requireNonNull(item);
        this.count = count;
        this.prototype = Objects.requireNonNull(capturedPrototype);
        this.componentPatch = Objects.requireNonNull(patch);
        this.components = patch.applyToCapturedPrototype(capturedPrototype);
        this.visiblePrototype = Objects.requireNonNull(visiblePrototype);
    }
    private SimItemStack(SimItemStack source) {
        item = source.item;
        count = source.count;
        prototype = source.prototype;
        componentPatch = source.componentPatch;
        components = source.components;
        visiblePrototype = source.visiblePrototype;
        wirePatch = source.wirePatch;
    }
    public ItemDefinition definition() { return item; }
    /** Inventory API names preserve the existing menu algorithms during the type migration. */
    public ItemDefinition getItem() { return isEmpty() ? empty().definition() : item; }
    public int getCount() { return count(); }
    public Map<String, Integer> getEnchantments() { return ItemComponents.enchantments(components()); }
    public void setCount(int count) { count(count); }
    public int getMaxStackSize() { return maxStackSize(); }
    public int getDamageValue() { return damage(); }
    public boolean isDamageableItem() { return isDamageable(); }
    public static boolean isSameItemSameComponents(SimItemStack first, SimItemStack second) {
        return first.sameItemSameComponents(second);
    }
    public static boolean matches(SimItemStack first, SimItemStack second) { return first.matches(second); }
    public String itemKey() { return isEmpty() ? "minecraft:air" : item.key(); }
    public Components components() { return isEmpty() ? Components.EMPTY : components; }
    public void components(Components components) {
        wirePatch = null;
        componentPatch = ComponentPatch.between(prototype, Objects.requireNonNull(components));
        this.components = componentPatch.applyToCapturedPrototype(prototype);
    }
    public Components prototype() { return isEmpty() ? Components.EMPTY : Objects.requireNonNull(visiblePrototype.get()); }
    public Components capturedPrototype() { return isEmpty() ? Components.EMPTY : prototype; }
    public ComponentPatch patch() { return isEmpty() ? ComponentPatch.EMPTY : componentPatch; }
    /** Only count changes may reuse a received packet's opaque component patch. */
    public ComponentWireEncoding wirePatch() {
        return wirePatch;
    }
    public void wirePatch(ComponentWireEncoding encoding) {
        wirePatch = encoding;
    }
    public void setComponent(String key, NbtValue value) {
        components(components.with(key, NbtJson.encode(value), value));
    }
    public void removeComponent(String key) { components(components.with(key, null)); }
    public void bundleContents(BundleContents value) { components(components.withBundle(value)); }

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
        return new SimItemStack(this);
    }
}
