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
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NetworkModuleTest {
    private static final NetworkSettings REDISBUNGEE =
            new NetworkSettings("redisbungee", "p3", "default", "", "", List.of(), "", "", "");

    private final FakeBroker broker = new FakeBroker();

    /** A proxy whose RedisBungee transport is the fake broker's. */
    private final class Proxy implements NetworkPlatform {
        byte[] forwardingSecret = "forwarding secret".getBytes(StandardCharsets.UTF_8);

        @Override
        public Collection<OnlinePlayer> onlinePlayers() {
            return List.of();
        }

        @Override
        public String address() {
            return "proxy:25577";
        }

        @Override
        public boolean proxy() {
            return true;
        }

        @Override
        public byte @Nullable [] defaultSecret() {
            return forwardingSecret;
        }

        @Override
        public MessageTransport redisBungee() {
            return broker.transport();
        }
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
        NetworkModule.platform(new Proxy());
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
        NetworkModule.platform(new Proxy());
        NetworkModule.start(REDISBUNGEE);
        NetworkService first = NetworkModule.service();

        NetworkModule.configReloaded(
                new NetworkSettings("redisbungee", "p3", "default", "", "", List.of(), "", "", ""));
        assertSame(first, NetworkModule.service());

        NetworkModule.configReloaded(
                new NetworkSettings("redisbungee", "p4", "default", "", "", List.of(), "", "", ""));
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
        NetworkModule.platform(new Proxy());
        NetworkModule.start(REDISBUNGEE);
        NetworkModule.configReloaded(
                new NetworkSettings("redisbungee", "p4", "default", "", "", List.of(), "", "", ""));

        assertEquals(2, hooked.size());
        assertSame(NetworkModule.service(), hooked.get(1));
    }

    @Test
    void withoutATransportTheBusStillRunsAndRoutingStaysLocal() {
        NetworkModule.platform(new Proxy());
        NetworkModule.start(NetworkSettings.OFF);
        NetworkService service = NetworkModule.service();
        assertNotNull(service);
        assertFalse(service.enabled());
        assertFalse(NetworkRouting.current().enabled());
        assertTrue(service.sendLocal("probe", new JsonObject()));
    }

    @Test
    void anUnknownTransportOrAMissingSecretStaysLocal() {
        Proxy proxy = new Proxy();
        NetworkModule.platform(proxy);
        NetworkModule.start(new NetworkSettings("rabbitmq", "p3", "default", "", "", List.of(), "", "", ""));
        assertFalse(NetworkRouting.current().enabled());

        NetworkModule.stop();
        proxy.forwardingSecret = null;
        NetworkModule.start(REDISBUNGEE);
        assertFalse(NetworkRouting.current().enabled());
        assertTrue(broker.published.isEmpty());
    }

    @Test
    void anInstanceIdNamingTheBroadcastChannelStaysLocal() {
        NetworkModule.platform(new Proxy());
        NetworkModule.start(new NetworkSettings("redisbungee", "all", "default", "", "", List.of(), "", "", ""));
        assertFalse(NetworkRouting.current().enabled());
    }

    @Test
    void aNetworkOrInstanceIdNoChannelCanCarryStaysLocalWithOneErrorNamingTheKey() {
        NetworkModule.platform(new Proxy());
        for (String transport : List.of("redisbungee", "redis", "nats")) {
            for (String network : List.of("eu.west", "eu west", "eu*", "eu>")) {
                assertStaysLocal(
                        new NetworkSettings(
                                transport,
                                "p3",
                                network,
                                "",
                                "redis://cache:6379",
                                List.of("nats://host:4222"),
                                "",
                                "",
                                ""),
                        "network.network");
            }
            for (String instance : List.of("p.3", "p 3", "p*", "p>", "all")) {
                assertStaysLocal(
                        new NetworkSettings(
                                transport,
                                instance,
                                "default",
                                "",
                                "redis://cache:6379",
                                List.of("nats://host:4222"),
                                "",
                                "",
                                ""),
                        "network.instance-id");
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
