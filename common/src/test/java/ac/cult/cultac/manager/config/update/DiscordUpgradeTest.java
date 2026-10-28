package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

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

    private static final String V2_GLADIATOR = """
            enabled: true
            webhook: "https://discord.com/api/webhooks/1/abc"
            embed-title: "**Gladiator Alert**"
            embed-footer-url: "https://example.com/logo.png"
            include-state: true
            violation-content:
                - "**Player**: `%player%`"
                - "**UUID**: `%uuid%`"
                - "**Check**: `%check%`"
                - "**Client Version**: `%version%`"
                - "**Brand**: `%brand%`"
                - "**Sensitivity**: `H %h_sensitivity%% / V %v_sensitivity%%`"
                - "**Ping**: `%ping%`"
                - "**TPS**: `%tps%`"
                - "**Server**: `%server%`"
                - "**World**: `%world% (%world_type%)`"
            config-flavor: V2
            config-version: 2
            """;

    private static final String V3_RAW_SENSITIVITY = """
            enabled: true
            webhook: "https://discord.com/api/webhooks/1/abc"
            embed-title: "Alert on %server%"
            embed-footer-url: "https://example.com/logo.png"
            embed-image-url: "https://example.com/banner.png"
            include-state: false
            include-verbose: false
            violation-content:
                - "**Version**: `%version%` **Brand**: `%brand%`"
                - "**Server**: `%server%` **World**: `%world%` **Location**: `%location%`"
                - "**TPS**: `%tps%` **Ping**: `%ping%/%keepalive_ping%` **Clock**: `%clock_behind%ms` **Time**: `%session_time%`"
                - "**xsens**: `%h_sensitivity_raw%` **ysens**: `%v_sensitivity_raw%`"
                - "**Checks**: %checks%"
            config-flavor: V2
            config-version: 3
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

    @ParameterizedTest
    @ValueSource(strings = {"**Gladiator Alert**", "**Gladiator Warnung**", "**Grim Alert**"})
    void shippedBrandedTitleTakesTheDefault(String title) throws Exception {
        Map<String, Object> data = upgrade(V1_GERMAN.replace("**Cult Warnung**", title));
        assertEquals(bundled("en").get("embed-title"), data.get("embed-title"));
    }

    @Test
    void shippedV2LayoutTakesTheNewDefault() throws Exception {
        Map<String, Object> data = upgrade(V2_GLADIATOR);
        Map<String, Object> bundled = bundled("en");
        assertEquals(bundled.get("embed-title"), data.get("embed-title"));
        assertEquals(bundled.get("violation-content"), data.get("violation-content"));
        assertEquals("https://example.com/logo.png", data.get("embed-footer-url"));
        assertEquals(bundled.get("embed-footer-text"), data.get("embed-footer-text"));
        assertEquals("https://discord.com/api/webhooks/1/abc", data.get("webhook"));
        assertEquals(4, data.get("config-version"));
    }

    @Test
    void turnedOffSectionsStayOffAndUnbundledKeysStay() throws Exception {
        Map<String, Object> data = upgrade(V2_GLADIATOR.replace(
                "include-state: true\n",
                "include-state: false\ninclude-verbose: false\nembed-image-url: \"https://example.com/banner.png\"\n"));
        List<?> content = (List<?>) data.get("violation-content");
        assertEquals(((List<?>) bundled("en").get("violation-content")).size() - 2, content.size());
        assertFalse(content.stream().anyMatch(line -> ((String) line).contains("%state%")));
        assertFalse(content.stream().anyMatch(line -> ((String) line).contains("%verbose%")));
        assertEquals(false, data.get("include-state"));
        assertEquals(false, data.get("include-verbose"));
        assertEquals("https://example.com/banner.png", data.get("embed-image-url"));
    }

    @Test
    void relabelledV2LayoutAndCustomBrandedTitleStay() throws Exception {
        Map<String, Object> data = upgrade(V2_GLADIATOR
                .replace("**Gladiator Alert**", "**Gladiator Staff Review Queue**")
                .replace("**Player**", "**Suspect**"));
        assertEquals("**Gladiator Staff Review Queue**", data.get("embed-title"));
        assertEquals("**Suspect**: `%player%`", ((List<?>) data.get("violation-content")).get(0));
    }

    @Test
    void shippedV3SensitivityShowsPercentages() throws Exception {
        Map<String, Object> data = upgrade(V3_RAW_SENSITIVITY);
        List<Object> expected = new ArrayList<>((List<?>) bundled("en").get("violation-content"));
        expected.removeIf(line -> ((String) line).contains("%state%") || ((String) line).contains("%verbose%"));
        assertEquals(expected, data.get("violation-content"));
        assertEquals("Alert on %server%", data.get("embed-title"));
        assertEquals("https://example.com/logo.png", data.get("embed-footer-url"));
        assertEquals("https://example.com/banner.png", data.get("embed-image-url"));
        assertEquals(false, data.get("include-state"));
        assertEquals(false, data.get("include-verbose"));
        assertEquals(4, data.get("config-version"));
    }

    @Test
    void operatorV3SensitivityStays() throws Exception {
        Map<String, Object> data = upgrade(V3_RAW_SENSITIVITY.replace("**xsens**", "**Sens X**"));
        assertEquals(
                "**Sens X**: `%h_sensitivity_raw%` **ysens**: `%v_sensitivity_raw%`",
                ((List<?>) data.get("violation-content")).get(3));
    }

    @Test
    void operatorV2LayoutStays() throws Exception {
        Map<String, Object> data = upgrade(V2_GLADIATOR
                .replace("**Gladiator Alert**", "**Gladiator** on %server%")
                .replace("`%world% (%world_type%)`", "`%world%`"));
        assertEquals("**Gladiator** on %server%", data.get("embed-title"));
        assertEquals("**World**: `%world%`", ((List<?>) data.get("violation-content")).get(9));
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
        assertEquals(4, data.get("config-version"));
        assertEquals(true, data.get("include-state"));
        assertEquals("", data.get("embed-footer-url"));
        assertEquals("%uuid% v%cult_version%", data.get("embed-footer-text"));
        assertEquals("https://crafthead.net/helm/%uuid%", data.get("embed-thumbnail-url"));
        String title = (String) data.get("embed-title");
        assertTrue(title.contains("%player%") && title.contains("%check%") && title.contains("%violations%"));
        List<?> content = (List<?>) data.get("violation-content");
        assertEquals(
                bundled("en").get("violation-content").toString().replaceAll("\\*\\*[^*]+\\*\\*", ""),
                content.toString().replaceAll("\\*\\*[^*]+\\*\\*", ""));
        for (Object line : content) {
            String text = (String) line;
            if (text.contains("%state%") || text.contains("%checks%") || text.contains("%verbose%")) continue;
            assertTrue(text.endsWith("`"), line + " is not in a code span");
        }
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
