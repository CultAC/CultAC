package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed views of the component fields consumed by inventory, movement and item actions. */
public final class ItemComponents {
    private ItemComponents() {}

    public record HolderSet(String tag, List<String> entries) {
        public HolderSet { entries = List.copyOf(entries); }
        public boolean contains(DataTables data, String registry, String key) {
            return tag == null ? entries.contains(key)
                    : data.tags().getOrDefault(registry + ":" + tag, java.util.Set.of()).contains(key);
        }
    }
    public record ToolRule(HolderSet blocks, Float speed, Boolean correctForDrops) {}
    public record Tool(List<ToolRule> rules, float defaultMiningSpeed, int damagePerBlock,
                       boolean canDestroyBlocksInCreative) {
        public Tool { rules = List.copyOf(rules); }
    }
    public record Equippable(String slot, HolderSet allowedEntities, boolean swappable) {}
    public record Food(int nutrition, float saturation, boolean canAlwaysEat) {}
    public record Consumable(float consumeSeconds, String animation) {
        public int ticks() { return (int)(consumeSeconds * 20.0F); }
    }
    public record UseCooldown(float seconds, String group) {
        public int ticks() { return (int)(seconds * 20.0F); }
    }
    public record BookPage(String raw, String filtered) {}

    public static Tool tool(Components components) {
        var value = object(components, "minecraft:tool");
        if (value == null) return null;
        var rules = new ArrayList<ToolRule>();
        for (var entry : value.getAsJsonArray("rules")) {
            var rule = entry.getAsJsonObject();
            rules.add(new ToolRule(holders(rule.get("blocks")), rule.has("speed") ? rule.get("speed").getAsFloat() : null,
                    rule.has("correct_for_drops") ? NbtJson.booleanValue(rule.get("correct_for_drops")) : null));
        }
        return new Tool(rules, number(value, "default_mining_speed", 1.0F),
                value.has("damage_per_block") ? value.get("damage_per_block").getAsInt() : 1,
                flag(value, "can_destroy_blocks_in_creative", true));
    }

    public static Equippable equippable(Components components) {
        var value = object(components, "minecraft:equippable");
        return value == null ? null : new Equippable(value.get("slot").getAsString(),
                value.has("allowed_entities") ? holders(value.get("allowed_entities")) : null,
                flag(value, "swappable", true));
    }

    public static Food food(Components components) {
        var value = object(components, "minecraft:food");
        return value == null ? null : new Food(value.get("nutrition").getAsInt(), value.get("saturation").getAsFloat(),
                flag(value, "can_always_eat", false));
    }

    public static Consumable consumable(Components components) {
        var value = object(components, "minecraft:consumable");
        return value == null ? null : new Consumable(number(value, "consume_seconds", 1.6F),
                value.has("animation") ? value.get("animation").getAsString() : "eat");
    }

    public static UseCooldown useCooldown(Components components) {
        var value = object(components, "minecraft:use_cooldown");
        return value == null ? null : new UseCooldown(value.get("seconds").getAsFloat(),
                value.has("cooldown_group") ? value.get("cooldown_group").getAsString() : null);
    }

    public static Map<String, Integer> enchantments(Components components) {
        var value = object(components, "minecraft:enchantments");
        if (value == null) return Map.of();
        var levels = new LinkedHashMap<String, Integer>();
        value.entrySet().forEach(entry -> levels.put(HolderSets.identifier(entry.getKey()), entry.getValue().getAsInt()));
        return java.util.Collections.unmodifiableMap(levels);
    }

    public static List<BookPage> writableBook(Components components) {
        var value = object(components, "minecraft:writable_book_content");
        if (value == null) return null;
        var pages = new ArrayList<BookPage>();
        if (value.has("pages")) for (var page : value.getAsJsonArray("pages")) {
            if (page.isJsonPrimitive()) pages.add(new BookPage(page.getAsString(), null));
            else {
                var fields = page.getAsJsonObject();
                pages.add(new BookPage(fields.get("raw").getAsString(),
                        fields.has("filtered") ? fields.get("filtered").getAsString() : null));
            }
        }
        return List.copyOf(pages);
    }

    public static int damage(Components components) { return components.integer("minecraft:damage", 0); }
    public static int maxDamage(Components components) { return components.integer("minecraft:max_damage", 0); }
    public static boolean glider(Components components) { return components.has("minecraft:glider"); }
    public static boolean creativeSlotLock(Components components) { return components.has("minecraft:creative_slot_lock"); }
    public static String blockTransformer(Components components) { return reference(components, "minecraft:block_transformer"); }
    public static String mapPostProcessing(Components components) { return reference(components, "minecraft:map_post_processing"); }
    public static NbtValue.Compound customData(Components components) { return (NbtValue.Compound) components.encodedNbt("minecraft:custom_data"); }
    public static List<ItemTemplate> bundleContents(Components components) {
        return components.bundle() == null ? null : components.bundle().items();
    }
    public static ItemTemplate useRemainder(Components components) {
        String key = "minecraft:use_remainder";
        if (!components.has(key)) return null;
        return components.hasEncodedNbt(key)
                ? ItemTemplate.fromNbt(components.encodedNbt(key), ComponentLayout.child(components, key, "0"))
                : ItemTemplate.fromJson(components.get(key));
    }
    public static List<NbtValue.Compound> canBreak(Components components) { return predicates(components, "minecraft:can_break"); }
    public static List<NbtValue.Compound> canPlaceOn(Components components) { return predicates(components, "minecraft:can_place_on"); }

    public static boolean canGlide(SimItemStack stack, String slot) {
        var equipment = equippable(stack.components());
        return canGlide(glider(stack.components()), stack.isDamageable(), stack.damage(), stack.maxDamage(),
                equipment == null ? null : equipment.slot(), slot);
    }
    /** Primitive boundary for the remaining native inventory adapter. */
    public static boolean canGlide(boolean glider, boolean damageable, int damage, int maxDamage, String equipment, String slot) {
        return glider && (!damageable || damage < maxDamage - 1) && slot.equals(equipment);
    }

    private static HolderSet holders(JsonElement value) {
        if (value.isJsonArray()) {
            var keys = new ArrayList<String>();
            value.getAsJsonArray().forEach(entry -> keys.add(HolderSets.identifier(entry.getAsString())));
            return new HolderSet(null, keys);
        }
        String key = value.getAsString();
        return key.startsWith("#") ? new HolderSet(HolderSets.identifier(key.substring(1)), List.of())
                : new HolderSet(null, List.of(HolderSets.identifier(key)));
    }
    static List<ItemTemplate> itemTemplates(Components components, String key) {
        if (!components.has(key)) return null;
        var result = new ArrayList<ItemTemplate>();
        if (components.hasEncodedNbt(key)) {
            var values = ((NbtValue.Sequence)components.encodedNbt(key)).values();
            for (int i = 0; i < values.size(); i++)
                result.add(ItemTemplate.fromNbt(values.get(i), ComponentLayout.child(components, key, Integer.toString(i))));
            return List.copyOf(result);
        }
        components.get(key).getAsJsonArray().forEach(value -> result.add(ItemTemplate.fromJson(value)));
        return List.copyOf(result);
    }
    private static List<NbtValue.Compound> predicates(Components components, String key) {
        var value = components.encodedNbt(key);
        if (value == null) return null;
        if (value instanceof NbtValue.Compound predicate) return List.of(predicate);
        return ((NbtValue.Sequence)value).values().stream().map(entry -> (NbtValue.Compound)entry).toList();
    }
    private static JsonObject object(Components components, String key) {
        var value = components.get(key);
        return value == null ? null : value.getAsJsonObject();
    }
    private static String reference(Components components, String key) {
        var value = components.get(key);
        return value == null ? null : value.getAsString();
    }
    private static boolean flag(JsonObject value, String key, boolean fallback) {
        return value.has(key) ? NbtJson.booleanValue(value.get(key)) : fallback;
    }
    private static float number(JsonObject value, String key, float fallback) {
        return value.has(key) ? value.get(key).getAsFloat() : fallback;
    }
}
