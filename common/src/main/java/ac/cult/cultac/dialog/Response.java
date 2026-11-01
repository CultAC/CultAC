package ac.cult.cultac.dialog;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The inputs of a dialog as the viewer left them when they clicked, read by {@link Dialog.Input#key()}: a text box's
 * text and a choice's option id as strings, a slider's value as a number.
 */
public final class Response {
    static final Response EMPTY = new Response(Map.of());

    private final Map<String, ?> values;

    public Response(Map<String, ?> values) {
        this.values = Map.copyOf(values);
    }

    public @Nullable String text(String key) {
        return values.get(key) instanceof String text ? text : null;
    }

    public @Nullable Float number(String key) {
        return values.get(key) instanceof Number number ? number.floatValue() : null;
    }
}
