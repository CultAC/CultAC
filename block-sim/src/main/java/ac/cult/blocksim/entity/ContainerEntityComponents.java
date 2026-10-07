package ac.cult.blocksim.entity;

import ac.cult.blocksim.data.ItemTemplates;
import ac.cult.blocksim.data.ItemTemplate;
import ac.cult.blocksim.data.ComponentLayout;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class ContainerEntityComponents {
    private final ItemTemplates templates;
    ContainerEntityComponents(ItemTemplates templates) { this.templates = templates; }

    void baseContainer(ComponentInput input, int size, boolean omitEmptyItems) {
        saveItems(input, copyInto(input, size), omitEmptyItems);
    }
    void randomizable(ComponentInput input) {
        var loot = input.get("minecraft:container_loot");
        if (loot != null) {
            // Loot-backed client containers remain empty until the server supplies contents.
            input.fields.remove("Items"); input.internalFields.remove("Items");
        }
    }
    void pot(ComponentInput input) {
        SimItemStack item = copyOne(input);
        if (item.isEmpty()) input.fields.remove("item");
        else input.fields.put("item", comparatorItem(item));
    }

    private List<SimItemStack> copyInto(ComponentInput input, int size) {
        var bySlot = new HashMap<Integer, com.google.gson.JsonElement>();
        var nbtBySlot = new HashMap<Integer, NbtValue>();
        var contents = input.get("minecraft:container");
        if (contents != null) {
            for (var entry : contents.getAsJsonArray()) bySlot.put(entry.getAsJsonObject().get("slot").getAsInt(), entry.getAsJsonObject().get("item"));
            for (var entry : ((NbtValue.Sequence)input.nbt("minecraft:container")).values()) {
                var slot = (NbtValue.Compound)entry;
                nbtBySlot.put(((NbtValue.Numeric)slot.values().get("slot")).value().intValue(), slot.values().get("item"));
            }
        }
        var result = new ArrayList<SimItemStack>(size);
        for (int i = 0; i < size; i++) {
            var item = bySlot.get(i);
            result.add(item == null ? templates.decode(new com.google.gson.JsonPrimitive("minecraft:air"))
                : templates.create(ItemTemplate.fromNbt(nbtBySlot.get(i),
                    ComponentLayout.child(input.components, "minecraft:container", Integer.toString(i)))));
        }
        return result;
    }
    private SimItemStack copyOne(ComponentInput input) { return copyInto(input, 1).getFirst(); }

    private static void saveItems(ComponentInput input, List<SimItemStack> items, boolean omitEmpty) {
        var serialized = new ArrayList<NbtValue>();
        for (int i = 0; i < items.size(); i++) if (!items.get(i).isEmpty()) {
            var fields = new HashMap<>(comparatorItem(items.get(i)).values());
            fields.put("Slot", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte)i));
            serialized.add(new NbtValue.Compound(fields));
        }
        var value = new NbtValue.Sequence(serialized);
        input.internalFields.put("Items", NbtJson.encode(value));
        if (omitEmpty && serialized.isEmpty()) input.fields.remove("Items"); else input.fields.put("Items", value);
    }

    private static NbtValue.Compound comparatorItem(SimItemStack item) {
        return new NbtValue.Compound(java.util.Map.of(
            "id", new NbtValue.Text(item.itemKey()), "count", new NbtValue.Numeric(NbtValue.Kind.INT, item.count()),
            "components", new NbtValue.Compound(java.util.Map.of("minecraft:max_stack_size",
                new NbtValue.Numeric(NbtValue.Kind.INT, item.maxStackSize())))));
    }
}
