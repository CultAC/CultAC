package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

class NetworkUriUpgradeTest {
    private static final Map<String, Object> REDIS_DEFAULTS =
            Map.of("address", "localhost:6379", "username", "", "password", "", "ssl", false);
    private static final Map<String, Object> NATS_DEFAULTS = Map.of(
            "address", "localhost:4222",
            "username", "",
            "password", "",
            "token", "",
            "credentials-file", "",
            "ssl", false);

    @TempDir
    Path directory;

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    /** A config at {@code version} whose network block is {@code block}, indented under {@code network:}. */
    private Map<String, Object> upgrade(int version, String block) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(
                file, "network:\n" + block.indent(2) + "config-flavor: V2\nconfig-version: " + version + "\n");
        var result = new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.mainConfig());
        assertTrue(result.migrated);
        Map<String, Object> data = new Yaml().load(Files.readString(file));
        assertEquals(17, data.get("config-version"));
        Map<String, Object> network = network(data);
        for (String old : new String[] {"transport", "network", "uri", "nats-credentials-file"})
            assertFalse(network.containsKey(old), old + " in " + network);
        return network;
    }

    private Map<String, Object> upgrade15(String block) throws Exception {
        return upgrade(15, block);
    }

    private Map<String, Object> upgrade16(String block) throws Exception {
        return upgrade(16, block);
    }

    /** Deletes the config and its backups, for the next upgrade in the same test. */
    private void clear() throws Exception {
        Files.delete(directory.resolve("config.yml"));
        try (var backups = Files.list(directory)) {
            for (Path backup : backups.toList()) Files.delete(backup);
        }
    }

    private static Map<String, Object> redis(String address, String username, String password, boolean ssl) {
        return Map.of("address", address, "username", username, "password", password, "ssl", ssl);
    }

    private static Map<String, Object> nats(
            String address, String username, String password, String token, String credentialsFile, boolean ssl) {
        Map<String, Object> nats = new LinkedHashMap<>();
        nats.put("address", address);
        nats.put("username", username);
        nats.put("password", password);
        nats.put("token", token);
        nats.put("credentials-file", credentialsFile);
        nats.put("ssl", ssl);
        return nats;
    }

    private static void assertNone(Map<String, Object> network) {
        assertEquals("none", network.get("type"));
        assertEquals(REDIS_DEFAULTS, network.get("redis"));
        assertEquals(NATS_DEFAULTS, network.get("nats"));
    }

    @Test
    void v15NoTransportIsNoneAndKeepsTheRest() throws Exception {
        Map<String, Object> network = upgrade15("""
                transport: none
                instance-id: p3
                network: default
                secret: shared
                redis:
                  uri: redis://cache:6379
                """);
        assertNone(network);
        assertEquals("p3", network.get("instance-id"));
        assertEquals("shared", network.get("secret"));
    }

    @Test
    void v15RedisCarriesItsUriIntoTheRedisBlock() throws Exception {
        Map<String, Object> network = upgrade15("""
                transport: Redis
                instance-id: p3
                network: eu
                secret: ""
                redis:
                  uri: rediss://:pw@cache:6380/2
                nats:
                  token: unused
                """);
        assertEquals("redis", network.get("type"));
        assertEquals(redis("cache:6380", "", "pw", true), network.get("redis"));
        assertEquals(NATS_DEFAULTS, network.get("nats"));
        assertEquals("p3", network.get("instance-id"));
        assertFalse(network.containsKey("secret"), network.toString());
    }

    @Test
    void v15NatsGivesItsServersTokenAndCredentialsFile() throws Exception {
        Map<String, Object> network = upgrade15("""
                transport: nats
                instance-id: lobby-1
                secret: shared
                nats:
                  servers:
                    - nats://a:4222
                    - tls://user:pw@b:4443
                  token: "a/b#c%d,e+f g"
                  credentials-file: /etc/cultac/nats.creds
                """);
        assertEquals("nats", network.get("type"));
        assertEquals(
                nats("a:4222, b:4443", "", "", "a/b#c%d,e+f g", "/etc/cultac/nats.creds", true), network.get("nats"));
        assertEquals(REDIS_DEFAULTS, network.get("redis"));
        assertEquals("lobby-1", network.get("instance-id"));
        assertEquals("shared", network.get("secret"));
    }

    @Test
    void v15RedisBungeeStaysRedisBungee() throws Exception {
        Map<String, Object> network = upgrade15("""
                transport: redisbungee
                instance-id: ""
                redis:
                  uri: redis://ignored:6379
                """);
        assertEquals("redisbungee", network.get("type"));
        assertEquals(REDIS_DEFAULTS, network.get("redis"));
        assertEquals("", network.get("instance-id"));
    }

    @Test
    void anEmptyUriIsNone() throws Exception {
        Map<String, Object> network = upgrade16("""
                uri: ""
                secret: kept
                instance-id: p7
                nats-credentials-file: ""
                """);
        assertNone(network);
        assertEquals("kept", network.get("secret"));
        assertEquals("p7", network.get("instance-id"));
    }

    @Test
    void anEmptySecretIsDroppedAndASetOneKept() throws Exception {
        Map<String, Object> blank = upgrade16("""
                uri: redis://:pw@cache:6379
                secret: "  "
                """);
        assertFalse(blank.containsKey("secret"), blank.toString());
        clear();
        assertFalse(upgrade16("uri: \"\"\n").containsKey("secret"));
        clear();
        assertEquals(
                "kept", upgrade16("uri: redis://:pw@cache:6379\nsecret: kept\n").get("secret"));
    }

    @Test
    void redisBungeeStaysItsType() throws Exception {
        assertEquals("redisbungee", upgrade16("uri: RedisBungee\n").get("type"));
    }

    @Test
    void aRedisUriBecomesTheRedisBlockWithoutItsDatabase() throws Exception {
        Map<String, Object> network = upgrade16("""
                uri: redis://cache/3
                secret: s
                instance-id: p3
                """);
        assertEquals("redis", network.get("type"));
        assertEquals(redis("cache:6379", "", "", false), network.get("redis"));
        assertEquals(NATS_DEFAULTS, network.get("nats"));
        assertEquals("s", network.get("secret"));
        assertEquals("p3", network.get("instance-id"));
    }

    @Test
    void aRedissUriWithUserAndEncodedPasswordIsDecodedAndSsl() throws Exception {
        Map<String, Object> network = upgrade16("uri: \"rediss://default:p%40ss%2Bw0rd+x@cache:6380/0\"\n");
        assertEquals(redis("cache:6380", "default", "p@ss+w0rd+x", true), network.get("redis"));
    }

    @Test
    void aCommaInARedisPasswordStaysInThePassword() throws Exception {
        Map<String, Object> network = upgrade16("uri: \"redis://:pa,ss@cache:6379\"\n");
        assertEquals("redis", network.get("type"));
        assertEquals(redis("cache:6379", "", "pa,ss", false), network.get("redis"));
    }

    @Test
    void aPasswordOfSpacesSurvivesTheMigration() throws Exception {
        assertEquals(
                redis("cache:6379", "", "  ", false),
                upgrade16("uri: \"redis://:%20%20@cache:6379\"\n").get("redis"));
    }

    @Test
    void anIpv6RedisHostKeepsItsBrackets() throws Exception {
        assertEquals(
                redis("[::1]:6390", "", "", false),
                upgrade16("uri: \"redis://[::1]:6390\"\n").get("redis"));
    }

    @Test
    void natsServersJoinWithTheTokenOfTheFirstUserInfo() throws Exception {
        Map<String, Object> network = upgrade16("""
                uri: "nats://a, nats://a%2Fb%23c%25d%2Ce%2Bf%20g@b:4223, nats://other@c:4224"
                nats-credentials-file: /etc/nats.creds
                """);
        assertEquals("nats", network.get("type"));
        assertEquals(
                nats("a:4222, b:4223, c:4224", "", "", "a/b#c%d,e+f g", "/etc/nats.creds", false), network.get("nats"));
        assertEquals(REDIS_DEFAULTS, network.get("redis"));
    }

    @Test
    void natsUserInfoWithAColonIsUserAndPasswordAndAnyTlsServerMeansSsl() throws Exception {
        Map<String, Object> network = upgrade16("uri: \"NATS://a:4222,tls://us%3Aer:p%40w@b:4443\"\n");
        assertEquals(nats("a:4222, b:4443", "us:er", "p@w", "", "", true), network.get("nats"));
    }

    @Test
    void anUnreadableUriIsNoneWithOneWarningThatHidesIt() throws Exception {
        for (String uri : List.of(
                "amqp://user:hunter22@broker:5672",
                "cache:6379",
                "nats://a:4222,redis://b:6379",
                "redis://a:6379,redis://b:6379",
                "redis://:hunter22 two@cache",
                "redis:///0")) {
            try (Captured warnings = new Captured()) {
                Map<String, Object> network = upgrade16("uri: \"" + uri + "\"\nsecret: kept\n");
                assertNone(network);
                assertEquals("kept", network.get("secret"));
                assertEquals(1, warnings.messages.size(), uri + ": " + warnings.messages);
                String warning = warnings.messages.getFirst();
                assertTrue(warning.contains("set the network block by hand"), warning);
                assertFalse(warning.contains("hunter22"), warning);
            }
            clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "zh"})
    void bundledLanguagesShipTheNetworkBlockWithoutASecret(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/config/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(17, data.get("config-version"));
            assertEquals(
                    Map.of("type", "none", "redis", REDIS_DEFAULTS, "nats", NATS_DEFAULTS, "instance-id", ""),
                    network(data));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> network(Map<String, Object> data) {
        return (Map<String, Object>) data.get("network");
    }

    /** The plugin log's warnings, until closed. */
    private static final class Captured extends Handler implements AutoCloseable {
        final List<String> messages = new CopyOnWriteArrayList<>();

        Captured() {
            LogUtil.getLogger().addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING && record.getMessage().contains("network"))
                messages.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            LogUtil.getLogger().removeHandler(this);
        }
    }
}
