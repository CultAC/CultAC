package ac.cult.cultac.dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A screen the client draws over the game: text, then inputs, then a grid of buttons, plus an exit button that Escape
 * also presses. It is vanilla's multi-action dialog, so it needs no resource pack. Build one with {@link #titled} and
 * show it with {@link Dialogs#show}; a button's handler runs on the viewer's thread with the inputs as the viewer left
 * them.
 */
public record Dialog(
        Component title,
        List<Body> body,
        List<Input> inputs,
        List<Button> buttons,
        @Nullable Button exit,
        int columns) {
    /** The client's default width for text, inputs and the exit button. */
    public static final int WIDE = 200;

    public Dialog {
        Objects.requireNonNull(title);
        body = List.copyOf(body);
        inputs = List.copyOf(inputs);
        buttons = List.copyOf(buttons);
        if (buttons.isEmpty()) throw new IllegalArgumentException("A dialog needs a button");
        if (columns < 1) throw new IllegalArgumentException("columns: " + columns);
    }

    public static Builder titled(Component title) {
        return new Builder(title);
    }

    public sealed interface Body permits Text, Head {}

    /** A block of text, wrapped to {@code width} pixels. */
    public record Text(Component text, int width) implements Body {}

    /** A player's head with text beside it. */
    public record Head(UUID player, String name, Text description) implements Body {}

    public sealed interface Input permits TextBox, Choice, Slider {
        /** The name the input's value is read back under in {@link Response}. */
        String key();
    }

    /** A one-line text field, empty at first. */
    public record TextBox(String key, Component label, int width, int maxLength) implements Input {}

    /** A button that cycles through {@code options}, starting at the one marked initial. */
    public record Choice(String key, Component label, int width, List<Option> options) implements Input {
        public Choice {
            options = List.copyOf(options);
        }
    }

    public record Option(String id, Component display, boolean initial) {}

    /**
     * A slider over whole numbers from {@code min} to {@code max}. The client writes its label from {@code format}, a
     * translation key it does not know, so it shows the key itself with the label and then the value for each
     * {@code %s}.
     */
    public record Slider(String key, Component label, String format, int width, int min, int max, int initial)
            implements Input {}

    public record Button(Component label, @Nullable Component tooltip, int width, Consumer<Response> onClick) {}

    public static final class Builder {
        private final Component title;
        private final List<Body> body = new ArrayList<>();
        private final List<Input> inputs = new ArrayList<>();
        private final List<Button> buttons = new ArrayList<>();
        private @Nullable Button exit;
        private int columns = 1;

        private Builder(Component title) {
            this.title = title;
        }

        public Builder text(Component text) {
            return text(text, WIDE);
        }

        public Builder text(Component text, int width) {
            body.add(new Text(text, width));
            return this;
        }

        public Builder head(UUID player, String name, Component description, int width) {
            body.add(new Head(player, name, new Text(description, width)));
            return this;
        }

        public Builder input(Input input) {
            inputs.add(input);
            return this;
        }

        public Builder button(Component label, int width, Consumer<Response> onClick) {
            return button(label, null, width, onClick);
        }

        public Builder button(Component label, @Nullable Component tooltip, int width, Consumer<Response> onClick) {
            buttons.add(new Button(label, tooltip, width, onClick));
            return this;
        }

        /** The button under the grid, which Escape presses too. */
        public Builder exit(Component label, int width, Consumer<Response> onClick) {
            exit = new Button(label, null, width, onClick);
            return this;
        }

        public Builder columns(int columns) {
            this.columns = columns;
            return this;
        }

        public Dialog build() {
            return new Dialog(title, body, inputs, buttons, exit, columns);
        }
    }
}
