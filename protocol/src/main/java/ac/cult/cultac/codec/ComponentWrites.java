package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.shaded.vialib.api.minecraft.GlobalBlockPosition;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredDataKey;
import ac.cult.shaded.vialib.api.minecraft.item.data.*;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.nbt.tag.*;
import ac.cult.shaded.vialib.util.Key;
import io.netty.buffer.ByteBuf;
import java.util.LinkedHashMap;

/** Reconstructs changed client values; Via owns their version-specific wire layout. */
final class ComponentWrites {
    private static final java.util.Set<String> SUPPORTED = java.util.Set.of(
            "damage",
            "max_damage",
            "max_stack_size",
            "map_id",
            "repair_cost",
            "ominous_bottle_amplifier",
            "custom_data",
            "bucket_entity_data",
            "container_loot",
            "creative_slot_lock",
            "glider",
            "potion_contents",
            "lodestone_tracker",
            "map_post_processing",
            "dyed_color",
            "block_state",
            "entity_data",
            "block_entity_data",
            "minimum_attack_charge",
            "potion_duration_scale",
            "enchantment_glint_override",
            "unbreakable",
            "intangible_projectile");

    private ComponentWrites() {}

    static boolean supports(String key) {
        String name = Key.stripMinecraftNamespace(key);
        return SUPPORTED.contains(name)
                || ComponentRecordWrites.supports(name)
                || ComponentSimpleWrites.supports(name)
                || ComponentConsumeValues.supports(name);
    }

    @SuppressWarnings("unchecked")
    static void write(
            ByteBuf output,
            StructuredDataKey<?> key,
            Tag tag,
            ProtocolVersion version,
            WireValueDecoder.Registries registries) {
        String name = Key.stripMinecraftNamespace(key.identifier());
        Object value = switch (name) {
            case "damage", "max_damage", "max_stack_size", "map_id", "repair_cost", "ominous_bottle_amplifier" ->
                number(tag).asInt();
            case "custom_data", "bucket_entity_data" -> compound(tag);
            case "container_loot" -> containerLoot(compound(tag));
            case "creative_slot_lock", "glider" -> ac.cult.shaded.vialib.util.Unit.INSTANCE;
            case "minimum_attack_charge", "potion_duration_scale" -> number(tag).asFloat();
            case "enchantment_glint_override" -> number(tag).asDouble() != 0.0;
            case "unbreakable" ->
                version.atLeast(ProtocolVersion.V1_21_5)
                        ? ac.cult.shaded.vialib.util.Unit.INSTANCE
                        : new Unbreakable(
                                tag instanceof CompoundTag object ? bool(object, "show_in_tooltip", true) : true);
            case "intangible_projectile" -> new CompoundTag();
            case "potion_contents" -> potion(tag, registries);
            case "lodestone_tracker" -> lodestone(compound(tag));
            case "map_post_processing" ->
                switch (string(tag)) {
                    case "lock" -> 0;
                    case "scale" -> 1;
                    default -> throw new ProtocolResolutionException("Unknown map post-processing value");
                };
            case "dyed_color" ->
                tag instanceof CompoundTag object
                        ? new DyedColor(color(object.get("rgb")), bool(object, "show_in_tooltip", true))
                        : new DyedColor(color(tag));
            case "block_state" -> blockState(compound(tag));
            case "entity_data", "block_entity_data" -> entityData(name, compound(tag), version, registries);
            default ->
                ComponentConsumeValues.supports(name)
                        ? ComponentConsumeValues.value(name, tag, version, registries)
                        : ComponentSimpleWrites.supports(name)
                                ? ComponentSimpleWrites.value(
                                        name, tag, key.type().getOutputClass(), version, registries)
                                : ComponentRecordWrites.value(name, tag, registries);
        };
        ((Type<Object>) key.type()).write(output, value);
    }

    private static PotionContents potion(Tag tag, WireValueDecoder.Registries registries) {
        if (tag instanceof StringTag)
            return new PotionContents(id(registries, "potion", string(tag)), null, new PotionEffect[0], null);
        var value = compound(tag);
        var effects = value.getListTag("custom_effects", CompoundTag.class);
        var result = new PotionEffect[effects == null ? 0 : effects.size()];
        for (int index = 0; index < result.length; index++) {
            var effect = effects.get(index);
            result[index] = new PotionEffect(
                    id(registries, "mob_effect", string(effect.get("id"))),
                    ComponentConsumeValues.effectData(effect, false));
        }
        return new PotionContents(
                value.contains("potion") ? id(registries, "potion", string(value.get("potion"))) : null,
                value.contains("custom_color")
                        ? number(value.get("custom_color")).asInt()
                        : null,
                result,
                value.contains("custom_name") ? string(value.get("custom_name")) : null);
    }

    private static LodestoneTracker lodestone(CompoundTag value) {
        var target = value.getCompoundTag("target");
        GlobalBlockPosition position = null;
        if (target != null) {
            Tag pos = target.get("pos");
            int x, y, z;
            if (pos instanceof IntArrayTag array && array.getValue().length == 3) {
                int[] coordinates = array.getValue();
                x = coordinates[0];
                y = coordinates[1];
                z = coordinates[2];
            } else if (pos instanceof ListTag<?> array && array.size() == 3) {
                x = number(element(array.get(0))).asInt();
                y = number(element(array.get(1))).asInt();
                z = number(element(array.get(2))).asInt();
            } else throw new ProtocolResolutionException("Lodestone target needs three coordinates");
            position = new GlobalBlockPosition(Key.namespaced(string(target.get("dimension"))), x, y, z);
        }
        return new LodestoneTracker(position, bool(value, "tracked", true));
    }

    private static BlockStateProperties blockState(CompoundTag value) {
        var properties = new LinkedHashMap<String, String>();
        value.getValue().forEach((key, tag) -> properties.put(key, string(tag)));
        return new BlockStateProperties(properties);
    }

    static CompoundTag containerLoot(CompoundTag value) {
        var result = new CompoundTag();
        result.putString("loot_table", Key.namespaced(string(value.get("loot_table"))));
        long seed = value.contains("seed") ? number(value.get("seed")).asLong() : 0L;
        if (seed != 0L) result.putLong("seed", seed);
        return result;
    }

    private static int color(Tag value) {
        if (value instanceof NumberTag number) return number.asInt();
        if (value instanceof ListTag<?> rgb && rgb.size() == 3)
            return 0xFF000000 | channel(rgb.get(0)) << 16 | channel(rgb.get(1)) << 8 | channel(rgb.get(2));
        throw new ProtocolResolutionException("Expected integer color or three RGB channels");
    }

    private static int channel(Tag value) {
        float scaled = number(element(value)).asFloat() * 255F;
        return (int) Math.floor(scaled) & 255;
    }

    private static Object entityData(
            String name, CompoundTag value, ProtocolVersion version, WireValueDecoder.Registries registries) {
        if (!version.atLeast(ProtocolVersion.V1_21_9)) return value;
        var payload = value.copy();
        String type = string(payload.remove("id"));
        return name.equals("entity_data")
                ? new EntityData(id(registries, "entity_type", type), payload)
                : new BlockEntityData(id(registries, "block_entity_type", type), payload);
    }

    static int id(WireValueDecoder.Registries registries, String registry, String name) {
        int id = registries.id("minecraft:" + registry, Key.namespaced(name));
        if (id < 0) throw new ProtocolResolutionException("Unknown " + registry + " value " + name);
        return id;
    }

    static int integer(CompoundTag value, String field, int fallback) {
        Tag tag = value.get(field);
        return tag == null ? fallback : number(tag).asInt();
    }

    static boolean bool(CompoundTag value, String field, boolean fallback) {
        Tag tag = value.get(field);
        return tag == null ? fallback : number(tag).asDouble() != 0.0;
    }

    static Tag element(Tag value) {
        // The model's heterogeneous NBT lists wrap each element once, as the client does.
        return value instanceof CompoundTag object && object.size() == 1 && object.contains("")
                ? object.get("")
                : value;
    }

    static NumberTag number(Tag tag) {
        if (tag instanceof NumberTag number) return number;
        throw new ProtocolResolutionException("Expected component number");
    }

    static String string(Tag tag) {
        if (tag instanceof StringTag text) return text.getValue();
        throw new ProtocolResolutionException("Expected component string");
    }

    static CompoundTag compound(Tag tag) {
        if (tag instanceof CompoundTag object) return object;
        throw new ProtocolResolutionException("Expected component object");
    }
}
