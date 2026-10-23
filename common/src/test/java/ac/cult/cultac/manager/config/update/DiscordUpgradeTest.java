package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

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

class DiscordUpgradeTest {
    private static final String V1_GERMAN = """
            enabled: true
            webhook: "https://discord.com/api/webhooks/1/abc"
            embed-title: "**Cult Warnung**"
            violation-content:
                - "**Spieler**: `%player%`"
                - "**Überprüfung**: %check%"
                - "**Verstöße**: %violations%"
                - "**Client-Version**: %version%"
                - "**Marke**: `%brand%`"
                - "**Ping**: %ping%"
                - "**TPS**: %tps%"
            config-flavor: V2
            config-version: 1
            """;

    @TempDir
    Path directory;

    @Test
    void shippedLayoutTakesTheNewDefault() throws Exception {
        Map<String, Object> data = upgrade(V1_GERMAN);
        Map<String, Object> bundled = bundled("en");
        assertEquals(bundled.get("embed-title"), data.get("embed-title"));
        assertEquals(bundled.get("violation-content"), data.get("violation-content"));
        assertEquals(true, data.get("include-state"));
        assertEquals(true, data.get("enabled"));
        assertEquals("https://discord.com/api/webhooks/1/abc", data.get("webhook"));
    }

    @Test
    void operatorLayoutStays() throws Exception {
        Map<String, Object> data = upgrade(V1_GERMAN
                .replace("**Cult Warnung**", "Alert on %server%")
                .replace("    - \"**TPS**: %tps%\"\n", "    - \"**Server**: %server%\"\n"));
        assertEquals("Alert on %server%", data.get("embed-title"));
        assertEquals("**Server**: %server%", ((List<?>) data.get("violation-content")).get(6));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "zh"})
    void bundledLanguagesShipTheNewLayout(String language) throws Exception {
        Map<String, Object> data = bundled(language);
        assertEquals(2, data.get("config-version"));
        assertEquals(true, data.get("include-state"));
        String title = (String) data.get("embed-title");
        assertTrue(title.contains("%player%") && title.contains("%check%") && title.contains("%violations%"));
        List<?> content = (List<?>) data.get("violation-content");
        for (Object line : content) assertTrue(((String) line).endsWith("`"), line + " is not in a code span");
        assertFalse(content.stream().anyMatch(line -> ((String) line).contains("%violations%")));
    }

    private Map<String, Object> upgrade(String contents) throws Exception {
        Path file = directory.resolve("discord.yml");
        Files.writeString(file, contents);
        var result = new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.discord());
        assertTrue(result.migrated);
        return new Yaml().load(Files.readString(file));
    }

    private static Map<String, Object> bundled(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/discord/" + language + ".yml")) {
            assertNotNull(source);
            return new Yaml().load(source);
        }
    }
}
