package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Item bindings and immutable default components from the official data generator. */
public final class ItemRegistry {
    private final Map<String, ItemDefinition> items;
    private final Map<String, Components> defaults;
    private final boolean[] containerItems;
    private final Function<String, Components> compensatedDefaults;

    public ItemRegistry(DataTables data) {
        this(data, key -> null);
    }
    /** The override reads only registry components established at the proven client tick. */
    public ItemRegistry(DataTables data, Function<String, Components> compensatedDefaults) {
        this.compensatedDefaults = Objects.requireNonNull(compensatedDefaults);
        var items = new HashMap<String, ItemDefinition>();
        var defaults = new HashMap<String, Components>();
        containerItems = new boolean[data.items().size()];
        for (ItemDefinition item : data.items()) {
            if (items.put(item.key(), item) != null) throw new IllegalArgumentException("Duplicate item " + item.key());
            var report = JsonParser.parseString(item.defaultComponentsJson()).getAsJsonObject();
            String nbt = item.bindings().get("components.defaultNbt");
            var encoding = nbt == null ? Map.<String, NbtValue>of()
                : ((NbtValue.Compound)CanonicalSnbt.parse(nbt)).values();
            defaults.put(item.key(), new Components(report.getAsJsonObject("components").asMap(), encoding));
            containerItems[item.id()] = item.block().isEmpty()
                    || !List.of(data.registry().block(item.block()).bindings().get("classHierarchy").split(","))
                            .contains("net.minecraft.world.level.block.ShulkerBoxBlock");
        }
        this.items = Map.copyOf(items);
        this.defaults = Map.copyOf(defaults);
    }
    public ItemDefinition item(String key) {
        ItemDefinition item = items.get(key);
        if (item == null) throw new IllegalArgumentException("Unknown item " + key);
        return item;
    }
    /** Item/BlockItem's container rule, compiled once from the existing generated class bindings. */
    public boolean canFitInsideContainerItems(ItemDefinition item) { return containerItems[item.id()]; }
    public Components defaults(String key) {
        item(key); Components current = compensatedDefaults.apply(key);
        return current == null ? defaults.get(key) : current;
    }
    public SimItemStack stack(String key, int count) {
        return stack(key, count, ComponentPatch.EMPTY);
    }
    /** New stacks sanitize received patches against the prototype captured at construction. */
    public SimItemStack stack(String key, int count, ComponentPatch patch) {
        var prototype = defaults(key);
        var sanitized = patch.sanitized(prototype);
        return new SimItemStack(item(key), count, prototype, sanitized, () -> defaults(key));
    }
    public SimItemStack empty() { return SimItemStack.empty(); }
}
