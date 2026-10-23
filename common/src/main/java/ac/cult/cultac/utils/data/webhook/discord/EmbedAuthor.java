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
public class EmbedAuthor implements JsonSerializable {
    public static final int MAX_NAME_LENGTH = 256;

    private @NotNull String name;
    private @Nullable String url;
    private @Nullable String icon;

    public EmbedAuthor(@NotNull String name) {
        this(name, null, null);
    }

    public EmbedAuthor(@NotNull String name, @Nullable String url, @Nullable String icon) {
        name(name);
        url(url);
        icon(icon);
    }

    @Contract(value = "_ -> this", mutates = "this")
    public @NotNull EmbedAuthor name(@NotNull String name) {
        Objects.requireNonNull(name, "Embed author name cannot be null!");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Embed author name too long, " + name.length() + " > " + MAX_NAME_LENGTH);
        }

        this.name = name;
        return this;
    }

    @Override
    public @NotNull JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name());
        if (url() != null) json.addProperty("url", url());
        if (icon() != null) json.addProperty("icon_url", icon());
        return json;
    }
}
