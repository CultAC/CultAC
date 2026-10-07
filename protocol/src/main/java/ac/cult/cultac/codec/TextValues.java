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
        if (!(value instanceof CompoundTag object)) return value;
        var result = object.copy();
        // ComponentSerialization.StrictEither.encode selects the content's fuzzy codec.
        // Its input discriminator is absent from the resulting client component value.
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
        // Component.tryCollapseToString: plain content without siblings or style.
        return result.getValue().size() == 1 && result.get("text") instanceof StringTag literal
                ? literal.copy()
                : result;
    }

    private static ListTag<Tag> texts(ListTag<?> list) {
        var values = new java.util.ArrayList<Tag>();
        for (var entry : list) {
            // Modern ListTag unwraps heterogeneous entries before the text codec reads them.
            if (entry instanceof CompoundTag wrapper && wrapper.size() == 1 && wrapper.contains(""))
                entry = wrapper.get("");
            values.add(text(entry));
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
