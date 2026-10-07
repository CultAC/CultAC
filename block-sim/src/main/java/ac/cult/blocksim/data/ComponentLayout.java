package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.NbtValue;
import java.util.Map;

/** Source-defined wire details, independent of a component's persistent NBT value. */
public final class ComponentLayout {
    public static final NbtValue.Compound EMPTY = new NbtValue.Compound(Map.of());
    private ComponentLayout() {}

    public static NbtValue.Compound child(Components components, String key, String index) {
        var layout = components.layout(key);
        if (layout != null && layout.values().get("items") instanceof NbtValue.Compound items
                && items.values().get(index) instanceof NbtValue.Compound child) return child;
        return EMPTY;
    }

    public static int containerSlots(Components components) {
        var layout = components.layout("minecraft:container");
        if (layout != null && layout.values().get("slots") instanceof NbtValue.Numeric slots)
            return slots.value().intValue();
        int size = 0;
        var contents = components.get("minecraft:container");
        if (contents != null) for (var entry : contents.getAsJsonArray())
            size = Math.max(size, entry.getAsJsonObject().get("slot").getAsInt() + 1);
        return size;
    }
}
