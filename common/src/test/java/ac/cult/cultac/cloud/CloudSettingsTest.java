package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.grim.grimac.api.config.ConfigManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CloudSettingsTest {
    private static ConfigManager config(Map<String, String> strings) {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> strings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        Mockito.when(config.getBooleanElse(Mockito.eq("cloud.enabled"), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }

    @Test
    void readsTheCloudBlock() {
        CloudSettings settings = CloudSettings.read(
                config(Map.of(
                        "cloud.url", " http://127.0.0.1:8080/ ",
                        "cloud.api-key", "key",
                        "cloud.server-id", "{server}",
                        "cloud.proxy-id", "edge-1")),
                CloudSettings.PROTOCOLS,
                CloudSettings.DEFAULT_SERVER_ID);
        assertEquals("http://127.0.0.1:8080", settings.url());
        assertEquals("key", settings.apiKey());
        assertEquals("{server}", settings.serverIdTemplate());
        assertEquals("edge-1", settings.proxyId());
        assertEquals(List.of(5), settings.protocols());
        assertTrue(settings.enabled());
    }

    @Test
    void onlyHttpsOrLoopbackHttpWithoutUserInfoTurnsTheUploadOn() throws Exception {
        try (Globals globals = new Globals()) {
            for (String url : List.of(
                    "http://example.com",
                    "https://u:p@example.com",
                    "https://example.com?key=1",
                    "https://example.com#part",
                    "ftp://example.com",
                    "https:///path",
                    "not a url")) {
                assertFalse(read(url).enabled(), url);
            }
            for (String url : List.of(
                    "http://127.0.0.1",
                    "http://127.0.0.1:8080/base",
                    "http://localhost:8080",
                    "http://[::1]:8080",
                    "https://example.com",
                    "https://example.com:8443/base")) {
                assertTrue(read(url).enabled(), url);
            }
            synchronized (globals.logs) {
                assertEquals(7, globals.logs.size());
                assertTrue(globals.logs.stream()
                        .noneMatch(record -> record.getMessage().contains("u:p")));
            }
        }
    }

    @Test
    void aKeyThatIsNotAHeaderValueLeavesTheUploadOffAndIsNeverLogged() throws Exception {
        try (Globals globals = new Globals()) {
            for (String key : List.of(
                    "issued-token-0123456789\u007f", "issued-token\n0123456789", "issued-token\u200b0123456789")) {
                CloudSettings settings = read(
                        Map.of(
                                "cloud.url", "https://intake.example",
                                "cloud.api-key", key,
                                "cloud.proxy-id", "lobby"),
                        CloudSettings.PROTOCOLS);
                assertEquals("", settings.apiKey());
                assertFalse(settings.enabled());
            }
            CloudSettings fine = read(
                    Map.of("cloud.url", "https://intake.example", "cloud.api-key", " issued-token_0123456789= "),
                    CloudSettings.PROTOCOLS);
            assertEquals("issued-token_0123456789=", fine.apiKey());
            synchronized (globals.logs) {
                assertEquals(3, globals.logs.size());
                for (LogRecord record : globals.logs) {
                    assertEquals(Level.WARNING, record.getLevel());
                    assertEquals(globals.logs.get(0).getMessage(), record.getMessage());
                    assertFalse(record.getMessage().contains("issued-token"), record.getMessage());
                    assertFalse(record.getMessage().contains("0123456789"), record.getMessage());
                }
            }
        }
    }

    @Test
    void theKeyIsNotInToString() {
        CloudSettings settings = new CloudSettings(
                "https://intake.example", "issued-token-0123456789", "{server}", "lobby", List.of(5), false, false);
        String text = settings.toString();
        assertFalse(text.contains("issued-token-0123456789"), text);
        assertTrue(text.contains("https://intake.example"), text);
    }

    @Test
    void perfIsOffByDefault() {
        ConfigManager config = config(Map.of());
        Mockito.when(config.getBooleanElse(Mockito.eq("cloud.perf"), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        assertFalse(CloudSettings.perf(config, name -> null));
        assertFalse(CloudSettings.OFF.perf());
    }

    @Test
    void perfFollowsTheConfig() {
        ConfigManager config = config(Map.of());
        Mockito.when(config.getBooleanElse(Mockito.eq("cloud.perf"), Mockito.anyBoolean()))
                .thenReturn(true);
        assertTrue(CloudSettings.perf(config, name -> null));
    }

    @Test
    void theEnvironmentWinsOverTheConfigForPerf() {
        ConfigManager off = config(Map.of());
        Mockito.when(off.getBooleanElse(Mockito.eq("cloud.perf"), Mockito.anyBoolean()))
                .thenReturn(false);
        ConfigManager on = config(Map.of());
        Mockito.when(on.getBooleanElse(Mockito.eq("cloud.perf"), Mockito.anyBoolean()))
                .thenReturn(true);
        for (String yes : List.of("true", "1", "yes", " YES ")) {
            assertTrue(CloudSettings.perf(off, Map.of("CULTAC_CLOUD_PERF", yes)::get), yes);
        }
        for (String no : List.of("false", "0", "no", "False")) {
            assertFalse(CloudSettings.perf(on, Map.of("CULTAC_CLOUD_PERF", no)::get), no);
        }
        assertTrue(CloudSettings.perf(on, Map.of("CULTAC_CLOUD_PERF", "")::get));
        assertTrue(CloudSettings.perf(on, Map.of("CULTAC_CLOUD_PERF", "maybe")::get));
    }

    @Test
    void teamSyncIsOffByDefault() {
        ConfigManager config = config(Map.of());
        Mockito.when(config.getBooleanElse(Mockito.eq("cloud.team-sync"), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        assertFalse(CloudSettings.team(config, name -> null));
        assertFalse(CloudSettings.team(config, Map.of(CloudSettings.TEAM_ENV, "maybe")::get));
        assertFalse(CloudSettings.OFF.team());
    }

    @Test
    void theEnvironmentWinsOverTheConfigForTeamSync() {
        ConfigManager off = config(Map.of());
        Mockito.when(off.getBooleanElse(Mockito.eq("cloud.team-sync"), Mockito.anyBoolean()))
                .thenReturn(false);
        ConfigManager on = config(Map.of());
        Mockito.when(on.getBooleanElse(Mockito.eq("cloud.team-sync"), Mockito.anyBoolean()))
                .thenReturn(true);
        assertTrue(CloudSettings.team(on, name -> null));
        for (String yes : List.of("true", "1", "yes", " YES ")) {
            assertTrue(CloudSettings.team(off, Map.of(CloudSettings.TEAM_ENV, yes)::get), yes);
        }
        for (String no : List.of("false", "0", "no", "False")) {
            assertFalse(CloudSettings.team(on, Map.of(CloudSettings.TEAM_ENV, no)::get), no);
        }
        assertTrue(CloudSettings.team(on, Map.of(CloudSettings.TEAM_ENV, "")::get));
    }

    private static CloudSettings read(String url) {
        return read(Map.of("cloud.url", url, "cloud.api-key", "key"), CloudSettings.PROTOCOLS);
    }

    private static CloudSettings read(Map<String, String> strings, List<Integer> protocols) {
        return CloudSettings.read(config(strings), protocols, CloudSettings.OWN_SERVER_ID);
    }

    @Test
    void theEnvironmentWinsOverTheConfigAndProxyIdFallsBackToTheEnvironment() {
        Map<String, String> env = Map.of(
                "CULTAC_CLOUD_URL", "https://env.example",
                "CULTAC_CLOUD_API_KEY", " env-key ",
                "CULTAC_PROXY_ID", "p0");
        CloudSettings settings = CloudSettings.read(
                config(Map.of("cloud.url", "https://config.example", "cloud.api-key", "config-key")),
                CloudSettings.PROTOCOLS,
                CloudSettings.DEFAULT_SERVER_ID,
                env::get);
        assertEquals("https://env.example", settings.url());
        assertEquals("env-key", settings.apiKey());
        assertEquals("p0", settings.proxyId());
        CloudSettings configured = CloudSettings.read(
                config(Map.of("cloud.proxy-id", "edge-1")),
                CloudSettings.PROTOCOLS,
                CloudSettings.DEFAULT_SERVER_ID,
                env::get);
        assertEquals("edge-1", configured.proxyId());
        CloudSettings bare = CloudSettings.read(
                config(Map.of()), CloudSettings.PROTOCOLS, CloudSettings.DEFAULT_SERVER_ID, name -> null);
        assertEquals("", bare.url());
        assertFalse(bare.enabled());
    }

    @Test
    void anEmptyUrlOrKeyIsOff() {
        assertFalse(
                read(Map.of("cloud.api-key", "key"), CloudSettings.PROTOCOLS).enabled());
        assertFalse(read(Map.of("cloud.url", "http://127.0.0.1:8080", "cloud.api-key", " "), CloudSettings.PROTOCOLS)
                .enabled());
        assertFalse(read(Map.of("cloud.url", "http://127.0.0.1:8080", "cloud.api-key", "key"), List.of())
                .enabled());
    }

    @Test
    void anEmptyServerIdTakesThePlatformsDefault() {
        assertEquals(
                CloudSettings.OWN_SERVER_ID,
                CloudSettings.read(
                                config(Map.of("cloud.server-id", "")),
                                CloudSettings.PROTOCOLS,
                                CloudSettings.OWN_SERVER_ID)
                        .serverIdTemplate());
        assertEquals(
                CloudSettings.DEFAULT_SERVER_ID,
                CloudSettings.read(
                                config(Map.of("cloud.server-id", "")),
                                CloudSettings.PROTOCOLS,
                                CloudSettings.DEFAULT_SERVER_ID)
                        .serverIdTemplate());
        assertEquals(
                "{proxy}-{server}",
                CloudSettings.read(
                                config(Map.of("cloud.server-id", "{proxy}-{server}")),
                                CloudSettings.PROTOCOLS,
                                CloudSettings.OWN_SERVER_ID)
                        .serverIdTemplate());
    }

    @Test
    void aProxyTemplateWithoutAProxyIdLeavesTheUploadOff() {
        OfflineCultTestBootstrap.installConfig();
        try {
            CloudService.configure(config(Map.of("cloud.url", "http://127.0.0.1:8080", "cloud.api-key", "key")), true);
            assertEquals("Cloud upload off", CloudService.status().get(0));
            CloudService.configure(
                    config(Map.of(
                            "cloud.url", "http://127.0.0.1:8080", "cloud.api-key", "key", "cloud.proxy-id", "edge-1")),
                    true);
            assertTrue(CloudService.status().get(0).startsWith("Cloud upload on, protocols [5]"));
        } finally {
            CloudService.configure(config(Map.of()), true);
            CloudCapture.uplinks(CloudCapture.Uplinks.NONE);
        }
        assertEquals("Cloud upload off", CloudService.status().get(0));
    }

    @Test
    void aServerUploadsOnlyUnderTheNameItIsGiven() {
        OfflineCultTestBootstrap.installConfig();
        try {
            CloudService.configure(config(Map.of("cloud.url", "http://127.0.0.1:8080", "cloud.api-key", "key")), false);
            assertEquals("Cloud upload off", CloudService.status().get(0));
            CloudService.configure(
                    config(Map.of(
                            "cloud.url", "http://127.0.0.1:8080", "cloud.api-key", "key", "cloud.proxy-id", "arena-1")),
                    false);
            assertTrue(CloudService.status()
                    .get(0)
                    .startsWith("Cloud upload on, protocols " + CloudSettings.PROTOCOLS + ","));
        } finally {
            CloudService.configure(config(Map.of()), false);
            CloudCapture.uplinks(CloudCapture.Uplinks.NONE);
        }
        assertEquals("Cloud upload off", CloudService.status().get(0));
    }

    @Test
    void theBundledConfigsCarryTheCloudBlockOff() throws IOException {
        for (String locale : List.of("de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "zh")) {
            try (var input = CloudSettingsTest.class.getResourceAsStream("/config/" + locale + ".yml")) {
                assertNotNull(input, locale);
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("\ncloud:"), locale);
                assertTrue(text.contains("  url: \"\""), locale);
                assertTrue(text.contains("  api-key: \"\""), locale);
                assertTrue(text.contains("  server-id: \"\""), locale);
                assertTrue(text.contains("config-version: 14"), locale);
            }
        }
    }
}
