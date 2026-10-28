package ac.cult.cultac.cloud.labels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The cheats a label may name, as the cloud service serves them: the pickable ones in picker order, {@code other} among
 * them, and the legacy ones that only appear on stored labels. The plugin ships a copy and uses it until the service
 * answers.
 */
public final class LabelCatalog {
    public static final String OTHER = "other";
    static final String RESOURCE = "/labels/catalog.json";

    private static final AtomicReference<LabelCatalog> CURRENT = new AtomicReference<>(bundled());

    /** One cheat: its wire id and its display name. */
    public record Entry(String id, String name) {}

    private final String version;
    private final List<Entry> cheats;
    private final List<String> ids;
    private final Map<String, String> pickable;
    private final Map<String, String> legacy;

    private LabelCatalog(String version, Map<String, String> pickable, Map<String, String> legacy) {
        this.version = version;
        this.pickable = Collections.unmodifiableMap(pickable);
        this.legacy = Collections.unmodifiableMap(legacy);
        this.ids = List.copyOf(pickable.keySet());
        this.cheats = pickable.entrySet().stream()
                .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** The catalog in use: the bundled one until the service answers with a valid one. */
    public static LabelCatalog current() {
        return CURRENT.get();
    }

    /** Makes {@code catalog} the one in use. */
    public static void use(LabelCatalog catalog) {
        CURRENT.set(catalog);
    }

    /** The copy shipped in the plugin. */
    public static LabelCatalog bundled() {
        try (InputStream in = LabelCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The catalog in a service answer; unknown fields are ignored.
     *
     * @throws IllegalArgumentException when it names no pickable cheat, or not {@code other}
     */
    public static LabelCatalog parse(JsonObject body) {
        JsonElement version = body.get("version");
        Map<String, String> pickable = entries(body.get("cheats"));
        if (pickable.isEmpty()) throw new IllegalArgumentException("the catalog names no cheat");
        if (!pickable.containsKey(OTHER)) throw new IllegalArgumentException("the catalog has no " + OTHER);
        Map<String, String> legacy = entries(body.get("legacy"));
        legacy.keySet().removeAll(pickable.keySet());
        return new LabelCatalog(
                version != null && version.isJsonPrimitive() ? version.getAsString() : "", pickable, legacy);
    }

    private static Map<String, String> entries(JsonElement list) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (list == null || !list.isJsonArray()) return entries;
        JsonArray array = list.getAsJsonArray();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) continue;
            JsonElement id = item.getAsJsonObject().get("id");
            JsonElement name = item.getAsJsonObject().get("name");
            if (id == null || !id.isJsonPrimitive() || id.getAsString().isBlank()) continue;
            String key = id.getAsString().strip().toLowerCase(Locale.ROOT);
            String display = name != null
                            && name.isJsonPrimitive()
                            && !name.getAsString().isBlank()
                    ? name.getAsString().strip()
                    : key;
            entries.putIfAbsent(key, display);
        }
        return entries;
    }

    public String version() {
        return version;
    }

    /** The pickable cheats in picker order. */
    public List<Entry> cheats() {
        return cheats;
    }

    /** The pickable cheats' ids in picker order. */
    public List<String> ids() {
        return ids;
    }

    /** Whether a new label may name {@code id}. */
    public boolean pickable(String id) {
        return pickable.containsKey(id);
    }

    /** The display name of a pickable or legacy cheat, else {@code id} itself. */
    public String name(String id) {
        String name = pickable.get(id);
        if (name != null) return name;
        return legacy.getOrDefault(id, id);
    }
}
