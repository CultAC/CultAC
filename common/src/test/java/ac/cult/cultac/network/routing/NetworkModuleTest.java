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
import java.util.Collection;
import java.util.List;
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
    void withoutAPlatformThereIsNoService() {
        NetworkModule.start(REDISBUNGEE);
        assertNull(NetworkModule.service());
        assertFalse(NetworkRouting.current().enabled());
    }
}
