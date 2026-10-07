package ac.cult.cultac.codec;

import static ac.cult.cultac.codec.ComponentWrites.*;

import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.shaded.vialib.api.minecraft.HolderSet;
import ac.cult.shaded.vialib.api.minecraft.item.data.*;
import ac.cult.shaded.vialib.nbt.tag.*;
import ac.cult.shaded.vialib.util.Key;

/** Portable values for the tool, food, cooldown and book records consumed by the engine. */
final class ComponentRecordWrites {
    private ComponentRecordWrites() {}

    static boolean supports(String name) {
        return switch (name) {
            case "enchantments", "stored_enchantments", "food", "tool", "use_cooldown", "writable_book_content" -> true;
            default -> false;
        };
    }

    static Object value(String name, Tag tag, WireValueDecoder.Registries registries) {
        var value = compound(tag);
        return switch (name) {
            case "enchantments", "stored_enchantments" -> enchantments(value, registries);
            case "food" ->
                new FoodProperties1_21_2(
                        number(value.get("nutrition")).asInt(),
                        number(value.get("saturation")).asFloat(),
                        bool(value, "can_always_eat", false));
            case "tool" -> tool(value, registries);
            case "use_cooldown" ->
                new UseCooldown(
                        number(value.get("seconds")).asFloat(),
                        value.contains("cooldown_group") ? Key.namespaced(string(value.get("cooldown_group"))) : null);
            case "writable_book_content" -> book(value);
            default -> throw new ProtocolResolutionException("Component needs a matching wire encoding: " + name);
        };
    }

    private static Enchantments enchantments(CompoundTag value, WireValueDecoder.Registries registries) {
        var levels = value.getCompoundTag("levels");
        var result = new Enchantments(levels == null || bool(value, "show_in_tooltip", true));
        (levels == null ? value : levels)
                .getValue()
                .forEach((name, level) -> result.add(
                        id(registries, "enchantment", name), number(level).asInt()));
        return result;
    }

    private static ToolProperties tool(CompoundTag value, WireValueDecoder.Registries registries) {
        var rules = value.getListTag("rules");
        if (rules == null) throw new ProtocolResolutionException("Tool needs its rules list");
        var result = new ToolRule[rules.size()];
        for (int index = 0; index < result.length; index++) {
            var rule = compound(element(rules.get(index)));
            Tag blocks = rule.get("blocks");
            HolderSet holders =
                    blocks instanceof StringTag text && text.getValue().startsWith("#")
                            ? HolderSet.of(Key.namespaced(text.getValue().substring(1)))
                            : HolderSet.fromTag(blocks, name -> id(registries, "block", name));
            result[index] = new ToolRule(
                    holders,
                    rule.contains("speed") ? number(rule.get("speed")).asFloat() : null,
                    rule.contains("correct_for_drops") ? bool(rule, "correct_for_drops", false) : null);
        }
        return new ToolProperties(
                result,
                value.contains("default_mining_speed")
                        ? number(value.get("default_mining_speed")).asFloat()
                        : 1F,
                integer(value, "damage_per_block", 1),
                bool(value, "can_destroy_blocks_in_creative", true));
    }

    private static WritableBook book(CompoundTag value) {
        var pages = value.getListTag("pages");
        var result = new FilterableString[pages == null ? 0 : pages.size()];
        for (int index = 0; index < result.length; index++) {
            Tag page = element(pages.get(index));
            if (page instanceof StringTag text) result[index] = new FilterableString(text.getValue(), null);
            else {
                var fields = compound(page);
                result[index] = new FilterableString(
                        string(fields.get("raw")), fields.contains("filtered") ? string(fields.get("filtered")) : null);
            }
        }
        return new WritableBook(result);
    }
}
