package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NetworkModuleTest {
    private static final NetworkSettings REDISBUNGEE = new NetworkSettings("redisbungee", "", "p3", "", "");

    private final FakeBroker broker = new FakeBroker();

    /** A proxy with a forwarding secret, whose RedisBungee transport is the fake broker's. */
    private Players proxy() {
        return new Players()
                .proxy(null)
                .secret("forwarding secret".getBytes(StandardCharsets.UTF_8))
                .redisBungee(broker::transport);
    }

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    @AfterEach
    void tearDown() {
        NetworkModule.stop();
        NetworkModule.platform(null);
    }

    @Test
    void aRunningTransportIsTheCurrentRoutingUntilStop() {
        NetworkModule.platform(proxy());
        NetworkModule.start(REDISBUNGEE);

        NetworkService service = NetworkModule.service();
        assertNotNull(service);
        assertSame(service, NetworkRouting.current());
        assertTrue(NetworkRouting.current().enabled());
        assertEquals("p3", NetworkRouting.current().instanceId());

        NetworkModule.stop();
        assertFalse(NetworkRouting.current().enabled());
        assertEquals("local", NetworkRouting.current().instanceId());
    }

    @Test
    void aReloadReconnectsOnlyWhenTheBlockChanged() {
        NetworkModule.platform(proxy());
        NetworkModule.start(REDISBUNGEE);
        NetworkService first = NetworkModule.service();

        NetworkModule.configReloaded(new NetworkSettings("redisbungee", "", "p3", "", ""));
        assertSame(first, NetworkModule.service());

        NetworkModule.configReloaded(new NetworkSettings("redisbungee", "", "p4", "", ""));
        assertNotSame(first, NetworkModule.service());
        assertFalse(first.enabled());
        assertEquals("p4", NetworkRouting.current().instanceId());
    }

    @Test
    void startHooksRunOnEveryServiceBeforeItSubscribes() {
        List<NetworkService> hooked = new ArrayList<>();
        NetworkModule.onStart(service -> {
            assertFalse(service.enabled());
            hooked.add(service);
        });
        NetworkModule.platform(proxy());
        NetworkModule.start(REDISBUNGEE);
        NetworkModule.configReloaded(new NetworkSettings("redisbungee", "", "p4", "", ""));

        assertEquals(2, hooked.size());
        assertSame(NetworkModule.service(), hooked.get(1));
    }

    @Test
    void withoutATransportTheBusStillRunsAndRoutingStaysLocal() {
        NetworkModule.platform(proxy());
        NetworkModule.start(NetworkSettings.OFF);
        NetworkService service = NetworkModule.service();
        assertNotNull(service);
        assertFalse(service.enabled());
        assertFalse(NetworkRouting.current().enabled());
        assertTrue(service.sendLocal("probe", new JsonObject()));
    }

    @Test
    void anUnknownSchemeStaysLocalWithOneErrorNamingTheAcceptedSchemes() {
        NetworkModule.platform(proxy());
        assertStaysLocal(new NetworkSettings("amqp://broker:5672", "", "p3", "", ""), NetworkSettings.ACCEPTED);
        assertStaysLocal(new NetworkSettings("cache:6379", "", "p3", "", ""), "names no scheme");
        assertStaysLocal(
                new NetworkSettings("nats://a:4222,redis://b:6379", "", "p3", "", ""), NetworkSettings.ACCEPTED);
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void withoutASigningKeyRoutingStaysLocalWithOneErrorAskingForTheSecret() {
        Players proxy = proxy().secret(null);
        NetworkModule.platform(proxy);
        assertStaysLocal(REDISBUNGEE, "set network.secret");
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void anInstanceIdNamingTheBroadcastChannelStaysLocal() {
        NetworkModule.platform(proxy());
        NetworkModule.start(new NetworkSettings("redisbungee", "", "all", "", ""));
        assertFalse(NetworkRouting.current().enabled());
    }

    @Test
    void anInstanceIdNoChannelCanCarryStaysLocalWithOneErrorNamingTheKey() {
        NetworkModule.platform(proxy());
        for (String uri : List.of("redisbungee", "redis://cache:6379", "nats://host:4222")) {
            for (String instance : List.of("p.3", "p 3", "p*", "p>", "all")) {
                assertStaysLocal(new NetworkSettings(uri, "", instance, "", ""), "network.instance-id");
            }
        }
        assertTrue(broker.published.isEmpty());
    }

    private static void assertStaysLocal(NetworkSettings settings, String key) {
        try (CapturedLog errors = new CapturedLog(Level.SEVERE, "Network routing off")) {
            NetworkModule.start(settings);
            assertFalse(NetworkRouting.current().enabled(), settings.toString());
            assertEquals("none", NetworkModule.service().transportName());
            assertEquals(1, errors.messages().size(), errors.messages().toString());
            assertTrue(
                    errors.messages().getFirst().contains(key),
                    errors.messages().getFirst());
        } finally {
            NetworkModule.stop();
        }
    }

    @Test
    void withoutAPlatformThereIsNoService() {
        NetworkModule.start(REDISBUNGEE);
        assertNull(NetworkModule.service());
        assertFalse(NetworkRouting.current().enabled());
    }
}
