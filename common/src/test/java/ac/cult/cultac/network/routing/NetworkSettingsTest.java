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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NetworkSettingsTest {

    private static ConfigManager config(Map<String, String> strings, List<String> natsServers) {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> strings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        Mockito.when(config.getStringListElse(Mockito.eq("network.nats.servers"), Mockito.any()))
                .thenReturn(natsServers);
        return config;
    }

    private static NetworkSettings read(Map<String, String> strings, Map<String, String> env) {
        return NetworkSettings.read(config(strings, List.of("nats://a:4222")), env::get);
    }

    private static final Map<String, String> BLOCK = Map.of(
            "network.transport", " Redis ",
            "network.instance-id", "p3",
            "network.network", "eu",
            "network.secret", "config-secret",
            "network.redis.uri", "redis://:pw@cache:6379/0",
            "network.nats.token", "config-token",
            "network.nats.credentials-file", "/etc/nats.creds",
            "cloud.proxy-id", "edge-1");

    @Test
    void readsTheNetworkBlock() {
        NetworkSettings settings = read(BLOCK, Map.of());
        assertEquals("redis", settings.transport());
        assertEquals("p3", settings.instanceId());
        assertEquals("eu", settings.network());
        assertEquals("config-secret", settings.secret());
        assertEquals("redis://:pw@cache:6379/0", settings.redisUri());
        assertEquals(List.of("nats://a:4222"), settings.natsServers());
        assertEquals("config-token", settings.natsToken());
        assertEquals("/etc/nats.creds", settings.natsCredentialsFile());
        assertEquals("edge-1", settings.cloudProxyId());
    }

    @Test
    void anEmptyBlockIsOff() {
        NetworkSettings settings = NetworkSettings.read(config(Map.of(), List.of()), name -> null);
        assertEquals(NetworkSettings.OFF, settings);
        assertEquals("none", settings.transport());
        assertEquals("default", settings.network());
    }

    @Test
    void aSetEnvironmentVariableWinsAndABlankOneDoesNot() {
        Map<String, String> env = new HashMap<>();
        env.put("CULTAC_NETWORK_TRANSPORT", "nats");
        env.put("CULTAC_NETWORK_INSTANCE_ID", "p9");
        env.put("CULTAC_NETWORK_NETWORK", "us");
        env.put("CULTAC_NETWORK_SECRET", "env-secret");
        env.put("CULTAC_NETWORK_REDIS_URI", "rediss://cache:6380");
        env.put("CULTAC_NETWORK_NATS_SERVERS", "nats://b:4222, nats://c:4222,");
        env.put("CULTAC_NETWORK_NATS_TOKEN", "env-token");
        env.put("CULTAC_NETWORK_NATS_CREDENTIALS_FILE", "/run/nats.creds");
        NetworkSettings settings = read(BLOCK, env);
        assertEquals("nats", settings.transport());
        assertEquals("p9", settings.instanceId());
        assertEquals("us", settings.network());
        assertEquals("env-secret", settings.secret());
        assertEquals("rediss://cache:6380", settings.redisUri());
        assertEquals(List.of("nats://b:4222", "nats://c:4222"), settings.natsServers());
        assertEquals("env-token", settings.natsToken());
        assertEquals("/run/nats.creds", settings.natsCredentialsFile());

        Map<String, String> blank = new HashMap<>();
        for (String name : env.keySet()) blank.put(name, "  ");
        assertEquals(read(BLOCK, Map.of()), read(BLOCK, blank));
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
    void theSecretFallsBackToThePlatformsAndIsNullWithoutOne() {
        byte[] forwarding = "forwarding".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(
                "config-secret".getBytes(StandardCharsets.UTF_8),
                read(BLOCK, Map.of()).resolveSecret(forwarding));
        assertArrayEquals(
                "env-secret".getBytes(StandardCharsets.UTF_8),
                read(BLOCK, Map.of("CULTAC_NETWORK_SECRET", "env-secret")).resolveSecret(forwarding));
        assertArrayEquals(forwarding, NetworkSettings.OFF.resolveSecret(forwarding));
        assertNull(NetworkSettings.OFF.resolveSecret(null));
        assertNull(NetworkSettings.OFF.resolveSecret(new byte[0]));
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
    void aNetworkNameMustBeOnePartOfAChannel() {
        assertNull(NetworkSettings.invalidToken("default"));
        assertNull(NetworkSettings.invalidToken("eu-west_2"));
        for (String bad : List.of("", "eu.west", "eu west", "eu*", "eu>", "eu/west"))
            assertNotNull(NetworkSettings.invalidToken(bad), bad);
    }

    @Test
    void knowsTheFourTransports() {
        for (String name : List.of("none", "redis", "nats", "redisbungee")) {
            assertTrue(new NetworkSettings(name, "", "", "", "", List.of(), "", "", "").knownTransport(), name);
        }
        assertFalse(new NetworkSettings("rabbitmq", "", "", "", "", List.of(), "", "", "").knownTransport());
    }

    @Test
    void channelsAreNamespacedByNetwork() {
        NetworkSettings settings = read(BLOCK, Map.of());
        assertEquals("cultac.eu.p3", settings.channel("p3"));
        assertEquals("cultac.eu.all", settings.channel(NetworkSettings.ALL));
    }

    @Test
    void toStringLeavesTheCredentialsOut() {
        String text = NetworkSettings.read(config(BLOCK, List.of("nats://user:natspw@a:4222")), name -> null)
                .toString();
        assertFalse(text.contains("config-secret"), text);
        assertFalse(text.contains("config-token"), text);
        assertFalse(text.contains(":pw@"), text);
        assertFalse(text.contains("natspw"), text);
        assertTrue(text.contains("redisUri=redis://***@cache:6379/0"), text);
        assertTrue(text.contains("natsServers=[nats://***@a:4222]"), text);
    }

    @Test
    void settingsThatReadAlikeAreEqual() {
        assertEquals(read(BLOCK, Map.of()), read(BLOCK, Map.of()));
        Map<String, String> changed = new HashMap<>(BLOCK);
        changed.put("network.secret", "rotated");
        assertNotEquals(read(BLOCK, Map.of()), read(changed, Map.of()));
    }
}
