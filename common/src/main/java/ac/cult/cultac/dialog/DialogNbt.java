package ac.cult.cultac.dialog;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.data.nbt.NbtValue.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * A {@link Dialog} as the NBT the client reads from {@code show_dialog}, the same from 1.21.6 on. Button {@code i}
 * clicks back with the id {@code clickPrefix + i} and the inputs' values as its payload; the exit button's index is one
 * past the last button. Text is encoded as text components: text, colour, decorations, hover text and children.
 */
final class DialogNbt {
    private DialogNbt() {}

    static NbtValue.Compound encode(Dialog dialog, String clickPrefix) {
        Map<String, NbtValue> nbt = new LinkedHashMap<>();
        nbt.put("type", text("minecraft:multi_action"));
        nbt.put("title", component(dialog.title()));
        nbt.put("body", list(dialog.body().stream().map(DialogNbt::body).toList()));
        nbt.put("inputs", list(dialog.inputs().stream().map(DialogNbt::input).toList()));
        nbt.put("can_close_with_escape", bool(true));
        nbt.put("pause", bool(false));
        nbt.put("after_action", text("none"));
        List<NbtValue> actions = new ArrayList<>();
        for (int i = 0; i < dialog.buttons().size(); i++) {
            actions.add(button(dialog.buttons().get(i), clickPrefix + i));
        }
        nbt.put("actions", list(actions));
        if (dialog.exit() != null) {
            nbt.put(
                    "exit_action",
                    button(dialog.exit(), clickPrefix + dialog.buttons().size()));
        }
        nbt.put("columns", integer(dialog.columns()));
        return new NbtValue.Compound(nbt);
    }

    private static NbtValue body(Dialog.Body body) {
        return switch (body) {
            case Dialog.Text text -> plainMessage(text);
            case Dialog.Head head ->
                compound(
                        "type", text("minecraft:item"),
                        "item", playerHead(head.player(), head.name()),
                        "description", plainMessage(fitted(head.description())),
                        "show_decorations", bool(false),
                        "show_tooltip", bool(false));
        };
    }

    /**
     * The client centres a head's text in a box as wide as its {@code width}, beside the head, so the box is fitted to
     * the text to keep the two together. Widths follow the default font: most glyphs advance 6 pixels, 7 when bold.
     */
    private static Dialog.Text fitted(Dialog.Text text) {
        List<Integer> lines = new ArrayList<>(List.of(0));
        measure(text.text(), false, lines);
        int widest = lines.stream().mapToInt(Integer::intValue).max().orElse(0);
        return new Dialog.Text(text.text(), Math.max(1, Math.min(text.width(), widest)));
    }

    private static void measure(Component component, boolean bold, List<Integer> lines) {
        TextDecoration.State state = component.decoration(TextDecoration.BOLD);
        boolean isBold = state == TextDecoration.State.NOT_SET ? bold : state == TextDecoration.State.TRUE;
        if (component instanceof TextComponent text) {
            text.content().codePoints().forEach(c -> {
                if (c == '\n') lines.add(0);
                else lines.set(lines.size() - 1, lines.getLast() + (isBold ? 7 : 6));
            });
        }
        for (Component child : component.children()) measure(child, isBold, lines);
    }

    private static NbtValue.Compound plainMessage(Dialog.Text text) {
        return compound(
                "type", text("minecraft:plain_message"),
                "contents", component(text.text()),
                "width", integer(text.width()));
    }

    private static NbtValue.Compound playerHead(UUID player, String name) {
        long most = player.getMostSignificantBits();
        long least = player.getLeastSignificantBits();
        NbtValue id = new NbtValue.PrimitiveArray(
                Kind.INT_ARRAY, List.of(most >> 32, most & 0xFFFFFFFFL, least >> 32, least & 0xFFFFFFFFL));
        return compound(
                "id", text("minecraft:player_head"),
                "count", integer(1),
                "components", compound("minecraft:profile", compound("id", id, "name", text(name))));
    }

    private static NbtValue input(Dialog.Input input) {
        return switch (input) {
            case Dialog.TextBox box ->
                compound(
                        "type", text("minecraft:text"),
                        "key", text(box.key()),
                        "label", component(box.label()),
                        "width", integer(box.width()),
                        "max_length", integer(box.maxLength()));
            case Dialog.Choice choice ->
                compound(
                        "type", text("minecraft:single_option"),
                        "key", text(choice.key()),
                        "label", component(choice.label()),
                        "width", integer(choice.width()),
                        "options",
                                list(choice.options().stream()
                                        .map(option -> (NbtValue) compound(
                                                "id", text(option.id()),
                                                "display", component(option.display()),
                                                "initial", bool(option.initial())))
                                        .toList()));
            case Dialog.Slider slider ->
                compound(
                        "type", text("minecraft:number_range"),
                        "key", text(slider.key()),
                        "label", component(slider.label()),
                        "label_format", text(slider.format()),
                        "width", integer(slider.width()),
                        "start", decimal(slider.min()),
                        "end", decimal(slider.max()),
                        "step", decimal(1),
                        "initial", decimal(slider.initial()));
        };
    }

    private static NbtValue.Compound button(Dialog.Button button, String clickId) {
        Map<String, NbtValue> nbt = new LinkedHashMap<>();
        nbt.put("label", component(button.label()));
        if (button.tooltip() != null) nbt.put("tooltip", component(button.tooltip()));
        nbt.put("width", integer(button.width()));
        nbt.put("action", compound("type", text("minecraft:dynamic/custom"), "id", text(clickId)));
        return new NbtValue.Compound(nbt);
    }

    /** A text component as NBT; any other kind of component is refused. */
    static NbtValue.Compound component(Component component) {
        if (!(component instanceof TextComponent text)) {
            throw new IllegalArgumentException("Dialogs draw text components only: " + component);
        }
        Map<String, NbtValue> nbt = new LinkedHashMap<>();
        nbt.put("text", text(text.content()));
        TextColor color = component.color();
        if (color != null) nbt.put("color", text(colorName(color)));
        for (TextDecoration decoration : TextDecoration.values()) {
            TextDecoration.State state = component.decoration(decoration);
            if (state != TextDecoration.State.NOT_SET)
                nbt.put(decoration.toString(), bool(state == TextDecoration.State.TRUE));
        }
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.value() instanceof Component shown) {
            nbt.put("hover_event", compound("action", text("show_text"), "value", component(shown)));
        }
        if (!component.children().isEmpty()) {
            nbt.put(
                    "extra",
                    list(component.children().stream()
                            .map(child -> (NbtValue) component(child))
                            .toList()));
        }
        return new NbtValue.Compound(nbt);
    }

    private static String colorName(TextColor color) {
        return color instanceof NamedTextColor named ? named.toString() : color.asHexString();
    }

    private static NbtValue.Compound compound(Object... keysAndValues) {
        Map<String, NbtValue> nbt = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            nbt.put((String) keysAndValues[i], (NbtValue) keysAndValues[i + 1]);
        }
        return new NbtValue.Compound(nbt);
    }

    private static NbtValue.Sequence list(List<NbtValue> values) {
        return new NbtValue.Sequence(values);
    }

    private static NbtValue.Text text(String value) {
        return new NbtValue.Text(value);
    }

    private static NbtValue.Numeric bool(boolean value) {
        return new NbtValue.Numeric(Kind.BYTE, (byte) (value ? 1 : 0));
    }

    private static NbtValue.Numeric integer(int value) {
        return new NbtValue.Numeric(Kind.INT, value);
    }

    private static NbtValue.Numeric decimal(float value) {
        return new NbtValue.Numeric(Kind.FLOAT, value);
    }
}
