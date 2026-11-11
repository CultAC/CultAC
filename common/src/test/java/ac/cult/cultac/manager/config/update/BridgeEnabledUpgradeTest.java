package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

class BridgeEnabledUpgradeTest {
    @TempDir
    Path directory;

    @Test
    void aV18ConfigGainsTheBridgeOnAndKeepsItsSecret() throws Exception {
        Map<String, Object> bridge = bridge(upgrade("bridge:\n  secret: \"a-long-random-bridge-secret\"\n"));
        assertEquals(true, bridge.get("enabled"));
        assertEquals("a-long-random-bridge-secret", bridge.get("secret"));
    }

    @Test
    void anOperatorsBridgeOffIsKept() throws Exception {
        assertEquals(false, bridge(upgrade("bridge:\n  enabled: false\n  secret: \"\"\n")).get("enabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void bundledLanguagesShipTheBridgeOn(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(20, data.get("config-version"));
            assertEquals(Map.of("enabled", true, "secret", ""), bridge(data));
        }
    }

    private Map<String, Object> upgrade(String content) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, content + "config-flavor: V2\nconfig-version: 18\n");
        assertTrue(new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.mainConfig())
                .migrated);
        Map<String, Object> data = new Yaml().load(Files.readString(file));
        assertEquals(20, data.get("config-version"));
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bridge(Map<String, Object> data) {
        return (Map<String, Object>) data.get("bridge");
    }
}
