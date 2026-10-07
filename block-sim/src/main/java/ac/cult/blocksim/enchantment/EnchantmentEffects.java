package ac.cult.blocksim.enchantment;

import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;

/** Immutable per-connection effects from the received enchantment registry. */
public final class EnchantmentEffects {
    public static final String PREVENT_ARMOR_CHANGE = "minecraft:prevent_armor_change";
    public static final String TRIDENT_SPIN_ATTACK_STRENGTH = "minecraft:trident_spin_attack_strength";
    public static final String CROSSBOW_CHARGE_TIME = "minecraft:crossbow_charge_time";
    private final InteractionRegistries registries;

    public EnchantmentEffects(InteractionRegistries registries) {
        this.registries = java.util.Objects.requireNonNull(registries);
    }

    /** Presence is independent of level, including a stream-decoded zero level. */
    public boolean has(SimItemStack stack, String component) {
        component = name(component);
        for (String key : ItemComponents.enchantments(stack.components()).keySet()) {
            var effects = effects(key);
            if (effects != null) for (String effect : effects.keySet()) {
                if (name(effect).equals(component)) return true;
            }
        }
        return false;
    }

    /** Process one enchantment; the item caller owns iteration order and the random stream. */
    public float modify(String enchantment, String component, int level, EffectRandom random, float input) {
        component = name(component);
        if (!component.equals(TRIDENT_SPIN_ATTACK_STRENGTH) && !component.equals(CROSSBOW_CHARGE_TIME))
            throw new IllegalArgumentException("Not an unfiltered value component " + component);
        var effects = effects(enchantment);
        com.google.gson.JsonElement value = null;
        if (effects != null) for (var entry : effects.entrySet()) {
            if (name(entry.getKey()).equals(component)) value = entry.getValue();
        }
        return value == null ? input : ValueEffect.read(value).apply(level, random, input);
    }

    /** An unused enchantment never builds an effect table or decodes its wire definition. */
    private com.google.gson.JsonObject effects(String key) {
        return registries.resolve("enchantment", new com.google.gson.JsonPrimitive(name(key)))
            .getAsJsonObject().getAsJsonObject("effects");
    }

    private static String name(String key) { return key.contains(":") ? key : "minecraft:" + key; }
}
