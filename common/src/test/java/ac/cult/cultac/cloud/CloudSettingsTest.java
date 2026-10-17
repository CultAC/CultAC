package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.grim.grimac.api.config.ConfigManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CloudSettingsTest {
    private static ConfigManager config(Map<String, String> strings) {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> strings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
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
                CloudSettings.PROXY_PROTOCOLS,
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

    private static CloudSettings read(String url) {
        return read(Map.of("cloud.url", url, "cloud.api-key", "key"), CloudSettings.SERVER_PROTOCOLS);
    }

    private static CloudSettings read(Map<String, String> strings, List<Integer> protocols) {
        return CloudSettings.read(config(strings), protocols, CloudSettings.OWN_SERVER_ID);
    }

    @Test
    void anEmptyUrlOrKeyIsOff() {
        assertFalse(read(Map.of("cloud.api-key", "key"), CloudSettings.SERVER_PROTOCOLS)
                .enabled());
        assertFalse(
                read(Map.of("cloud.url", "http://127.0.0.1:8080", "cloud.api-key", " "), CloudSettings.SERVER_PROTOCOLS)
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
                                CloudSettings.SERVER_PROTOCOLS,
                                CloudSettings.OWN_SERVER_ID)
                        .serverIdTemplate());
        assertEquals(
                CloudSettings.DEFAULT_SERVER_ID,
                CloudSettings.read(
                                config(Map.of("cloud.server-id", "")),
                                CloudSettings.PROXY_PROTOCOLS,
                                CloudSettings.DEFAULT_SERVER_ID)
                        .serverIdTemplate());
        assertEquals(
                "{proxy}-{server}",
                CloudSettings.read(
                                config(Map.of("cloud.server-id", "{proxy}-{server}")),
                                CloudSettings.SERVER_PROTOCOLS,
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
                    .startsWith("Cloud upload on, protocols " + CloudSettings.SERVER_PROTOCOLS + ","));
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
                assertTrue(text.contains("config-version: 13"), locale);
            }
        }
    }
}
