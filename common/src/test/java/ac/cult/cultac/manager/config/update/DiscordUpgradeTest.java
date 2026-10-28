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
            embed-footer-text: "%uuid% v%cult_version%"
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

    private static final String V4_GRIM = """
            enabled: true
            webhook: "https://discord.com/api/webhooks/1/abc"
            embed-title: "%player% has been detected for %check% [%violations%]"
            embed-footer-text: "%uuid% v%cult_version%"
            embed-footer-url: ""
            include-state: true
            violation-content:
                - "**Version**: `%version%` **Brand**: `%brand%`"
                - "**Server**: `%server%` **World**: `%world%` **Location**: `%location%`"
                - "**TPS**: `%tps%` **Ping**: `%ping%/%keepalive_ping%` **Clock**: `%clock_behind%ms` **Time**: `%session_time%`"
                - "**xsens**: `%h_sensitivity%%` **ysens**: `%v_sensitivity%%`"
                - "**State**: %state%"
                - "**Checks**: %checks%"
                - "**Verbose**: %verbose%"
            config-flavor: V2
            config-version: 4
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
        assertEquals(5, data.get("config-version"));
    }

    @Test
    void turnedOffSectionsAndUnbundledKeysStay() throws Exception {
        Map<String, Object> data = upgrade(V2_GLADIATOR.replace(
                "include-state: true\n",
                "include-state: false\ninclude-verbose: false\nembed-image-url: \"https://example.com/banner.png\"\n"));
        assertEquals(bundled("en").get("violation-content"), data.get("violation-content"));
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
    void shippedV3GrimLayoutTakesTheNewDefault() throws Exception {
        Map<String, Object> data = upgrade(V3_RAW_SENSITIVITY);
        assertEquals(bundled("en").get("violation-content"), data.get("violation-content"));
        assertEquals("v%cult_version%", data.get("embed-footer-text"));
        assertEquals("Alert on %server%", data.get("embed-title"));
        assertEquals("https://example.com/logo.png", data.get("embed-footer-url"));
        assertEquals("https://example.com/banner.png", data.get("embed-image-url"));
        assertEquals(false, data.get("include-state"));
        assertEquals(false, data.get("include-verbose"));
        assertEquals(5, data.get("config-version"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"**Brand**", "**Marke**"})
    void shippedV4GrimLayoutTakesTheNewDefault(String brandLabel) throws Exception {
        Map<String, Object> data = upgrade(V4_GRIM.replace("**Brand**", brandLabel));
        Map<String, Object> bundled = bundled("en");
        assertEquals(bundled.get("violation-content"), data.get("violation-content"));
        assertEquals(bundled.get("embed-title"), data.get("embed-title"));
        assertEquals("v%cult_version%", data.get("embed-footer-text"));
        assertEquals(5, data.get("config-version"));
    }

    @Test
    void shippedChineseGrimLayoutTakesTheNewDefault() throws Exception {
        Map<String, Object> data = upgrade(V4_GRIM.replace("**Version**", "**版本**")
                .replace("**Brand**", "**品牌**")
                .replace("**Server**", "**服务器**")
                .replace("**World**", "**世界**")
                .replace("**Location**", "**位置**")
                .replace("**Ping**", "**延迟**")
                .replace("**Clock**", "**时钟**")
                .replace("**Time**", "**时长**")
                .replace("**State**", "**状态**")
                .replace("**Checks**", "**检查**")
                .replace("**Verbose**", "**详情**"));
        assertEquals(bundled("en").get("violation-content"), data.get("violation-content"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                " **Location**: `%location%`|",
                "**Brand**|**Client**",
                "**Brand**|**品牌**",
                "**Clock**|**Time**",
                "**State**|****",
                "    - \"**Checks**: %checks%\"\n|"
            })
    void operatorGrimLayoutStays(String edit) throws Exception {
        String[] change = edit.split("\\|", -1);
        String file = V4_GRIM.replace(change[0], change[1]);
        List<?> before = (List<?>) new Yaml().<Map<String, Object>>load(file).get("violation-content");
        Map<String, Object> data = upgrade(file.replace("%uuid% v%cult_version%", "%uuid%"));
        assertEquals(before, data.get("violation-content"));
        assertEquals("%uuid%", data.get("embed-footer-text"));
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
        assertEquals(5, data.get("config-version"));
        assertEquals(true, data.get("include-state"));
        assertEquals("", data.get("embed-footer-url"));
        assertEquals("v%cult_version%", data.get("embed-footer-text"));
        assertEquals("https://crafthead.net/helm/%uuid%", data.get("embed-thumbnail-url"));
        String title = (String) data.get("embed-title");
        assertTrue(title.contains("%player%") && title.contains("%check%") && title.contains("%violations%"));
        List<?> content = (List<?>) data.get("violation-content");
        assertEquals(
                bundled("en").get("violation-content").toString().replaceAll("\\*\\*[^*]+\\*\\*", ""),
                content.toString().replaceAll("\\*\\*[^*]+\\*\\*", ""));
        for (Object line : content) {
            assertTrue(((String) line).endsWith("`"), line + " is not in a code span");
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
