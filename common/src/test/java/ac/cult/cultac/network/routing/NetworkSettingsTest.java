package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.routing.NetworkSettings.Nats;
import ac.cult.cultac.network.routing.NetworkSettings.Redis;
import ac.grim.grimac.api.config.ConfigManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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

    private static ConfigManager config(Map<String, Object> values) {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.any())).thenAnswer(invocation -> {
            Object value = values.get(invocation.<String>getArgument(0));
            return value == null ? invocation.getArgument(1) : value.toString();
        });
        Mockito.when(config.getBooleanElse(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> values.get(invocation.<String>getArgument(0)) instanceof Boolean set
                        ? set
                        : invocation.getArgument(1));
        return config;
    }

    private static NetworkSettings read(Map<String, Object> values, Map<String, String> env) {
        return NetworkSettings.read(config(values), env::get);
    }

    private static NetworkSettings redis(String username, String password) {
        return new NetworkSettings(
                NetworkSettings.REDIS, new Redis("cache:6379", username, password, false), null, "", "", "");
    }

    private static NetworkSettings nats(String username, String password, String token) {
        return new NetworkSettings(
                NetworkSettings.NATS, null, new Nats("host:4222", username, password, token, "", false), "", "", "");
    }

    private static NetworkSettings type(String type) {
        return new NetworkSettings(type, null, null, "", "", "");
    }

    private static Map<String, Object> block(String type) {
        Map<String, Object> values = new HashMap<>();
        values.put("network.type", type);
        values.put("network.redis.address", " cache:6380 ");
        values.put("network.redis.username", "default");
        values.put("network.redis.password", "redis-pw");
        values.put("network.redis.ssl", true);
        values.put("network.nats.address", "a:4222, b");
        values.put("network.nats.username", "user");
        values.put("network.nats.password", "nats-pw");
        values.put("network.nats.token", "");
        values.put("network.nats.credentials-file", "/etc/nats.creds");
        values.put("network.nats.ssl", false);
        values.put("network.secret", "config-secret");
        values.put("network.instance-id", "p3");
        values.put("cloud.proxy-id", "edge-1");
        return values;
    }

    @Test
    void readsTheChosenBlockOnly() {
        NetworkSettings settings = read(block("Redis"), Map.of());
        assertEquals(
                new NetworkSettings(
                        NetworkSettings.REDIS,
                        new Redis("cache:6380", "default", "redis-pw", true),
                        Nats.DEFAULT,
                        "config-secret",
                        "p3",
                        "edge-1"),
                settings);
        assertEquals(NetworkSettings.REDIS, settings.transport());

        NetworkSettings nats = read(block("NATS"), Map.of());
        assertEquals(new Nats("a:4222, b", "user", "nats-pw", "", "/etc/nats.creds", false), nats.nats());
        assertEquals(Redis.DEFAULT, nats.redis());
        assertEquals(List.of("nats://a:4222", "nats://b:4222"), nats.nats().servers());
    }

    @Test
    void theUnchosenBlockNeverForcesAReconnect() {
        Map<String, Object> changed = block("nats");
        changed.put("network.redis.password", "rotated");
        changed.put("network.redis.address", "elsewhere:6379");
        assertEquals(read(block("nats"), Map.of()), read(changed, Map.of()));

        Map<String, Object> chosen = block("nats");
        chosen.put("network.nats.password", "rotated");
        assertNotEquals(read(block("nats"), Map.of()), read(chosen, Map.of()));
    }

    @Test
    void anEmptyBlockIsOff() {
        NetworkSettings settings = read(Map.of(), Map.of());
        assertEquals(NetworkSettings.OFF, settings);
        assertEquals(NetworkSettings.NONE, settings.transport());
        assertEquals(NetworkSettings.OFF, type(""));
    }

    @Test
    void aSetEnvironmentVariableWinsAndABlankOneDoesNot() {
        Map<String, String> env = new HashMap<>();
        env.put("CULTAC_NETWORK_TYPE", "redis");
        env.put("CULTAC_NETWORK_SECRET", "env-secret");
        env.put("CULTAC_NETWORK_INSTANCE_ID", "p9");
        env.put("CULTAC_NETWORK_REDIS_ADDRESS", "env-cache:6390");
        env.put("CULTAC_NETWORK_REDIS_USERNAME", "env-user");
        env.put("CULTAC_NETWORK_REDIS_PASSWORD", "env-pw");
        env.put("CULTAC_NETWORK_REDIS_SSL", "false");
        NetworkSettings settings = read(block("nats"), env);
        assertEquals(
                new NetworkSettings(
                        NetworkSettings.REDIS,
                        new Redis("env-cache:6390", "env-user", "env-pw", false),
                        null,
                        "env-secret",
                        "p9",
                        "edge-1"),
                settings);

        Map<String, String> natsEnv = new HashMap<>();
        natsEnv.put("CULTAC_NETWORK_NATS_ADDRESS", "x:1, y:2");
        natsEnv.put("CULTAC_NETWORK_NATS_USERNAME", "env-user");
        natsEnv.put("CULTAC_NETWORK_NATS_PASSWORD", "env-pw");
        natsEnv.put("CULTAC_NETWORK_NATS_TOKEN", "env-token");
        natsEnv.put("CULTAC_NETWORK_NATS_CREDENTIALS_FILE", "/run/nats.creds");
        natsEnv.put("CULTAC_NETWORK_NATS_SSL", "TRUE");
        assertEquals(
                new Nats("x:1, y:2", "env-user", "env-pw", "env-token", "/run/nats.creds", true),
                read(block("nats"), natsEnv).nats());

        Map<String, String> blank = new HashMap<>();
        for (String name : env.keySet()) blank.put(name, "  ");
        for (String name : natsEnv.keySet()) blank.put(name, "");
        assertEquals(read(block("nats"), Map.of()), read(block("nats"), blank));
        assertEquals(read(block("redis"), Map.of()), read(block("redis"), blank));
    }

    @Test
    void theTypeIsCaseInsensitiveAndAnUnknownOneNamesNoTransport() {
        assertEquals(NetworkSettings.NONE, type("").transport());
        assertEquals(NetworkSettings.NONE, type(" None ").transport());
        assertEquals(NetworkSettings.REDIS, type("REDIS").transport());
        assertEquals(NetworkSettings.NATS, type("Nats").transport());
        assertEquals(NetworkSettings.REDISBUNGEE, type(" RedisBungee ").transport());
        for (String unknown : List.of("amqp", "valkey", "redis://cache:6379", "nats,redis"))
            assertNull(type(unknown).transport(), unknown);
    }

    @Test
    void anAddressIsHostAndAnOptionalPort() {
        assertEquals("cache:6379", new Redis("cache", "", "", false).server().toString());
        assertEquals(
                "cache:6380", new Redis(" cache:6380 ", "", "", false).server().toString());
        assertEquals("[::1]:6379", new Redis("[::1]", "", "", false).server().toString());
        assertEquals("::1", new Redis("[::1]:6380", "", "", false).server().host());
        assertEquals(
                List.of("tls://a:4222", "tls://[fe80::1]:4443", "tls://c:1"),
                new Nats("a, [fe80::1]:4443,, c:1 ,", "", "", "", "", true).servers());
        for (String bad : List.of(
                "",
                ":6379",
                "cache:",
                "cache:0",
                "cache:65536",
                "cache:x",
                "::1",
                "[::1",
                "[::1]x",
                "user@cache",
                "cache/0",
                "two words"))
            assertThrows(IllegalArgumentException.class, () -> new Redis(bad, "", "", false).server(), bad);
        assertThrows(IllegalArgumentException.class, () -> new Nats(" , ", "", "", "", "", false).servers());
        assertThrows(IllegalArgumentException.class, () -> new Nats("nats://a:4222", "", "", "", "", false).servers());
    }

    @Test
    void aRedisAddressNamingASchemeOrSeveralServersIsRejectedWithOneMessage() {
        for (String bad : List.of("a:6379,b:6379", "redis://cache:6379", "rediss://:pw@cache")) {
            IllegalArgumentException rejected =
                    assertThrows(IllegalArgumentException.class, () -> new Redis(bad, "", "", false).server(), bad);
            assertTrue(rejected.getMessage().startsWith("network.redis.address"), rejected.getMessage());
            assertFalse(rejected.getMessage().contains("pw"), rejected.getMessage());
        }
    }

    @Test
    void theCredentialIsThePasswordTheTokenOrUserAndPassword() {
        assertEquals("pw", redis("", "pw").credential());
        assertEquals("default:pw", redis("default", "pw").credential());
        assertEquals("default", redis("default", "").credential());
        assertNull(redis("", "").credential());
        assertEquals("token", nats("user", "pw", "token").credential());
        assertEquals("user:pw", nats("user", "pw", "").credential());
        assertNull(nats("", "", "").credential());
        assertNull(type("redisbungee").credential());
        assertNull(NetworkSettings.OFF.credential());
    }

    /** The key build 188's {@code network.uri} derived: from its first user info, decoded, a leading ':' dropped. */
    private static byte[] uriKey(String uri) throws Exception {
        List<String> servers = uri.startsWith("redis") ? List.of(uri) : Arrays.asList(uri.split(","));
        for (String server : servers) {
            String userInfo = new URI(server.strip()).getUserInfo();
            if (userInfo == null) continue;
            String credential = userInfo.startsWith(":") ? userInfo.substring(1) : userInfo;
            if (!credential.isEmpty()) return hmac(credential, "cultac-network-signing");
        }
        return null;
    }

    @Test
    void theDerivedKeyMatchesTheOneTheUriGaveForTheSameCredentials() throws Exception {
        assertArrayEquals(uriKey("redis://:pw@cache:6379/0"), redis("", "pw").resolveSecret(null));
        assertArrayEquals(
                uriKey("rediss://default:pw@cache:6380"), redis("default", "pw").resolveSecret(null));
        assertArrayEquals(
                uriKey("redis://:p%40ss@cache:6379"), redis("", "p@ss").resolveSecret(null));
        assertArrayEquals(
                uriKey("redis://:pa,ss@cache:6379"), redis("", "pa,ss").resolveSecret(null));
        assertArrayEquals(uriKey("redis://pw@cache:6379"), redis("", "pw").resolveSecret(null));
        assertArrayEquals(uriKey("redis://u:p%3Aw@cache"), redis("u", "p:w").resolveSecret(null));
        assertArrayEquals(
                uriKey("nats://t0ken@a:4222,nats://b:4222"),
                nats("", "", "t0ken").resolveSecret(null));
        assertArrayEquals(
                uriKey("nats://a%2Fb%23c%25d%2Ce%2Bf%20g@a:4222"),
                nats("", "", "a/b#c%d,e+f g").resolveSecret(null));
        assertArrayEquals(
                uriKey("tls://a:4222, tls://user:pw@b:4443"),
                nats("user", "pw", "").resolveSecret(null));
        assertArrayEquals(uriKey("nats://:pw@a:4222"), nats("", "pw", "").resolveSecret(null));
        assertNull(uriKey("redis://cache:6379"));
        assertNull(redis("", "").resolveSecret(null));
    }

    @Test
    void theSigningKeyIsTheSecretThenThePlatformsThenOneDerivedFromTheCredential() throws Exception {
        NetworkSettings all = new NetworkSettings(
                NetworkSettings.REDIS, new Redis("cache", "", "pw", false), null, "config-secret", "", "");
        assertArrayEquals("config-secret".getBytes(StandardCharsets.UTF_8), all.resolveSecret(FORWARDING));

        NetworkSettings noSecret = redis("", "pw");
        assertArrayEquals(FORWARDING, noSecret.resolveSecret(FORWARDING));

        byte[] derived = hmac("pw", "cultac-network-signing");
        assertArrayEquals(derived, noSecret.resolveSecret(null));
        assertArrayEquals(derived, noSecret.resolveSecret(new byte[0]));
        assertArrayEquals(derived, nats("", "", "pw").resolveSecret(null));
        assertFalse(Arrays.equals(derived, redis("", "other").resolveSecret(null)));
    }

    @Test
    void withoutASecretAPlatformSecretOrACredentialThereIsNoKey() {
        assertNull(NetworkSettings.OFF.resolveSecret(null));
        assertNull(redis("", "").resolveSecret(null));
        assertNull(type("redisbungee").resolveSecret(new byte[0]));
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
        assertEquals("p3", read(block("redis"), Map.of()).resolveInstanceId(() -> "rb-1", noAddress));

        Map<String, Object> unnamed = block("redis");
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
                        NetworkSettings.NATS,
                        null,
                        new Nats("a:4222, b:4443", "natsuser", "natspw", "tok3n", "/etc/nats.creds", true),
                        "config-secret",
                        "p3",
                        "")
                .toString();
        for (String secret : List.of("config-secret", "natsuser", "natspw", "tok3n"))
            assertFalse(text.contains(secret), text);
        assertTrue(text.contains("address=a:4222, b:4443"), text);
        assertTrue(text.contains("secret=<set>"), text);

        String redis = read(block("redis"), Map.of()).toString();
        assertFalse(redis.contains("redis-pw"), redis);
        assertFalse(redis.contains("nats"), redis);
        assertTrue(redis.contains("address=cache:6380"), redis);
    }

    @Test
    void settingsThatReadAlikeAreEqual() {
        assertEquals(read(block("redis"), Map.of()), read(block("redis"), Map.of()));
        Map<String, Object> changed = block("redis");
        changed.put("network.secret", "rotated");
        assertNotEquals(read(block("redis"), Map.of()), read(changed, Map.of()));
    }

    private static byte[] hmac(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
}
