package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

class UncapturedServersUpgradeTest {
    @TempDir
    Path directory;

    @Test
    void aV19ConfigGainsAnEmptyListAndKeepsItsProxyId() throws Exception {
        Map<String, Object> cloud = cloud(upgrade("cloud:\n  proxy-id: \"proxy-a\"\n"));
        assertEquals(List.of(), cloud.get("uncaptured-servers"));
        assertEquals("proxy-a", cloud.get("proxy-id"));
    }

    @Test
    void anOperatorsListIsKept() throws Exception {
        Map<String, Object> cloud = cloud(upgrade("cloud:\n  uncaptured-servers:\n    - lobby\n    - hub-*\n"));
        assertEquals(List.of("lobby", "hub-*"), cloud.get("uncaptured-servers"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void bundledLanguagesShipAnEmptyList(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(20, data.get("config-version"));
            assertEquals(List.of(), cloud(data).get("uncaptured-servers"));
        }
    }

    private Map<String, Object> upgrade(String content) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, content + "config-flavor: V2\nconfig-version: 19\n");
        assertTrue(new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.mainConfig())
                .migrated);
        Map<String, Object> data = new Yaml().load(Files.readString(file));
        assertEquals(20, data.get("config-version"));
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cloud(Map<String, Object> data) {
        return (Map<String, Object>) data.get("cloud");
    }
}
