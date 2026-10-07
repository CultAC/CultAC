package ac.cult.cultac.codec;

import static ac.cult.cultac.codec.ComponentWrites.*;

import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.shaded.vialib.api.minecraft.Holder;
import ac.cult.shaded.vialib.api.minecraft.HolderSet;
import ac.cult.shaded.vialib.api.minecraft.SoundEvent;
import ac.cult.shaded.vialib.api.minecraft.item.data.*;
import ac.cult.shaded.vialib.nbt.tag.*;
import ac.cult.shaded.vialib.util.Either;
import ac.cult.shaded.vialib.util.Key;
import java.util.List;

/** Reconstructs the client records whose fields map directly to Via's versioned types. */
final class ComponentSimpleWrites {
    private ComponentSimpleWrites() {}

    static boolean supports(String name) {
        return switch (name) {
            case "use_effects",
                    "weapon",
                    "attack_range",
                    "attack_animation",
                    "swing_animation",
                    "mob_visibility",
                    "repairable",
                    "damage_resistant",
                    "equippable",
                    "break_sound",
                    "enchantable",
                    "rarity",
                    "item_model",
                    "tooltip_style",
                    "damage_type",
                    "block_transformer" -> true;
            default -> false;
        };
    }

    static Object value(
            String name, Tag tag, Class<?> wireType, ProtocolVersion version, WireValueDecoder.Registries registries) {
        return switch (name) {
            case "item_model" -> new ItemModel(Key.of(Key.namespaced(string(tag))));
            case "tooltip_style" -> Key.of(Key.namespaced(string(tag)));
            case "rarity" -> ordinal(string(tag), List.of("common", "uncommon", "rare", "epic"));
            case "damage_type" ->
                wireType == Either.class
                        ? Either.left(id(registries, name, string(tag)))
                        : id(registries, name, string(tag));
            case "block_transformer" -> id(registries, name, string(tag));
            case "break_sound" -> sound(tag, registries);
            case "enchantable" -> {
                int enchantable = integer(compound(tag), "value", 0);
                if (enchantable <= 0) throw new ProtocolResolutionException("Enchantment value must be positive");
                yield new Enchantable(enchantable);
            }
            default -> record(name, compound(tag), wireType, version, registries);
        };
    }

    private static Object record(
            String name,
            CompoundTag value,
            Class<?> wireType,
            ProtocolVersion version,
            WireValueDecoder.Registries registries) {
        return switch (name) {
            case "use_effects" ->
                new UseEffects(
                        bool(value, "can_sprint", false),
                        bool(value, "interact_vibrations", true),
                        floating(value, "speed_multiplier", .2F));
            case "weapon" ->
                new Weapon(
                        integer(value, "item_damage_per_attack", 1),
                        floating(value, "disable_blocking_for_seconds", 0F));
            case "attack_range" ->
                new AttackRange(
                        floating(value, "min_reach", 0F),
                        floating(value, "max_reach", 3F),
                        floating(value, "min_creative_reach", 0F),
                        floating(value, "max_creative_reach", 5F),
                        floating(value, "hitbox_margin", .3F),
                        floating(value, "mob_factor", 1F));
            case "swing_animation", "attack_animation" ->
                new SwingAnimation(
                        value.contains("type")
                                ? ordinal(string(value.get("type")), List.of("none", "whack", "stab"))
                                : 1,
                        integer(value, "duration", 6));
            case "mob_visibility" ->
                new MobVisibility(
                        holders(value.get("targeting_entity_types"), "entity_type", registries),
                        number(value.get("visibility")).asFloat());
            case "repairable" -> new Repairable(holders(value.get("items"), "item", registries));
            case "damage_resistant" -> {
                Tag types = value.get("types");
                if (wireType == DamageResistant1_21_2.class) {
                    String key = string(types);
                    if (!key.startsWith("#"))
                        throw new ProtocolResolutionException("This damage-resistance schema requires a tag");
                    yield new DamageResistant1_21_2(Key.of(Key.namespaced(key.substring(1))));
                }
                yield new DamageResistant26_1(holders(types, "damage_type", registries));
            }
            case "equippable" -> equipment(value, version, registries);
            default -> throw new ProtocolResolutionException("Unsupported component record " + name);
        };
    }

    private static Equippable equipment(
            CompoundTag value, ProtocolVersion version, WireValueDecoder.Registries registries) {
        int slot = EquipmentSlot.byName(string(value.get("slot"))).componentId();
        var equipSound = value.contains("equip_sound")
                ? sound(value.get("equip_sound"), registries)
                : Holder.<SoundEvent>of(id(registries, "sound_event", "minecraft:item.armor.equip_generic"));
        String asset = identifier(value, "asset_id"), overlay = identifier(value, "camera_overlay");
        var entities = value.contains("allowed_entities")
                ? holders(value.get("allowed_entities"), "entity_type", registries)
                : null;
        boolean dispensable = bool(value, "dispensable", true),
                swappable = bool(value, "swappable", true),
                damageOnHurt = bool(value, "damage_on_hurt", true),
                equipOnInteract = bool(value, "equip_on_interact", false);
        // Earlier wire schemas do not contain shearing fields or their newer sound registry entry.
        if (!version.atLeast(ProtocolVersion.V1_21_6))
            return new Equippable(
                    slot, equipSound, asset, overlay, entities, dispensable, swappable, damageOnHurt, equipOnInteract);
        return new Equippable(
                slot,
                equipSound,
                asset,
                overlay,
                entities,
                dispensable,
                swappable,
                damageOnHurt,
                equipOnInteract,
                bool(value, "can_be_sheared", false),
                value.contains("shearing_sound")
                        ? sound(value.get("shearing_sound"), registries)
                        : Holder.of(id(registries, "sound_event", "minecraft:item.shears.snip")));
    }

    static HolderSet holders(Tag value, String registry, WireValueDecoder.Registries registries) {
        if (value instanceof StringTag text && text.getValue().startsWith("#"))
            return HolderSet.of(Key.namespaced(text.getValue().substring(1)));
        return HolderSet.fromTag(value, name -> id(registries, registry, name));
    }

    static Holder<SoundEvent> sound(Tag value, WireValueDecoder.Registries registries) {
        if (value instanceof StringTag text) return Holder.of(id(registries, "sound_event", text.getValue()));
        var fields = compound(value);
        return Holder.of(new SoundEvent(
                Key.namespaced(string(fields.get("sound_id"))),
                fields.get("range") instanceof NumberTag range ? range.asFloat() : null));
    }

    private static float floating(CompoundTag value, String field, float fallback) {
        return value.contains(field) ? number(value.get(field)).asFloat() : fallback;
    }

    private static String identifier(CompoundTag value, String field) {
        return value.contains(field) ? Key.namespaced(string(value.get(field))) : null;
    }

    private static int ordinal(String name, List<String> values) {
        int index = values.indexOf(name);
        if (index < 0) throw new ProtocolResolutionException("Unknown component enum " + name);
        return index;
    }
}
