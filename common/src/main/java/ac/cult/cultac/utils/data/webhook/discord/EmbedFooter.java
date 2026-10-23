package ac.cult.cultac.utils.data.webhook.discord;

import ac.cult.cultac.utils.data.json.JsonSerializable;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Getter
@Setter
@Accessors(fluent = true)
public class EmbedFooter implements JsonSerializable {
    public static final int MAX_TEXT_LENGTH = 2048;

    private @NotNull String text;
    private @Nullable String icon;

    public EmbedFooter(@NotNull String text) {
        this(text, null);
    }

    public EmbedFooter(@NotNull String text, @Nullable String icon) {
        text(text);
        icon(icon);
    }

    @Contract(value = "_ -> this", mutates = "this")
    public @NotNull EmbedFooter text(@NotNull String text) {
        Objects.requireNonNull(text, "Embed footer text cannot be null!");
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Embed footer text too long, " + text.length() + " > " + MAX_TEXT_LENGTH);
        }

        this.text = text;
        return this;
    }

    @Override
    public @NotNull JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("text", text());
        if (icon() != null) json.addProperty("icon_url", icon());
        return json;
    }
}
