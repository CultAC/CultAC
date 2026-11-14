package ac.cult.cultac.codec;

import ac.cult.shaded.vialib.nbt.tag.CompoundTag;
import ac.cult.shaded.vialib.nbt.tag.ListTag;
import ac.cult.shaded.vialib.nbt.tag.StringTag;
import ac.cult.shaded.vialib.nbt.tag.Tag;

/** Client text codec's untyped encoding; original wire bytes remain in ComponentEncoding. */
final class TextValues {
    private TextValues() {}

    static Tag component(String name, Tag value) {
        return switch (name) {
            case "custom_name", "item_name" -> text(value);
            case "lore" -> texts((ListTag<?>) value);
            default -> value;
        };
    }

    private static Tag text(Tag value) {
        return value instanceof CompoundTag ? copiedText(value) : value;
    }

    /** {@link #text} of a copy of {@code value}: a tree that shares no tag with {@code value}. */
    private static Tag copiedText(Tag value) {
        if (!(value instanceof CompoundTag object)) return value.copy();
        var result = new CompoundTag();
        for (var entry : object.getValue().entrySet()) {
            String key = entry.getKey();
            Tag field = entry.getValue();
            switch (key) {
                // ComponentSerialization.StrictEither.encode selects the content's fuzzy codec.
                // Its input discriminator is absent from the resulting client component value.
                case "type" -> {}
                case "extra", "with" -> result.put(key, field instanceof ListTag<?> list ? texts(list, true) : field.copy());
                case "separator" -> result.put(key, copiedText(field));
                case "hover_event" -> result.put(key, field instanceof CompoundTag event ? hover(event.copy()) : field.copy());
                default -> result.put(key, field.copy());
            }
        }
        // Component.tryCollapseToString: plain content without siblings or style.
        return result.getValue().size() == 1 && result.get("text") instanceof StringTag literal
                ? literal.copy()
                : result;
    }

    private static CompoundTag hover(CompoundTag hover) {
        if (hover.get("action") instanceof StringTag action) {
            String field = switch (action.getValue()) {
                case "show_text" -> "value";
                case "show_entity" -> "name";
                default -> null;
            };
            if (field != null && hover.get(field) != null) hover.put(field, text(hover.get(field)));
        }
        return hover;
    }

    private static ListTag<Tag> texts(ListTag<?> list) {
        return texts(list, false);
    }

    /** With {@code copied}, as {@link #texts(ListTag)} of a copy of {@code list}. */
    private static ListTag<Tag> texts(ListTag<?> list, boolean copied) {
        var values = new java.util.ArrayList<Tag>();
        for (var entry : list) {
            // Modern ListTag unwraps heterogeneous entries before the text codec reads them.
            if (entry instanceof CompoundTag wrapper && wrapper.size() == 1 && wrapper.contains(""))
                entry = wrapper.get("");
            values.add(copied ? copiedText(entry) : text(entry));
        }
        // Via's NBT lists are homogeneous. Retain the client's compound wrappers when
        // collapsing plain text produces a mixture of strings and styled objects.
        if (!values.isEmpty()
                && values.stream()
                        .anyMatch(value -> value.getTagId() != values.getFirst().getTagId())) {
            values.replaceAll(value -> {
                if (value instanceof CompoundTag object && !(object.size() == 1 && object.contains(""))) return value;
                var wrapper = new CompoundTag();
                wrapper.put("", value);
                return wrapper;
            });
        }
        return new ListTag<>(values);
    }
}
