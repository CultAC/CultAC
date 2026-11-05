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

class AlertDefaultScopeUpgradeTest {
    @TempDir
    Path directory;

    @Test
    void aV17ConfigGainsTheServerDefaultAndKeepsItsAlertSettings() throws Exception {
        Map<String, Object> alerts = alerts(upgrade("alerts:\n  print-to-console: false\n"));
        assertEquals("server", alerts.get("default-scope"));
        assertEquals(false, alerts.get("print-to-console"));
    }

    @Test
    void anOperatorsDefaultScopeIsKept() throws Exception {
        assertEquals(
                "global", alerts(upgrade("alerts:\n  default-scope: global\n")).get("default-scope"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void bundledLanguagesShipTheServerDefault(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(18, data.get("config-version"));
            assertEquals("server", alerts(data).get("default-scope"));
        }
    }

    private Map<String, Object> upgrade(String content) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, content + "config-flavor: V2\nconfig-version: 17\n");
        assertTrue(new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.mainConfig())
                .migrated);
        Map<String, Object> data = new Yaml().load(Files.readString(file));
        assertEquals(18, data.get("config-version"));
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> alerts(Map<String, Object> data) {
        return (Map<String, Object>) data.get("alerts");
    }
}
