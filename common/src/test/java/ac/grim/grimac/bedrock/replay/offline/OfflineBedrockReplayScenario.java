package ac.grim.grimac.bedrock.replay.offline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

final class OfflineBedrockReplayScenario {
    private final Path root;
    private final Properties fixtureProperties;
    private final JsonObject manifest;

    private OfflineBedrockReplayScenario(Path root, Properties fixtureProperties, JsonObject manifest) {
        this.root = root;
        this.fixtureProperties = fixtureProperties;
        this.manifest = manifest;
    }

    static OfflineBedrockReplayScenario load(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path normalized = root.toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(normalized.resolve("fixture.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(normalized.resolve("manifest.json"), StandardCharsets.UTF_8)) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }
        return new OfflineBedrockReplayScenario(normalized, properties, manifest);
    }

    Path root() {
        return root;
    }

    Path packetsPath() {
        return root.resolve("packets.ndjson");
    }

    Path schematicPath() {
        return root.resolve("region.schem");
    }

    Properties fixtureProperties() {
        return fixtureProperties;
    }

    JsonObject manifest() {
        return manifest;
    }

    int protocolVersion() {
        return manifest.has("protocolVersion") ? manifest.get("protocolVersion").getAsInt() : 0;
    }

    String name() {
        String propertyName = fixtureProperties.getProperty("scenario", "").trim();
        return propertyName.isEmpty() ? root.getFileName().toString() : propertyName;
    }

    double spawnX() {
        return doubleProperty("spawn-x", 0.0D);
    }

    double spawnY() {
        return doubleProperty("spawn-y", 64.0D);
    }

    double spawnZ() {
        return doubleProperty("spawn-z", 0.0D);
    }

    int minX() {
        return intProperty("min-x", 0);
    }

    int minY() {
        return intProperty("min-y", 0);
    }

    int minZ() {
        return intProperty("min-z", 0);
    }

    private int intProperty(String key, int fallback) {
        String value = fixtureProperties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private double doubleProperty(String key, double fallback) {
        String value = fixtureProperties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Double.parseDouble(value.trim());
    }
}
