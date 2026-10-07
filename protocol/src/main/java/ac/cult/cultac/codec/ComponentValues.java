package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.shaded.vialib.api.minecraft.codec.CodecContext;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredData;
import ac.cult.shaded.vialib.api.minecraft.item.Item;
import ac.cult.shaded.vialib.api.minecraft.item.data.*;
import ac.cult.shaded.vialib.api.minecraft.item.data.consumable.Consumable1_21_2;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.api.type.types.version.VersionedTypes;
import ac.cult.shaded.vialib.codec.nbt.NbtOps;
import ac.cult.shaded.vialib.nbt.tag.*;
import ac.cult.shaded.vialib.util.Key;

/** Complete component patches; uninspected values retain their version-specific codec encoding. */
final class ComponentValues {
    // Reviewed client records and their nested numeric records, rather than arbitrary NBT payloads.
    private static final java.util.Set<String> RECORD_NUMBERS = java.util.Set.of(
            "minimum_attack_charge",
            "potion_duration_scale",
            "food",
            "consumable",
            "use_cooldown",
            "tool",
            "attack_range",
            "custom_model_data",
            "attribute_modifiers",
            "blocks_attacks",
            "kinetic_weapon",
            "weapon",
            "use_effects",
            "mob_visibility",
            "death_protection",
            "brewing_fuel",
            "break_sound",
            "equippable");

    private ComponentValues() {}

    static CompoundTag stack(Item item, CodecContext context) {
        var result = new CompoundTag();
        if (item == null || (!item.isTemplate() && item.isEmpty())) {
            result.putString("id", "minecraft:air");
            result.putInt("count", 0);
            return result;
        }
        result.putString("id", context.registryAccess().item(item.identifier()).toString());
        if (!item.isTemplate() || item.amount() != 1) result.putInt("count", item.amount());
        var patch = patch(item, context);
        if (!patch.isEmpty()) result.put("components", patch);
        return result;
    }

    static CompoundTag patch(Item item, CodecContext context) {
        var patch = new CompoundTag();
        for (var data : item.dataContainer().data().values()) {
            String name = Key.stripMinecraftNamespace(data.key().identifier());
            patch.put(
                    (data.isEmpty() ? "!" : "") + Key.namespaced(name),
                    data.isEmpty() ? new CompoundTag() : component(data, context));
        }
        return patch;
    }

    static WireValueDecoder.ComponentEncoding wireEncoding(StructuredData<?> data, ValueContext parent) {
        var context = parent.fork();
        var value = component(data, context);
        valueLayout(data, value, context);
        var buffer = io.netty.buffer.Unpooled.buffer();
        try {
            data.write(buffer);
            return new WireValueDecoder.ComponentEncoding(
                    parent.version().protocol(),
                    io.netty.buffer.ByteBufUtil.getBytes(buffer),
                    context.references(),
                    itemEncodings(data.value(), parent));
        } finally {
            buffer.release();
        }
    }

    private static java.util.Map<String, java.util.Map<String, WireValueDecoder.ComponentEncoding>> itemEncodings(
            Object value, ValueContext context) {
        var result = new java.util.HashMap<String, java.util.Map<String, WireValueDecoder.ComponentEncoding>>();
        if (value instanceof Item item) itemEncodings(result, "0", item, context);
        else if (value instanceof Item[] items)
            for (int index = 0; index < items.length; index++)
                itemEncodings(result, Integer.toString(index), items[index], context);
        else if (value instanceof PotDecorations26_3 pot) {
            itemEncodings(result, "back", pot.back(), context);
            itemEncodings(result, "left", pot.left(), context);
            itemEncodings(result, "right", pot.right(), context);
            itemEncodings(result, "front", pot.front(), context);
        }
        return java.util.Map.copyOf(result);
    }

    private static void itemEncodings(
            java.util.Map<String, java.util.Map<String, WireValueDecoder.ComponentEncoding>> target,
            String slot,
            Item item,
            ValueContext context) {
        if (!present(item)) return;
        var components = new java.util.HashMap<String, WireValueDecoder.ComponentEncoding>();
        for (var data : item.dataContainer().data().values())
            if (!data.isEmpty()) components.put(Key.namespaced(data.key().identifier()), wireEncoding(data, context));
        if (!components.isEmpty()) target.put(slot, java.util.Map.copyOf(components));
    }

    /** ItemContainerContents equality includes optional slots omitted by its persistent asSlots codec. */
    static CompoundTag layouts(Item item, CompoundTag patch, CodecContext context) {
        var result = new CompoundTag();
        for (var data : item.dataContainer().data().values()) {
            if (data.isEmpty()) continue;
            String key = Key.namespaced(data.key().identifier());
            var detail = valueLayout(data, patch.get(key), context);
            if (!detail.isEmpty()) result.put(key, detail);
        }
        return result;
    }

    /** The same source-defined details apply inside an exact component matcher. */
    static CompoundTag valueLayout(StructuredData<?> data, Tag encoding, CodecContext context) {
        String name = Key.stripMinecraftNamespace(data.key().identifier());
        Object value = data.value();
        var detail = value instanceof AdventureModePredicate predicate
                ? PredicateValues.layout(predicate, context)
                : new CompoundTag();
        var children = new CompoundTag();
        if (RECORD_NUMBERS.contains(name)) {
            Tag signs = negativeZero(encoding);
            if (signs != null) detail.put("negative_zero", signs);
        }
        if (value instanceof Item child) childLayout(children, "0", child, encoding, context);
        else if (value instanceof Item[] items) {
            int last = -1;
            int encodedIndex = 0;
            for (int index = 0; index < items.length; index++) {
                if (present(items[index])) {
                    last = index;
                    Tag encodedItem = ((ListTag<?>) encoding).get(name.equals("container") ? encodedIndex++ : index);
                    if (name.equals("container")) encodedItem = ((CompoundTag) encodedItem).get("item");
                    childLayout(children, Integer.toString(index), items[index], encodedItem, context);
                }
            }
            if (name.equals("container") && items.length != last + 1) detail.putInt("slots", items.length);
        } else if (value instanceof PotDecorations26_3 pot) {
            var sides = (CompoundTag) encoding;
            childLayout(children, "back", pot.back(), sides.get("back"), context);
            childLayout(children, "left", pot.left(), sides.get("left"), context);
            childLayout(children, "right", pot.right(), sides.get("right"), context);
            childLayout(children, "front", pot.front(), sides.get("front"), context);
        }
        if (!children.isEmpty()) detail.put("items", children);
        return detail;
    }

    private static boolean present(Item item) {
        return item != null && (item.isTemplate() || !item.isEmpty());
    }

    private static void childLayout(CompoundTag target, String key, Item item, Tag encoding, CodecContext context) {
        if (!present(item)) return;
        var patch = ((CompoundTag) encoding).getCompoundTag("components");
        var layout = layouts(item, patch == null ? new CompoundTag() : patch, context);
        if (!layout.isEmpty()) target.put(key, layout);
    }

    /** Record/boxed-number equality retains the zero sign that FloatTag/DoubleTag.valueOf loses. */
    private static Tag negativeZero(Tag value) {
        if (value instanceof FloatTag number)
            return Float.floatToRawIntBits(number.asFloat()) == Integer.MIN_VALUE ? new IntTag(5) : null;
        if (value instanceof DoubleTag number)
            return Double.doubleToRawLongBits(number.asDouble()) == Long.MIN_VALUE ? new IntTag(6) : null;
        if (!(value instanceof CompoundTag) && !(value instanceof ListTag<?>)) return null;
        var signs = new CompoundTag();
        if (value instanceof CompoundTag object)
            object.getValue().forEach((key, child) -> {
                Tag sign = negativeZero(child);
                if (sign != null) signs.put(key, sign);
            });
        else if (value instanceof ListTag<?> list)
            for (int index = 0; index < list.size(); index++) {
                Tag sign = negativeZero(list.get(index));
                if (sign != null) signs.put(Integer.toString(index), sign);
            }
        return signs.isEmpty() ? null : signs;
    }

    private static CompoundTag typedEntity(CompoundTag payload, String type) {
        var result = payload.copy();
        result.putString("id", type);
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Tag component(StructuredData<?> data, CodecContext context) {
        Object value = data.value();
        String name = Key.stripMinecraftNamespace(data.key().identifier());
        // This persistent Unit uses the generic NBT wire codec. The decoded tag,
        // including a null/end tag, is ignored by the client and represents Unit.
        if (name.equals("intangible_projectile")) return new CompoundTag();
        if (name.equals("container_loot")) return ComponentWrites.containerLoot((CompoundTag) value);
        // The modern writer accepts the same decoded records and emits their current persistent shape.
        if (value instanceof AdventureModePredicate predicate) return PredicateValues.adventure(predicate, context);
        // Via's generic item NBT writer omits nested removals. Keep complete patches,
        // including network-only values, for every item-bearing component instead.
        if (value instanceof Item item) return stack(item, context);
        if (value instanceof Item[] items && !name.equals("container")) {
            var contents = new java.util.ArrayList<CompoundTag>();
            for (var item : items) contents.add(stack(item, context));
            return new ListTag<>(contents);
        }
        if (name.equals("container")) {
            var contents = new java.util.ArrayList<CompoundTag>();
            var items = (Item[]) value;
            for (int slot = 0; slot < items.length; slot++) {
                var item = items[slot];
                if (item == null || (!item.isTemplate() && item.isEmpty())) continue;
                var entry = new CompoundTag();
                entry.putInt("slot", slot);
                entry.put("item", stack(item, context));
                contents.add(entry);
            }
            return new ListTag<>(contents);
        }
        if (name.equals("pot_decorations") && !(value instanceof PotDecorations26_3)) {
            var ordered =
                    (ListTag<?>) NbtOps.serialize(context, (Type) data.key().type(), value);
            var result = new CompoundTag();
            String[] sides = {"back", "left", "right", "front"};
            for (int index = 0; index < sides.length; index++) {
                // Older PotDecorations.ordered fills missing sides with the brick holder.
                var item = new CompoundTag();
                item.putString(
                        "id", index < ordered.size() ? ((StringTag) ordered.get(index)).getValue() : "minecraft:brick");
                result.put(sides[index], item);
            }
            return result;
        }
        if (value instanceof PotDecorations26_3 decorations) {
            var result = new CompoundTag();
            if (decorations.back() != null) result.put("back", stack(decorations.back(), context));
            if (decorations.left() != null) result.put("left", stack(decorations.left(), context));
            if (decorations.right() != null) result.put("right", stack(decorations.right(), context));
            if (decorations.front() != null) result.put("front", stack(decorations.front(), context));
            return result;
        }
        // TypedEntityData strips a payload's own id and retains the authoritative wire type.
        if (value instanceof EntityData entity)
            return typedEntity(
                    entity.tag(), context.registryAccess().entity(entity.type()).toString());
        if (value instanceof BlockEntityData entity)
            return typedEntity(
                    entity.tag(),
                    context.registryAccess().blockEntity(entity.type()).toString());
        // MobEffectInstance constructs each received effect with an amplifier clamped to 0..255.
        if (value instanceof PotionContents potion) {
            return NbtOps.serialize(
                    context,
                    PotionContents.TYPE1_21_2,
                    new PotionContents(
                            potion.potion(),
                            potion.customColor(),
                            ComponentConsumeValues.potions(potion.customEffects(), false),
                            potion.customName()));
        }
        if (value instanceof Consumable1_21_2 consumable) {
            return NbtOps.serialize(
                    context,
                    Consumable1_21_2.TYPE26_3,
                    new Consumable1_21_2(
                            consumable.consumeSeconds(),
                            consumable.animationType(),
                            consumable.sound(),
                            consumable.hasConsumeParticles(),
                            ComponentConsumeValues.normalized(consumable.consumeEffects())));
        }
        if (value instanceof DeathProtection protection) {
            return NbtOps.serialize(
                    context,
                    (Type) data.key().type(),
                    new DeathProtection(ComponentConsumeValues.normalized(protection.deathEffects())));
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
        // Opaque values must not be cast to a newer version's record type. Their
        // source reader/writer owns that schema, including components since removed.
        if (!WireValueDecoder.PROJECTED_COMPONENTS.contains(name))
            return TextValues.component(
                    name, NbtOps.serialize(context, (Type) data.key().type(), value));
        int id = ac.cult.cultac.protocol.data.ModelRegistryData.load(ac.cult.cultac.protocol.ProtocolVersion.V26_3)
                .registry("minecraft:data_component_type")
                .id(Key.namespaced(name));
        var key = VersionedTypes.V26_3.structuredData().key(id);
        if (key == null) throw new IllegalArgumentException("Missing model component " + name);
        return NbtOps.serialize(context, (Type) key.type(), value);
    }
}
