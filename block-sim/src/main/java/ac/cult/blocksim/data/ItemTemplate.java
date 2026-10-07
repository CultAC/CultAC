package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.NbtValue;
import java.util.Map;

/** Immutable nested item input. Its raw count and unsanitized patch differ from a live stack. */
public record ItemTemplate(String itemKey, int count, ComponentPatch patch) {
    public ItemTemplate {
        itemKey = HolderSets.identifier(itemKey);
        java.util.Objects.requireNonNull(patch);
        if (count == 0 || itemKey.equals("minecraft:air")) throw new IllegalArgumentException("Empty item template");
    }

    public static ItemTemplate fromNbt(NbtValue value) {
        return fromNbt(value, ComponentLayout.EMPTY);
    }
    public static ItemTemplate fromNbt(NbtValue value, NbtValue.Compound layouts) {
        if (value instanceof NbtValue.Text key) return new ItemTemplate(key.value(), 1, ComponentPatch.EMPTY);
        var fields = ((NbtValue.Compound)value).values();
        String key = ((NbtValue.Text)fields.get("id")).value();
        int count = fields.get("count") instanceof NbtValue.Numeric number ? number.value().intValue() : 1;
        var components = fields.get("components") instanceof NbtValue.Compound patch ? patch : new NbtValue.Compound(Map.of());
        return new ItemTemplate(key, count, ComponentPatch.fromNbt(components, layouts));
    }

    public ItemTemplate withWireEncodings(Map<String, ComponentWireEncoding> encodings) {
        return new ItemTemplate(itemKey, count, new ComponentPatch(patch.added().withWireEncodings(encodings), patch.removed()));
    }

    public static ItemTemplate fromJson(com.google.gson.JsonElement value) {
        if (value.isJsonPrimitive()) return new ItemTemplate(value.getAsString(), 1, ComponentPatch.EMPTY);
        var fields = value.getAsJsonObject();
        return new ItemTemplate(fields.get("id").getAsString(), fields.has("count") ? fields.get("count").getAsInt() : 1,
                fields.has("components") ? ComponentPatch.fromJson(fields.getAsJsonObject("components")) : ComponentPatch.EMPTY);
    }

    public static ItemTemplate fromNonEmptyStack(ac.cult.blocksim.engine.SimItemStack stack) {
        if (stack.isEmpty()) throw new IllegalStateException("Stack must be non-empty");
        return new ItemTemplate(stack.itemKey(), stack.count(), stack.patch());
    }
    public com.google.gson.JsonObject json() {
        var result = new com.google.gson.JsonObject();
        result.addProperty("id", itemKey);
        if (count != 1) result.addProperty("count", count);
        if (!patch.added().keys().isEmpty() || !patch.removed().isEmpty()) {
            var components = com.google.gson.JsonParser.parseString(patch.added().json()).getAsJsonObject();
            patch.removed().forEach(key -> components.add("!" + key, new com.google.gson.JsonObject()));
            result.add("components", components);
        }
        return result;
    }
    /** Template codecs omit count=1; they keep the raw component patch rather than a live map. */
    public NbtValue encodedNbt() {
        var fields = new java.util.HashMap<String, NbtValue>();
        fields.put("id", new NbtValue.Text(itemKey));
        if (count != 1) fields.put("count", new NbtValue.Numeric(NbtValue.Kind.INT, count));
        if (!patch.added().keys().isEmpty() || !patch.removed().isEmpty()) {
            var components = new java.util.HashMap<>(patch.added().encodedNbt().values());
            patch.removed().forEach(key -> components.put("!" + key, new NbtValue.Compound(Map.of())));
            fields.put("components", new NbtValue.Compound(components));
        }
        return new NbtValue.Compound(fields);
    }

    /** Templates apply their patch to the holder's current prototype, before stack creation. */
    public Components components(ItemRegistry items) { return patch.apply(items.defaults(itemKey)); }
}
