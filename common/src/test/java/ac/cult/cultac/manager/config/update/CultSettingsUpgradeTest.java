package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

class CultSettingsUpgradeTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"3.1, 0.1", "3.5, 0.5", "3, 0.0", "4.25, 1.25"})
    void fairReachMaxReachBecomesLeniencyPastTheDefaultRange(String maxReach, double leniency) throws Exception {
        Path file = directory.resolve("cult.yml");
        Files.writeString(file, """
                cult:
                  checks:
                    fair-reach:
                      max-ping-compensation-ms: 250
                      max-reach: %s
                config-flavor: V2
                config-version: 2
                """.formatted(maxReach));
        var result = new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.cultSettings());
        assertTrue(result.migrated);
        Map<String, Object> fairReach = fairReach(new Yaml().load(Files.readString(file)));
        assertEquals(leniency, ((Number) fairReach.get("reach-leniency")).doubleValue());
        assertEquals(250, fairReach.get("max-ping-compensation-ms"));
        assertFalse(fairReach.containsKey("max-reach"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void bundledLanguagesShipTheLeniency(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/cult/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(3, data.get("config-version"));
            assertEquals(0.1, fairReach(data).get("reach-leniency"));
            assertFalse(fairReach(data).containsKey("max-reach"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fairReach(Map<String, Object> data) {
        var checks = (Map<String, Object>) ((Map<String, Object>) data.get("cult")).get("checks");
        return (Map<String, Object>) checks.get("fair-reach");
    }
}
