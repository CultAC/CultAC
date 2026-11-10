package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.shaded.vialib.nbt.tag.ByteTag;
import ac.cult.shaded.vialib.nbt.tag.CompoundTag;
import ac.cult.shaded.vialib.nbt.tag.IntTag;
import ac.cult.shaded.vialib.nbt.tag.ListTag;
import ac.cult.shaded.vialib.nbt.tag.StringTag;
import ac.cult.shaded.vialib.nbt.tag.Tag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextValuesCopyTest {
    /** The text conversion as a deep copy of every compound, then the same field rewrites. */
    private static final class Reference {
        static Tag component(String name, Tag value) {
            return switch (name) {
                case "custom_name", "item_name" -> text(value);
                case "lore" -> texts((ListTag<?>) value);
                default -> value;
            };
        }

        private static Tag text(Tag value) {
            if (!(value instanceof CompoundTag object)) return value;
            var result = object.copy();
            result.remove("type");
            for (String field : new String[] {"extra", "with"})
                if (result.get(field) instanceof ListTag<?> list) result.put(field, texts(list));
            if (result.get("separator") != null) result.put("separator", text(result.get("separator")));
            if (result.get("hover_event") instanceof CompoundTag event) {
                var hover = event.copy();
                if (hover.get("action") instanceof StringTag action) {
                    String field = switch (action.getValue()) {
                        case "show_text" -> "value";
                        case "show_entity" -> "name";
                        default -> null;
                    };
                    if (field != null && hover.get(field) != null) hover.put(field, text(hover.get(field)));
                }
                result.put("hover_event", hover);
            }
            return result.getValue().size() == 1 && result.get("text") instanceof StringTag literal
                    ? literal.copy()
                    : result;
        }

        private static ListTag<Tag> texts(ListTag<?> list) {
            var values = new ArrayList<Tag>();
            for (var entry : list) {
                if (entry instanceof CompoundTag wrapper && wrapper.size() == 1 && wrapper.contains(""))
                    entry = wrapper.get("");
                values.add(text(entry));
            }
            if (!values.isEmpty()
                    && values.stream().anyMatch(value -> value.getTagId() != values.getFirst().getTagId())) {
                values.replaceAll(value -> {
                    if (value instanceof CompoundTag object && !(object.size() == 1 && object.contains("")))
                        return value;
                    var wrapper = new CompoundTag();
                    wrapper.put("", value);
                    return wrapper;
                });
            }
            return new ListTag<>(values);
        }
    }

    /** Type, ordered keys and leaf values, recursively. */
    private static String shape(Tag tag) {
        var out = new StringBuilder();
        shape(tag, out);
        return out.toString();
    }

    private static void shape(Tag tag, StringBuilder out) {
        out.append(tag.getClass().getSimpleName()).append(':');
        if (tag instanceof CompoundTag compound) {
            out.append('{');
            for (var entry : compound.getValue().entrySet()) {
                out.append(entry.getKey()).append('=');
                shape(entry.getValue(), out);
                out.append(',');
            }
            out.append('}');
        } else if (tag instanceof ListTag<?> list) {
            out.append('[');
            for (Tag child : list) {
                shape(child, out);
                out.append(',');
            }
            out.append(']');
        } else out.append(tag.getValue());
    }

    private static void tags(Tag tag, Set<Tag> into) {
        into.add(tag);
        if (tag instanceof CompoundTag compound) compound.getValue().values().forEach(child -> tags(child, into));
        else if (tag instanceof ListTag<?> list) for (Tag child : list) tags(child, into);
    }

    private static Tag random(Random random, int depth) {
        int pick = random.nextInt(depth > 3 ? 2 : 9);
        if (pick == 0) return new StringTag("plain" + random.nextInt(3));
        if (pick == 1) {
            var literal = new CompoundTag();
            literal.putString("text", "only");
            return literal;
        }
        var compound = new CompoundTag();
        var keys = new ArrayList<>(List.of(
                "text", "color", "bold", "italic", "type", "extra", "with", "separator", "hover_event", "font",
                "click_event", "translate", "insertion"));
        Collections.shuffle(keys, random);
        int count = 1 + random.nextInt(keys.size());
        for (String key : keys.subList(0, count)) {
            switch (key) {
                case "text", "translate", "font", "insertion" -> compound.putString(key, key + random.nextInt(4));
                case "color" -> compound.putString(key, random.nextBoolean() ? "gold" : "#00ff00");
                case "bold", "italic" -> compound.put(key, new ByteTag((byte) random.nextInt(2)));
                case "type" -> compound.putString(key, "text");
                case "extra", "with" -> {
                    if (random.nextInt(5) == 0) compound.putString(key, "not a list");
                    else {
                        var values = new ArrayList<Tag>();
                        boolean strings = random.nextBoolean();
                        for (int index = random.nextInt(4); index >= 0; index--) {
                            Tag child = strings && random.nextBoolean()
                                    ? new StringTag("s" + index)
                                    : random(random, depth + 1);
                            if (random.nextInt(4) == 0) {
                                var wrapper = new CompoundTag();
                                wrapper.put("", child);
                                child = wrapper;
                            }
                            values.add(child);
                        }
                        if (values.stream().allMatch(value -> value.getTagId() == values.getFirst().getTagId()))
                            compound.put(key, new ListTag<>(values));
                        else {
                            var wrapped = new ArrayList<Tag>();
                            for (Tag value : values) {
                                if (value instanceof CompoundTag) wrapped.add(value);
                                else {
                                    var wrapper = new CompoundTag();
                                    wrapper.put("", value);
                                    wrapped.add(wrapper);
                                }
                            }
                            compound.put(key, new ListTag<>(wrapped));
                        }
                    }
                }
                case "separator" -> compound.put(key, random(random, depth + 1));
                case "hover_event" -> {
                    if (random.nextInt(6) == 0) compound.putString(key, "flat");
                    else {
                        var hover = new CompoundTag();
                        String[] actions = {"show_text", "show_entity", "show_item"};
                        int action = random.nextInt(4);
                        if (action < 3) hover.putString("action", actions[action]);
                        else hover.put("action", new IntTag(3));
                        if (random.nextBoolean()) hover.put("value", random(random, depth + 1));
                        if (random.nextBoolean()) hover.put("name", random(random, depth + 1));
                        if (random.nextBoolean()) hover.put("id", new StringTag("minecraft:pig"));
                        compound.put(key, hover);
                    }
                }
                default -> {
                    var event = new CompoundTag();
                    event.putString("action", "run_command");
                    event.putString("command", "/menu");
                    compound.put(key, event);
                }
            }
        }
        return compound;
    }

    @Test
    void textsMatchTheDeepCopyConversionAndShareNothingNew() {
        var random = new Random(5);
        for (int round = 0; round < 20_000; round++) {
            String name = switch (round % 3) {
                case 0 -> "custom_name";
                case 1 -> "item_name";
                default -> "lore";
            };
            Tag value;
            if (name.equals("lore")) {
                var lines = new ArrayList<Tag>();
                boolean strings = random.nextInt(3) == 0;
                for (int line = random.nextInt(6); line >= 0; line--)
                    lines.add(strings ? new StringTag("line" + line) : random(random, 0));
                if (lines.stream().allMatch(line -> line.getTagId() == lines.getFirst().getTagId()))
                    value = new ListTag<>(lines);
                else {
                    var wrapped = new ArrayList<Tag>();
                    for (Tag line : lines) {
                        var wrapper = new CompoundTag();
                        wrapper.put("", line);
                        wrapped.add(line instanceof CompoundTag ? line : wrapper);
                    }
                    value = new ListTag<>(wrapped);
                }
            } else value = random(random, 0);
            String before = shape(value);
            var expected = Reference.component(name, value);
            assertEquals(before, shape(value));
            var actual = TextValues.component(name, value);
            assertEquals(before, shape(value));
            assertEquals(shape(expected), shape(actual));
            assertEquals(expected, actual);
            // Only tags the deep-copy conversion also passes through uncopied may be shared with the input.
            Set<Tag> input = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Tag> sharedBefore = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Tag> sharedAfter = Collections.newSetFromMap(new IdentityHashMap<>());
            tags(value, input);
            Set<Tag> expectedTags = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Tag> actualTags = Collections.newSetFromMap(new IdentityHashMap<>());
            tags(expected, expectedTags);
            tags(actual, actualTags);
            for (Tag tag : expectedTags) if (input.contains(tag)) sharedBefore.add(tag);
            for (Tag tag : actualTags) if (input.contains(tag)) sharedAfter.add(tag);
            assertTrue(sharedBefore.containsAll(sharedAfter), "new sharing with the input");
            assertEquals(sharedBefore.size(), sharedAfter.size());
        }
    }
}
