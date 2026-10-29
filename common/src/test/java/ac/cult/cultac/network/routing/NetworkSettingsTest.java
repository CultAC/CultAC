package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.grim.grimac.api.config.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NetworkSettingsTest {
    private static final byte[] FORWARDING = "forwarding".getBytes(StandardCharsets.UTF_8);

    private static ConfigManager config(Map<String, String> strings) {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> strings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        return config;
    }

    private static NetworkSettings read(Map<String, String> strings, Map<String, String> env) {
        return NetworkSettings.read(config(strings), env::get);
    }

    private static NetworkSettings uri(String uri) {
        return new NetworkSettings(uri, "", "", "", "");
    }

    private static final Map<String, String> BLOCK = Map.of(
            "network.uri", " redis://:pw@cache:6379/0 ",
            "network.secret", "config-secret",
            "network.instance-id", "p3",
            "network.nats-credentials-file", "/etc/nats.creds",
            "cloud.proxy-id", "edge-1");

    @Test
    void readsTheNetworkBlock() {
        NetworkSettings settings = read(BLOCK, Map.of());
        assertEquals(
                new NetworkSettings("redis://:pw@cache:6379/0", "config-secret", "p3", "/etc/nats.creds", "edge-1"),
                settings);
        assertEquals(NetworkSettings.REDIS, settings.transport());
    }

    @Test
    void anEmptyBlockIsOff() {
        NetworkSettings settings = read(Map.of(), Map.of());
        assertEquals(NetworkSettings.OFF, settings);
        assertEquals(NetworkSettings.NONE, settings.transport());
    }

    @Test
    void aSetEnvironmentVariableWinsAndABlankOneDoesNot() {
        Map<String, String> env = new HashMap<>();
        env.put("CULTAC_NETWORK_URI", "nats://b:4222, nats://c:4222,");
        env.put("CULTAC_NETWORK_SECRET", "env-secret");
        env.put("CULTAC_NETWORK_INSTANCE_ID", "p9");
        env.put("CULTAC_NETWORK_NATS_CREDENTIALS_FILE", "/run/nats.creds");
        NetworkSettings settings = read(BLOCK, env);
        assertEquals(
                new NetworkSettings("nats://b:4222, nats://c:4222,", "env-secret", "p9", "/run/nats.creds", "edge-1"),
                settings);
        assertEquals(NetworkSettings.NATS, settings.transport());
        assertEquals(List.of("nats://b:4222", "nats://c:4222"), settings.servers());

        Map<String, String> blank = new HashMap<>();
        for (String name : env.keySet()) blank.put(name, "  ");
        assertEquals(read(BLOCK, Map.of()), read(BLOCK, blank));
    }

    @Test
    void theSchemePicksTheTransport() {
        assertEquals(NetworkSettings.NONE, uri("").transport());
        assertEquals(NetworkSettings.REDIS, uri("redis://cache:6379").transport());
        assertEquals(NetworkSettings.REDIS, uri("REDISS://:pw@cache:6380/0").transport());
        assertEquals(NetworkSettings.NATS, uri("nats://token@host:4222").transport());
        assertEquals(NetworkSettings.NATS, uri("tls://a:4443").transport());
        assertEquals(NetworkSettings.NATS, uri("nats://a:4222, tls://b:4443").transport());
        assertEquals(NetworkSettings.REDISBUNGEE, uri("redisbungee").transport());
        assertEquals(NetworkSettings.REDISBUNGEE, uri(" RedisBungee ").transport());
        assertEquals("rediss", uri("rediss://cache").scheme());
        assertEquals("", uri("").scheme());
    }

    @Test
    void anUnknownSchemeOrAMixOfThemNamesNoTransport() {
        for (String unknown : List.of(
                "amqp://broker:5672",
                "cache:6379",
                "localhost",
                "nats://a:4222,redis://b:6379",
                "redis://a:6379,redis://b:6379",
                "http://cache:6379")) {
            assertNull(uri(unknown).transport(), unknown);
        }
        assertEquals("amqp", uri("amqp://broker:5672").scheme());
        assertEquals("", uri("cache:6379").scheme());
    }

    @Test
    void theCredentialIsTheFirstUserInfoInTheUri() {
        assertEquals("pw", uri("redis://:pw@cache:6379").credential());
        assertEquals("default:pw", uri("rediss://default:pw@cache:6380").credential());
        assertEquals("p@ss", uri("redis://:p%40ss@cache:6379").credential());
        assertEquals("token", uri("nats://token@host:4222").credential());
        assertEquals("second", uri("nats://a:4222,nats://second@b:4222").credential());
        assertNull(uri("redis://cache:6379").credential());
        assertNull(uri("redisbungee").credential());
        assertNull(NetworkSettings.OFF.credential());
    }

    @Test
    void theSigningKeyIsTheSecretThenThePlatformsThenOneDerivedFromTheCredential() throws Exception {
        NetworkSettings all = new NetworkSettings("redis://:pw@cache:6379", "config-secret", "", "", "");
        assertArrayEquals("config-secret".getBytes(StandardCharsets.UTF_8), all.resolveSecret(FORWARDING));

        NetworkSettings noSecret = uri("redis://:pw@cache:6379");
        assertArrayEquals(FORWARDING, noSecret.resolveSecret(FORWARDING));

        byte[] derived = hmac("pw", "cultac-network-signing");
        assertArrayEquals(derived, noSecret.resolveSecret(null));
        assertArrayEquals(derived, noSecret.resolveSecret(new byte[0]));
        assertArrayEquals(derived, uri("nats://pw@host:4222").resolveSecret(null));
        assertFalse(java.util.Arrays.equals(
                derived, uri("redis://:other@cache:6379").resolveSecret(null)));
    }

    @Test
    void withoutASecretAPlatformSecretOrACredentialThereIsNoKey() {
        assertNull(NetworkSettings.OFF.resolveSecret(null));
        assertNull(uri("redis://cache:6379").resolveSecret(null));
        assertNull(uri("redisbungee").resolveSecret(new byte[0]));
    }

    @Test
    void channelsAreNamespacedByTheSigningKey() throws Exception {
        byte[] key = "a key".getBytes(StandardCharsets.UTF_8);
        String namespace = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(key))
                .substring(0, 8);
        assertEquals("cultac." + namespace + ".p3", NetworkSettings.channel(key, "p3"));
        assertEquals("cultac." + namespace + ".all", NetworkSettings.channel(key, NetworkSettings.ALL));
        assertNotEquals(
                NetworkSettings.channel(key, "p3"),
                NetworkSettings.channel("another key".getBytes(StandardCharsets.UTF_8), "p3"));
        assertTrue(NetworkSettings.channel(null, "p3").matches("cultac\\.[0-9a-f]{8}\\.p3"));
    }

    @Test
    void theInstanceIdFallsBackToRedisBungeeThenTheCloudProxyIdThenTheAddress() {
        Supplier<String> noAddress = () -> {
            throw new AssertionError("address looked up");
        };
        assertEquals("p3", read(BLOCK, Map.of()).resolveInstanceId(() -> "rb-1", noAddress));

        Map<String, String> unnamed = new HashMap<>(BLOCK);
        unnamed.remove("network.instance-id");
        assertEquals("rb-1", read(unnamed, Map.of()).resolveInstanceId(() -> "rb-1", noAddress));
        assertEquals("edge-1", read(unnamed, Map.of()).resolveInstanceId(() -> null, noAddress));

        unnamed.remove("cloud.proxy-id");
        assertEquals(
                "edge-env",
                read(unnamed, Map.of("CULTAC_PROXY_ID", "edge-env")).resolveInstanceId(() -> " ", noAddress));
        assertEquals("host-25577", read(unnamed, Map.of()).resolveInstanceId(() -> null, () -> "host:25577"));
        assertEquals(
                "mc-example-com-25577",
                read(unnamed, Map.of()).resolveInstanceId(() -> null, () -> "mc.example.com:25577"));
    }

    @Test
    void anInstanceIdMustNameAChannel() {
        assertNull(NetworkSettings.invalidInstanceId("p3"));
        assertNull(NetworkSettings.invalidInstanceId("proxy_1"));
        assertNull(NetworkSettings.invalidInstanceId("edge-1"));
        for (String bad : List.of("", "all", "ALL", "p 3", "p*", "p>", "p.3", "host.example:25577", "p\t3", "é"))
            assertNotNull(NetworkSettings.invalidInstanceId(bad), bad);
    }

    @Test
    void toStringLeavesTheCredentialsOut() {
        String text = new NetworkSettings(
                        "nats://user:natspw@a:4222, tls://tok3n@b:4443", "config-secret", "p3", "/etc/nats.creds", "")
                .toString();
        assertFalse(text.contains("config-secret"), text);
        assertFalse(text.contains("natspw"), text);
        assertFalse(text.contains("tok3n"), text);
        assertTrue(text.contains("uri=nats://***@a:4222,tls://***@b:4443"), text);
        assertTrue(text.contains("secret=<set>"), text);

        assertTrue(uri("redis://:pw@cache:6379/0").toString().contains("uri=redis://***@cache:6379/0"));
        assertTrue(uri("redisbungee").toString().contains("uri=redisbungee"));
    }

    @Test
    void toStringHidesTheCredentialsOfAUrlThatDoesNotParse() {
        String text = uri("nats://user:pass host:4222, nats://user:p@ss@, localhost:4222")
                .toString();
        assertFalse(text.contains("pass"), text);
        assertFalse(text.contains("p@ss"), text);
        assertTrue(text.contains("uri=" + Redaction.HIDDEN + ",nats://***@,localhost:4222"), text);
    }

    @Test
    void settingsThatReadAlikeAreEqual() {
        assertEquals(read(BLOCK, Map.of()), read(BLOCK, Map.of()));
        Map<String, String> changed = new HashMap<>(BLOCK);
        changed.put("network.secret", "rotated");
        assertNotEquals(read(BLOCK, Map.of()), read(changed, Map.of()));
    }

    private static byte[] hmac(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
}
