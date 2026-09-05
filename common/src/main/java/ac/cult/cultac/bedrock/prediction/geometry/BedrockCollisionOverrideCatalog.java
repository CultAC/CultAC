package ac.cult.cultac.bedrock.prediction.geometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.GZIPInputStream;

public final class BedrockCollisionOverrideCatalog {
    public static final String SCHEMA = "cultac-bedrock-collision-overrides/v1";
    private static final String BUNDLED_RESOURCE = "bedrock/cultac-bedrock-collision-overrides.json.gz";
    private static volatile BedrockCollisionOverrideCatalog bundled;

    private final JsonObject source;
    private final JsonObject summary;
    private final List<BedrockCollisionOverrideShape> shapes;
    private final Map<Integer, BedrockCollisionOverrideShape> byJavaStateId;
    private final Map<Integer, Long> blockPropertyMasksByJavaStateId;

    private BedrockCollisionOverrideCatalog(
            JsonObject source,
            JsonObject summary,
            List<BedrockCollisionOverrideShape> shapes,
            Map<Integer, BedrockCollisionOverrideShape> byJavaStateId,
            Map<Integer, Long> blockPropertyMasksByJavaStateId
    ) {
        this.source = source == null ? new JsonObject() : source.deepCopy();
        this.summary = summary == null ? new JsonObject() : summary.deepCopy();
        this.shapes = List.copyOf(shapes);
        this.byJavaStateId = Map.copyOf(byJavaStateId);
        this.blockPropertyMasksByJavaStateId = Map.copyOf(blockPropertyMasksByJavaStateId);
    }

    public static BedrockCollisionOverrideCatalog load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input);
        }
    }

    public static BedrockCollisionOverrideCatalog load(InputStream input) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return from(root);
        }
    }

    public static BedrockCollisionOverrideCatalog from(JsonObject root) {
        if (root == null) {
            throw new IllegalArgumentException("Bedrock collision override artifact is required");
        }
        String schema = string(root.get("schema"), "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported Bedrock collision override schema " + schema);
        }

        JsonArray rawShapes = array(root.get("shapes"), "shapes");
        List<BedrockCollisionOverrideShape> shapes = new ArrayList<>(rawShapes.size());
        for (JsonElement rawShape : rawShapes) {
            shapes.add(new BedrockCollisionOverrideShape(parseShape(rawShape)));
        }

        JsonArray javaStateIds = array(root.get("java_state_ids"), "java_state_ids");
        JsonArray indices = array(root.get("indices"), "indices");
        if (javaStateIds.size() != indices.size()) {
            throw new IllegalArgumentException(
                    "java_state_ids length " + javaStateIds.size() + " does not match indices length " + indices.size());
        }

        Map<Integer, BedrockCollisionOverrideShape> byJavaStateId = new LinkedHashMap<>();
        for (int i = 0; i < javaStateIds.size(); i++) {
            int javaStateId = integer(javaStateIds.get(i), "java_state_ids[" + i + "]");
            int shapeIndex = integer(indices.get(i), "indices[" + i + "]");
            if (shapeIndex < 0 || shapeIndex >= shapes.size()) {
                throw new IllegalArgumentException("shape index out of range for Java state id " + javaStateId + ": " + shapeIndex);
            }
            BedrockCollisionOverrideShape previous = byJavaStateId.put(javaStateId, shapes.get(shapeIndex));
            if (previous != null) {
                throw new IllegalArgumentException("duplicate Bedrock collision override for Java state id " + javaStateId);
            }
        }

        return new BedrockCollisionOverrideCatalog(
                object(root.get("source"), "source"),
                object(root.get("summary"), "summary"),
                shapes,
                byJavaStateId,
                parseBlockPropertyMasks(root));
    }

    public static BedrockCollisionOverrideCatalog bundled() {
        BedrockCollisionOverrideCatalog cached = bundled;
        if (cached != null) {
            return cached;
        }
        synchronized (BedrockCollisionOverrideCatalog.class) {
            if (bundled == null) {
                bundled = loadBundled();
            }
            return bundled;
        }
    }

    private static BedrockCollisionOverrideCatalog loadBundled() {
        try (InputStream input = BedrockCollisionOverrideCatalog.class.getClassLoader().getResourceAsStream(BUNDLED_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing Bedrock collision override resource " + BUNDLED_RESOURCE);
            }
            return load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load Bedrock collision override resource " + BUNDLED_RESOURCE, exception);
        }
    }

    public Optional<BedrockCollisionOverrideShape> override(int javaStateId) {
        return Optional.ofNullable(byJavaStateId.get(javaStateId));
    }

    public boolean hasOverride(int javaStateId) {
        return byJavaStateId.containsKey(javaStateId);
    }

    public OptionalLong blockPropertyMask(int javaStateId) {
        Long value = blockPropertyMasksByJavaStateId.get(javaStateId);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public int overrideCount() {
        return byJavaStateId.size();
    }

    public int shapeCount() {
        return shapes.size();
    }

    public JsonObject source() {
        return source.deepCopy();
    }

    public JsonObject summary() {
        return summary.deepCopy();
    }

    private static List<BlockAabb> parseShape(JsonElement element) {
        JsonArray rawBoxes = array(element, "shape");
        List<BlockAabb> boxes = new ArrayList<>(rawBoxes.size());
        for (int i = 0; i < rawBoxes.size(); i++) {
            JsonArray coordinates = array(rawBoxes.get(i), "shape[" + i + "]");
            if (coordinates.size() != 6) {
                throw new IllegalArgumentException("collision box must contain six coordinates: " + coordinates);
            }
            boxes.add(new BlockAabb(
                    coordinate(coordinates.get(0), "minX"),
                    coordinate(coordinates.get(1), "minY"),
                    coordinate(coordinates.get(2), "minZ"),
                    coordinate(coordinates.get(3), "maxX"),
                    coordinate(coordinates.get(4), "maxY"),
                    coordinate(coordinates.get(5), "maxZ")));
        }
        return List.copyOf(boxes);
    }

    private static Map<Integer, Long> parseBlockPropertyMasks(JsonObject root) {
        JsonElement rawJavaStateIds = root.get("metadata_java_state_ids");
        JsonElement rawIndices = root.get("metadata_indices");
        JsonElement rawMasks = root.get("block_property_masks");
        if (rawJavaStateIds == null && rawIndices == null && rawMasks == null) {
            return Map.of();
        }
        JsonArray javaStateIds = array(rawJavaStateIds, "metadata_java_state_ids");
        JsonArray indices = array(rawIndices, "metadata_indices");
        JsonArray masks = array(rawMasks, "block_property_masks");
        if (javaStateIds.size() != indices.size()) {
            throw new IllegalArgumentException("metadata Java state IDs and indices must have equal lengths");
        }
        Map<Integer, Long> byJavaStateId = new LinkedHashMap<>();
        for (int i = 0; i < javaStateIds.size(); i++) {
            int javaStateId = integer(javaStateIds.get(i), "metadata_java_state_ids[" + i + "]");
            int maskIndex = integer(indices.get(i), "metadata_indices[" + i + "]");
            if (maskIndex < 0 || maskIndex >= masks.size()) {
                throw new IllegalArgumentException("block property mask index out of range for Java state id " + javaStateId);
            }
            long mask = longInteger(masks.get(maskIndex), "block_property_masks[" + maskIndex + "]");
            if (byJavaStateId.put(javaStateId, mask) != null) {
                throw new IllegalArgumentException("duplicate block property metadata for Java state id " + javaStateId);
            }
        }
        return byJavaStateId;
    }

    private static JsonObject object(JsonElement element, String name) {
        if (element == null || element.isJsonNull()) {
            return new JsonObject();
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject().deepCopy();
        }
        throw new IllegalArgumentException(name + " is not an object: " + element);
    }

    private static JsonArray array(JsonElement element, String name) {
        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        throw new IllegalArgumentException(name + " is not an array: " + element);
    }

    private static String string(JsonElement element, String name) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " is not a string: " + element);
        }
        return element.getAsString();
    }

    private static int integer(JsonElement element, String name) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " is not an integer: " + element);
        }
        return element.getAsInt();
    }

    private static long longInteger(JsonElement element, String name) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " is not an integer: " + element);
        }
        return element.getAsLong();
    }

    private static double coordinate(JsonElement element, String name) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " is not a number: " + element);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + element);
        }
        return value;
    }
}
