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

class LinearInterpolationOptInUpgradeTest {
    @TempDir
    Path directory;

    @Test
    void aV20ConfigGainsTheOptInOff() throws Exception {
        Map<String, Object> data = upgrade("disable-pong-cancelling: true\n");
        assertEquals(false, data.get("linear-interpolation-opt-in"));
        assertEquals(true, data.get("disable-pong-cancelling"));
    }

    @Test
    void anOperatorsOptInIsKept() throws Exception {
        assertEquals(true, upgrade("linear-interpolation-opt-in: true\n").get("linear-interpolation-opt-in"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void bundledLanguagesShipTheOptInOff(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(21, data.get("config-version"));
            assertEquals(false, data.get("linear-interpolation-opt-in"));
        }
    }

    private Map<String, Object> upgrade(String content) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, content + "config-flavor: V2\nconfig-version: 20\n");
        assertTrue(new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.mainConfig())
                .migrated);
        Map<String, Object> data = new Yaml().load(Files.readString(file));
        assertEquals(21, data.get("config-version"));
        return data;
    }
}
