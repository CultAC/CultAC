package ac.cult.cultac.checks.info;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/** The plain-language explanation of every check, shipped in the jar rather than copied to the data folder. */
public final class CheckTexts {
    public static final String RESOURCE = "/check-info/en.yml";

    /**
     * @param summary what the player did, short enough for a hover
     * @param details what kind of cheat or client behavior causes it
     */
    public record Text(@NotNull String summary, @NotNull String details) {}

    private static volatile @Nullable CheckTexts loaded;

    private final Map<String, Text> checks;
    private final Map<CheckCategory, String> categories;

    CheckTexts(Map<String, Text> checks, Map<CheckCategory, String> categories) {
        this.checks = checks;
        this.categories = categories;
    }

    public static @NotNull CheckTexts get() {
        CheckTexts texts = loaded;
        if (texts == null) loaded = texts = load();
        return texts;
    }

    static @NotNull CheckTexts load() {
        try (InputStream stream = CheckTexts.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static @NotNull CheckTexts parse(@NotNull String yaml) {
        Map<?, ?> root = new Yaml().load(yaml);
        Map<String, Text> checks = new HashMap<>();
        if (root.get("checks") instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> fields)) continue;
                checks.put(
                        String.valueOf(entry.getKey()),
                        new Text(string(fields.get("summary")), string(fields.get("details"))));
            }
        }
        Map<CheckCategory, String> categories = new EnumMap<>(CheckCategory.class);
        if (root.get("categories") instanceof Map<?, ?> labels) {
            for (Map.Entry<?, ?> entry : labels.entrySet()) {
                CheckCategory category = CheckCategory.byId(String.valueOf(entry.getKey()));
                if (category != null) categories.put(category, string(entry.getValue()));
            }
        }
        return new CheckTexts(Map.copyOf(checks), categories);
    }

    private static String string(@Nullable Object value) {
        return value == null ? "" : value.toString().strip();
    }

    /** The explanation of the check named {@code name}, or {@code null} when the file has none. */
    public @Nullable Text text(@NotNull String name) {
        return checks.get(name);
    }

    /** Every check name the file explains. */
    public @NotNull Set<String> names() {
        return checks.keySet();
    }

    public @NotNull String label(@NotNull CheckCategory category) {
        String label = categories.get(category);
        return label == null || label.isEmpty() ? category.id() : label;
    }
}
