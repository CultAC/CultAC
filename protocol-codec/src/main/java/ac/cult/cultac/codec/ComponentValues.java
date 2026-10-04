package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.WireValueDecoder;
import com.viaversion.nbt.tag.*;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.data.*;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.codec.nbt.NbtOps;
import com.viaversion.viaversion.util.Key;

/** Converts only consumed component values; source readers supply version-specific defaults. */
final class ComponentValues {
    private ComponentValues() {}

    static CompoundTag stack(Item item, CodecContext context) {
        var result = new CompoundTag();
        if (item == null || item.isEmpty()) {
            result.putString("id", "minecraft:air");
            result.putInt("count", 0);
            return result;
        }
        result.putString("id", context.registryAccess().item(item.identifier()).toString());
        result.putInt("count", item.amount());
        var patch = patch(item, context);
        if (!patch.isEmpty()) result.put("components", patch);
        return result;
    }

    static CompoundTag patch(Item item, CodecContext context) {
        var patch = new CompoundTag();
        for (var data : item.dataContainer().data().values()) {
            String name = Key.stripMinecraftNamespace(data.key().identifier());
            if (!WireValueDecoder.COMPONENTS.contains(name)) continue;
            patch.put(
                    (data.isEmpty() ? "!" : "") + Key.namespaced(name),
                    data.isEmpty() ? new CompoundTag() : component(data, context));
        }
        return patch;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Tag component(StructuredData<?> data, CodecContext context) {
        Object value = data.value();
        String name = Key.stripMinecraftNamespace(data.key().identifier());
        // The modern writer accepts the same decoded records and emits their current persistent shape.
        if (value instanceof AdventureModePredicate predicate) return PredicateValues.adventure(predicate, context);
        // Nested stacks need the same consumed-component filtering and network-only
        // adaptations as the outer stack; other values use Via's original NBT writer.
        if (name.equals("bundle_contents")) {
            var contents = new java.util.ArrayList<CompoundTag>();
            for (var item : (Item[]) value) contents.add(stack(item, context));
            return new ListTag<>(contents);
        }
        if (value instanceof CompoundTag tag && (name.equals("entity_data") || name.equals("block_entity_data"))) {
            if (name.equals("entity_data")
                    && tag.get("id") instanceof StringTag id
                    && context instanceof ValueContext source) {
                var converted = tag.copy();
                converted.putString("id", source.modelName("minecraft:entity_type", id.getValue()));
                return converted;
            }
            return tag;
        }
        if (name.equals("map_post_processing")) return new StringTag(((Integer) value) == 1 ? "scale" : "lock");
        if (name.equals("block_transformer"))
            return new StringTag(context.registryAccess()
                    .registryKey("block_transformer", (Integer) value)
                    .toString());
        if (value instanceof Equippable equipment
                && context instanceof ValueContext source
                && !source.version().atLeast(ac.cult.cultac.protocol.ProtocolVersion.V1_21_6)) {
            return NbtOps.serialize(context, Equippable.TYPE1_21_5, equipment);
        }
        int id = ac.cult.cultac.protocol.data.ModelRegistryData.load(ac.cult.cultac.protocol.ProtocolVersion.V26_3)
                .registry("minecraft:data_component_type")
                .id(Key.namespaced(name));
        var key = VersionedTypes.V26_3.structuredData().key(id);
        if (key == null) throw new IllegalArgumentException("Missing model component " + name);
        return NbtOps.serialize(context, (Type) key.type(), value);
    }
}
