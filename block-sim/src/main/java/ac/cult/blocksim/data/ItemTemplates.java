package ac.cult.blocksim.data;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonElement;

/** Template creation and strict component validation, including raw nested template counts. */
public final class ItemTemplates {
    private final ItemRegistry items;
    public ItemTemplates(ItemRegistry items) { this.items = java.util.Objects.requireNonNull(items); }

    public SimItemStack create(JsonElement value) {
        return create(value, null);
    }
    public SimItemStack create(JsonElement value, NbtValue encoding) {
        SimItemStack result = decode(value, encoding);
        return valid(result) ? result : items.empty();
    }
    public SimItemStack create(ItemTemplate template) {
        var result = decode(template);
        return valid(result) ? result : items.empty();
    }
    public SimItemStack decode(ItemTemplate template) { return items.stack(template.itemKey(), template.count(), template.patch()); }

    /** Decoding follows ItemStackTemplate.MAP_CODEC; the boundary has already checked codec validity. */
    public SimItemStack decode(JsonElement value) {
        return decode(value, null);
    }
    public SimItemStack decode(JsonElement value, NbtValue encoding) {
        if (encoding != null) return decode(ItemTemplate.fromNbt(encoding));
        String key = value.isJsonPrimitive() ? value.getAsString() : value.getAsJsonObject().get("id").getAsString();
        int count = value.isJsonPrimitive() || !value.getAsJsonObject().has("count") ? 1 : value.getAsJsonObject().get("count").getAsInt();
        var patch = value.isJsonObject() && value.getAsJsonObject().has("components")
                ? ComponentPatch.fromJson(value.getAsJsonObject().getAsJsonObject("components")) : ComponentPatch.EMPTY;
        return items.stack(HolderSets.identifier(key), count, patch);
    }

    public boolean valid(SimItemStack stack) {
        return validComponents(stack.components()) && stack.count() <= stack.components().integer("minecraft:max_stack_size", 1);
    }

    private boolean validComponents(Components components) {
        if (components.has("minecraft:max_damage") && components.integer("minecraft:max_stack_size", 1) > 1) return false;
        var container = components.get("minecraft:container");
        if (container != null) {
            // ItemContainerContents.fromSlots keeps only the last template at each slot.
            var slots = new java.util.HashMap<Integer, ItemTemplate>();
            if (components.hasEncodedNbt("minecraft:container")) {
                for (var entry : ((NbtValue.Sequence)components.encodedNbt("minecraft:container")).values()) {
                    var fields = ((NbtValue.Compound)entry).values();
                    int slot = ((NbtValue.Numeric)fields.get("slot")).value().intValue();
                    slots.put(slot, ItemTemplate.fromNbt(fields.get("item"), ComponentLayout.child(components, "minecraft:container", Integer.toString(slot))));
                }
            } else for (var entry : container.getAsJsonArray()) {
                var fields = entry.getAsJsonObject();
                slots.put(fields.get("slot").getAsInt(), ItemTemplate.fromJson(fields.get("item")));
            }
            if (!validContainedSizes(slots.values())) return false;
        }
        var bundle = components.bundle();
        if (bundle != null) {
            if (!validContainedSizes(bundle.items()) || !bundle.weight(items).valid()) return false;
        }
        var projectiles = ItemComponents.itemTemplates(components, "minecraft:charged_projectiles");
        return projectiles == null || validContainedSizes(projectiles);
    }

    private boolean validContainedSizes(Iterable<ItemTemplate> values) {
        for (var item : values) {
            if (item.count() > item.components(items).integer("minecraft:max_stack_size", 1)) return false;
        }
        return true;
    }

}
