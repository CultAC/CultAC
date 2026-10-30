package ac.cult.cultac.checks.info;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/** Check explanations from {@code check-info/en.yml} in the jar; servers cannot override them. */
public final class CheckTexts {
    private static final String RESOURCE = "/check-info/en.yml";

    public record Text(String summary, String details) {}

    private static volatile @Nullable CheckTexts loaded;

    private final Map<String, Text> checks;
    private final Map<CheckCategory, String> categories;

    CheckTexts(Map<String, Text> checks, Map<CheckCategory, String> categories) {
        this.checks = checks;
        this.categories = categories;
    }

    public static CheckTexts get() {
        CheckTexts texts = loaded;
        if (texts == null) loaded = texts = load();
        return texts;
    }

    static CheckTexts load() {
        try (InputStream stream = CheckTexts.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static CheckTexts parse(String yaml) {
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

    public @Nullable Text text(String name) {
        return checks.get(name);
    }

    public Set<String> names() {
        return checks.keySet();
    }

    public String label(CheckCategory category) {
        String label = categories.get(category);
        return label == null || label.isEmpty() ? category.id() : label;
    }
}
