package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class CultFileFoldTest {
    @TempDir
    Path directory;

    @Test
    void cultYmlSettingsMoveIntoConfigAndSurviveItsUpgrade() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "alerts:\n  print-to-console: false\nconfig-flavor: V2\nconfig-version: 19\n");
        String legacy = """
                cult:
                  exempt-bedrock-players: true
                  checks:
                    fair-reach:
                      max-ping-compensation-ms: 250
                      max-reach: 3.5
                  diagnostics:
                    teleports: true
                config-flavor: V2
                config-version: 2
                """;
        Files.writeString(directory.resolve("cult.yml"), legacy);

        ConfigUpdater updater = new ConfigUpdater(
                CultConfigSpecs.class, Logger.getLogger(getClass().getName()));
        updater.foldLegacyCultFile(config.toFile(), false, "en");
        updater.update(config.toFile(), CultConfigSpecs.mainConfig());

        String text = Files.readString(config);
        assertTrue(text.contains("# Skip all checks for Bedrock players"));
        Map<String, Object> data = new Yaml().load(text);
        assertEquals(false, section(data, "alerts").get("print-to-console"));
        Map<String, Object> cult = section(data, "cult");
        assertEquals(true, cult.get("exempt-bedrock-players"));
        assertEquals(false, cult.get("exempt-java-players"));
        assertEquals(true, section(cult, "diagnostics").get("teleports"));
        Map<String, Object> fairReach = section(section(cult, "checks"), "fair-reach");
        assertEquals(0.5, ((Number) fairReach.get("reach-leniency")).doubleValue());
        assertEquals(250, fairReach.get("max-ping-compensation-ms"));
        assertFalse(fairReach.containsKey("max-reach"));
        assertFalse(Files.exists(directory.resolve("cult.yml")));
        assertEquals(legacy, Files.readString(directory.resolve("cult.yml.migrated")));
    }

    @Test
    void aCultSectionInConfigWinsOverCultYml() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "cult:\n  exempt-bedrock-players: false\nconfig-flavor: V2\nconfig-version: 20\n");
        Files.writeString(
                directory.resolve("cult.yml"),
                "cult:\n  exempt-bedrock-players: true\n  exempt-java-players: true\nconfig-version: 3\n");

        new ConfigUpdater(CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .foldLegacyCultFile(config.toFile(), false, "en");

        Map<String, Object> cult = section(new Yaml().load(Files.readString(config)), "cult");
        assertEquals(false, cult.get("exempt-bedrock-players"));
        assertEquals(true, cult.get("exempt-java-players"));
        assertTrue(Files.exists(directory.resolve("cult.yml")));
    }

    @Test
    void cultYmlWinsOverTheBundledSectionOfAFreshConfig() throws Exception {
        Path config = directory.resolve("config.yml");
        try (var bundled = CultConfigSpecs.class.getResourceAsStream("/config/en.yml")) {
            Files.copy(bundled, config);
        }
        Files.writeString(directory.resolve("cult.yml"), "cult:\n  exempt-java-players: true\nconfig-version: 3\n");

        new ConfigUpdater(CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .foldLegacyCultFile(config.toFile(), false, "en");

        assertEquals(true, section(new Yaml().load(Files.readString(config)), "cult").get("exempt-java-players"));
        assertTrue(Files.exists(directory.resolve("cult.yml.migrated")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void bundledLanguagesShipTheCultSection(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> cult = section(new Yaml().load(source), "cult");
            assertEquals(0.1, section(section(cult, "checks"), "fair-reach").get("reach-leniency"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> data, String key) {
        return (Map<String, Object>) data.get(key);
    }
}
