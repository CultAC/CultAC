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

class NetworkUriUpgradeTest {
    @TempDir
    Path directory;

    /** A version 15 config whose network block is {@code block}, indented under {@code network:}. */
    private Map<String, Object> upgrade(String block) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "network:\n" + block.indent(2) + "config-flavor: V2\nconfig-version: 15\n");
        var result = new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.mainConfig());
        assertTrue(result.migrated);
        Map<String, Object> data = new Yaml().load(Files.readString(file));
        assertEquals(16, data.get("config-version"));
        Map<String, Object> network = network(data);
        for (String old : new String[] {"transport", "network", "redis", "nats"})
            assertFalse(network.containsKey(old), old + " in " + network);
        return network;
    }

    @Test
    void noTransportLeavesTheUriEmptyAndKeepsTheRest() throws Exception {
        Map<String, Object> network = upgrade("""
                transport: none
                instance-id: p3
                network: default
                secret: shared
                redis:
                  uri: redis://cache:6379
                """);
        assertEquals("", network.get("uri"));
        assertEquals("p3", network.get("instance-id"));
        assertEquals("shared", network.get("secret"));
        assertEquals("", network.get("nats-credentials-file"));
    }

    @Test
    void redisCarriesItsUri() throws Exception {
        Map<String, Object> network = upgrade("""
                transport: Redis
                instance-id: p3
                network: eu
                secret: ""
                redis:
                  uri: rediss://:pw@cache:6380/2
                """);
        assertEquals("rediss://:pw@cache:6380/2", network.get("uri"));
        assertEquals("p3", network.get("instance-id"));
    }

    @Test
    void natsJoinsItsServersAndPutsTheTokenInEachWithoutUserInfo() throws Exception {
        Map<String, Object> network = upgrade("""
                transport: nats
                instance-id: lobby-1
                secret: shared
                nats:
                  servers:
                    - nats://a:4222
                    - tls://user:pw@b:4443
                  token: t0ken
                  credentials-file: /etc/cultac/nats.creds
                """);
        assertEquals("nats://t0ken@a:4222,tls://user:pw@b:4443", network.get("uri"));
        assertEquals("/etc/cultac/nats.creds", network.get("nats-credentials-file"));
        assertEquals("lobby-1", network.get("instance-id"));
        assertEquals("shared", network.get("secret"));
    }

    @Test
    void natsWithoutATokenKeepsItsServersAsTheyAre() throws Exception {
        Map<String, Object> network = upgrade("""
                transport: nats
                nats:
                  servers: [nats://a:4222, nats://b:4222]
                  token: ""
                """);
        assertEquals("nats://a:4222,nats://b:4222", network.get("uri"));
    }

    @Test
    void redisBungeeBecomesItsKeyword() throws Exception {
        Map<String, Object> network = upgrade("""
                transport: redisbungee
                instance-id: ""
                redis:
                  uri: redis://ignored:6379
                """);
        assertEquals("redisbungee", network.get("uri"));
        assertEquals("", network.get("instance-id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "zh"})
    void bundledLanguagesShipTheUri(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(16, data.get("config-version"));
            assertEquals(
                    Map.of("uri", "", "secret", "", "instance-id", "", "nats-credentials-file", ""), network(data));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> network(Map<String, Object> data) {
        return (Map<String, Object>) data.get("network");
    }
}
