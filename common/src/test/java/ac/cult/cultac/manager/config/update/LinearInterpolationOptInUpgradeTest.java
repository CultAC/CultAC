package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.latency.ClientInterpolationOptIn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        assertEquals(List.of("antistutter:linear_3"), data.get("linear-interpolation-channels"));
        assertEquals(true, data.get("disable-pong-cancelling"));
    }

    @Test
    void anOperatorsOptInIsKept() throws Exception {
        assertEquals(true, upgrade("linear-interpolation-opt-in: true\n").get("linear-interpolation-opt-in"));
    }

    @Test
    void noChannelsConfiguredMeansTheDefault() {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of("antistutter:linear_3"), ClientInterpolationOptIn.configuredChannels(null, warnings::add));
        assertEquals(
                List.of("antistutter:linear_3"), ClientInterpolationOptIn.configuredChannels(List.of(), warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void channelsThatCannotBeOfferedAreLeftOutWithAWarningEach() {
        List<String> entries = new ArrayList<>(List.of(
                "lunar:linear_3",
                "Not A Channel",
                "nopath:",
                "minecraft:linear_3",
                "cultac:bridge",
                "interpolationfix:linear_3"));
        for (int i = 2; i <= 8; i++) entries.add("client" + i + ":linear_3");
        entries.add("client9:linear_3");
        List<String> warnings = new ArrayList<>();
        List<String> channels = ClientInterpolationOptIn.configuredChannels(entries, warnings::add);
        assertEquals(8, channels.size());
        assertEquals("lunar:linear_3", channels.get(0));
        assertEquals("client8:linear_3", channels.get(7));
        assertEquals(6, warnings.size());
        assertTrue(warnings.get(4).contains("interpolationfix:linear_3"));
        assertTrue(warnings.get(5).contains("client9:linear_3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void bundledLanguagesShipTheOptInOff(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(21, data.get("config-version"));
            assertEquals(false, data.get("linear-interpolation-opt-in"));
            assertEquals(List.of("antistutter:linear_3"), data.get("linear-interpolation-channels"));
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
