package ac.cult.cultac.codec;

import static ac.cult.cultac.codec.ComponentWrites.*;

import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.shaded.vialib.api.minecraft.Holder;
import ac.cult.shaded.vialib.api.minecraft.SoundEvent;
import ac.cult.shaded.vialib.api.minecraft.item.data.*;
import ac.cult.shaded.vialib.api.minecraft.item.data.consumable.*;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.nbt.tag.*;
import ac.cult.shaded.vialib.util.Key;
import ac.cult.shaded.vialib.util.Unit;

/** Portable consume records; effect execution remains in the existing simulation. */
final class ComponentConsumeValues {
    private ComponentConsumeValues() {}

    static boolean supports(String name) {
        return name.equals("consumable") || name.equals("death_protection");
    }

    static Object value(String name, Tag tag, ProtocolVersion version, WireValueDecoder.Registries registries) {
        var fields = compound(tag);
        if (name.equals("death_protection"))
            return new DeathProtection(effects(fields, "death_effects", version, registries));
        int animation = 1;
        if (fields.contains("animation")) {
            String nameValue = string(fields.get("animation"));
            var names = java.util.List.of(EnumTypes.ITEM_USE_ANIMATION.names());
            animation = names.indexOf(nameValue);
            if (animation < 0 || (!version.atLeast(ProtocolVersion.V1_21_11) && animation >= 11))
                throw new ProtocolResolutionException("Unknown consume animation in " + version + ": " + nameValue);
        }
        return new Consumable1_21_2(
                floating(fields, "consume_seconds", 1.6F),
                animation,
                fields.contains("sound")
                        ? ComponentSimpleWrites.sound(fields.get("sound"), registries)
                        : Holder.<SoundEvent>of(id(registries, "sound_event", "minecraft:entity.generic.eat")),
                bool(fields, "has_consume_particles", true),
                effects(fields, "on_consume_effects", version, registries));
    }

    private static ConsumeEffect<?>[] effects(
            CompoundTag fields, String field, ProtocolVersion version, WireValueDecoder.Registries registries) {
        var list = fields.getListTag(field);
        if (list == null && fields.contains(field))
            throw new ProtocolResolutionException("Consume effects must be a list: " + field);
        var result = new ConsumeEffect<?>[list == null ? 0 : list.size()];
        for (int index = 0; index < result.length; index++)
            result[index] = effect(compound(element(list.get(index))), version, registries);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static ConsumeEffect<?> effect(
            CompoundTag fields, ProtocolVersion version, WireValueDecoder.Registries registries) {
        String name = Key.namespaced(string(fields.get("type")));
        var keys = EnumTypes.CONSUME_EFFECT.keys();
        int index = -1;
        for (int i = 0; i < keys.length; i++)
            if (keys[i].toString().equals(name)) {
                index = i;
                break;
            }
        if (index < 0) throw new ProtocolResolutionException("Unknown consume effect " + name);
        Object value = switch (index) {
            case 0 -> new ApplyStatusEffects(potions(fields, registries), floating(fields, "probability", 1F));
            case 1 -> ComponentSimpleWrites.holders(fields.get("effects"), "mob_effect", registries);
            case 2 -> Unit.INSTANCE;
            case 3 ->
                version.atLeast(ProtocolVersion.V26_3)
                        ? new TeleportRandomlyConsumeEffect(
                                floating(fields, "diameter", 16F), bool(fields, "directional_particles", true))
                        : floating(fields, "diameter", 16F);
            case 4 -> ComponentSimpleWrites.sound(fields.get("sound"), registries);
            default -> throw new ProtocolResolutionException("Unsupported consume effect " + name);
        };
        var types = version.atLeast(ProtocolVersion.V26_3)
                ? ConsumeEffect.EFFECT_TYPES26_3
                : ConsumeEffect.EFFECT_TYPES1_21_2;
        return new ConsumeEffect<>(index, (Type<Object>) types[index], value);
    }

    private static PotionEffect[] potions(CompoundTag fields, WireValueDecoder.Registries registries) {
        var list = fields.getListTag("effects");
        if (list == null) throw new ProtocolResolutionException("Apply consume effect needs its effects list");
        var result = new PotionEffect[list.size()];
        for (int index = 0; index < result.length; index++) {
            var value = compound(element(list.get(index)));
            result[index] =
                    new PotionEffect(id(registries, "mob_effect", string(value.get("id"))), effectData(value, true));
        }
        return result;
    }

    /** Persistent Details uses an unsigned byte; potion copies omit their hidden chain. */
    static PotionEffectData effectData(CompoundTag fields, boolean hidden) {
        boolean particles = bool(fields, "show_particles", true);
        return new PotionEffectData(
                integer(fields, "amplifier", 0) & 255,
                integer(fields, "duration", 0),
                bool(fields, "ambient", false),
                particles,
                bool(fields, "show_icon", particles),
                hidden && fields.contains("hidden_effect")
                        ? effectData(compound(fields.get("hidden_effect")), true)
                        : null);
    }

    /** MobEffectInstance's received constructor clamps every member of the hidden chain. */
    static PotionEffectData effectData(PotionEffectData value, boolean hidden) {
        return new PotionEffectData(
                Math.clamp(value.amplifier(), 0, 255),
                value.duration(),
                value.ambient(),
                value.showParticles(),
                value.showIcon(),
                hidden && value.hiddenEffect() != null ? effectData(value.hiddenEffect(), true) : null);
    }

    static PotionEffect[] potions(PotionEffect[] values, boolean hidden) {
        return java.util.Arrays.stream(values)
                .map(value -> new PotionEffect(value.effect(), effectData(value.effectData(), hidden)))
                .toArray(PotionEffect[]::new);
    }

    static ConsumeEffect<?>[] normalized(ConsumeEffect<?>[] values) {
        return java.util.Arrays.stream(values)
                .map(value -> value.value() instanceof ApplyStatusEffects applied
                        ? new ConsumeEffect<>(
                                value.id(),
                                ApplyStatusEffects.TYPE,
                                new ApplyStatusEffects(potions(applied.effects(), true), applied.probability()))
                        : value)
                .toArray(ConsumeEffect<?>[]::new);
    }

    private static float floating(CompoundTag fields, String field, float fallback) {
        return fields.contains(field) ? number(fields.get(field)).asFloat() : fallback;
    }
}
