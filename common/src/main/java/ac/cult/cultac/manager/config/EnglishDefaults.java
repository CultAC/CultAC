package ac.cult.cultac.manager.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

/**
 * The values the jar ships in a config file's English default, for keys that both the server's file and its
 * language's default lack.
 */
public final class EnglishDefaults {
    private static final Map<String, Map<?, ?>> FILES = new ConcurrentHashMap<>();

    private EnglishDefaults() {}

    /** The shipped English value of {@code key} in {@code /<directory>/en.yml}. */
    public static @NotNull String string(@NotNull String directory, @NotNull String key) {
        return value(directory, key).toString();
    }

    /** The shipped English list at {@code key} in {@code /<directory>/en.yml}. */
    public static @NotNull List<String> stringList(@NotNull String directory, @NotNull String key) {
        if (!(value(directory, key) instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " in /" + directory + "/en.yml is not a list");
        }
        List<String> lines = new ArrayList<>();
        for (Object line : list) lines.add(String.valueOf(line));
        return List.copyOf(lines);
    }

    private static Object value(String directory, String key) {
        Object value = FILES.computeIfAbsent(directory, EnglishDefaults::load).get(key);
        if (value == null) throw new IllegalArgumentException("/" + directory + "/en.yml has no " + key);
        return value;
    }

    private static Map<?, ?> load(String directory) {
        String resource = "/" + directory + "/en.yml";
        try (InputStream stream = EnglishDefaults.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException(resource + " is missing from the jar");
            Object root = new Yaml().load(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return root instanceof Map<?, ?> map ? map : Map.of();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
