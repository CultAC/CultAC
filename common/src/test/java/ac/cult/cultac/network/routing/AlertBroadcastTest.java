package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.alerts.AlertKind;
import ac.cult.cultac.manager.alerts.AlertNetwork;
import ac.cult.cultac.manager.alerts.AlertSubject;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Two instances over one broker, each with its alert network attached the way the network module attaches it. */
class AlertBroadcastTest {
    private static final byte[] KEY = "a shared network secret of some length".getBytes(StandardCharsets.UTF_8);
    private static final NetworkSettings SETTINGS =
            new NetworkSettings("redis", "", "default", "", "", List.of(), "", "", "");
    private static final AlertSubject BOB = new AlertSubject(UUID.randomUUID(), "Bob", "pvp");

    private final FakeBroker broker = new FakeBroker();
    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    private record Received(
            AlertKind kind, Component component, @Nullable String server, boolean afterVerbose) {}

    private final class Instance implements AlertNetwork.Staff {
        final List<Received> received = new CopyOnWriteArrayList<>();
        final AtomicBoolean wantsVerbose = new AtomicBoolean();
        final NetworkService service;
        final AlertNetwork alerts;

        Instance(String id) {
            NetworkPlatform platform = new NetworkPlatform() {
                @Override
                public Collection<OnlinePlayer> onlinePlayers() {
                    return List.of();
                }

                @Override
                public String address() {
                    return "test:1";
                }
            };
            service = new NetworkService(SETTINGS, platform, null, id, KEY, broker.transport(), clock::get, null);
            alerts = new AlertNetwork(() -> service, this, () -> true, clock::get);
            alerts.attach(service);
            service.start();
        }

        @Override
        public void received(AlertKind kind, Component component, @Nullable String server, boolean afterVerbose) {
            received.add(new Received(kind, component, server, afterVerbose));
        }

        @Override
        public boolean wantGlobalVerbose() {
            return wantsVerbose.get();
        }
    }

    @Test
    void anAlertRaisedOnOneInstanceReachesTheOtherWithItsServer() {
        Instance p3 = new Instance("p3");
        Instance p7 = new Instance("p7");
        Component flag = Component.text("Bob failed Reach", NamedTextColor.RED);

        assertTrue(p3.alerts.publish(AlertKind.ALERT, BOB, flag, false));
        assertTrue(p3.alerts.publish(AlertKind.BRAND, BOB, Component.text("lunar"), false));

        assertEquals(
                List.of(
                        new Received(AlertKind.ALERT, flag, "pvp", false),
                        new Received(AlertKind.BRAND, Component.text("lunar"), "pvp", false)),
                p7.received);
        assertTrue(p3.received.isEmpty());
    }

    @Test
    void verboseCrossesOnlyWhileAPeerFlagsGlobalVerbose() {
        Instance p3 = new Instance("p3");
        Instance p7 = new Instance("p7");

        assertFalse(p3.alerts.publish(AlertKind.VERBOSE, BOB, Component.text("v"), false));

        p7.wantsVerbose.set(true);
        p7.alerts.demandChanged();
        assertFalse(p3.alerts.verboseWanted());
        clock.addAndGet(1_000);
        assertTrue(p3.alerts.verboseWanted());
        assertTrue(p3.alerts.publish(AlertKind.VERBOSE, BOB, Component.text("v"), false));
        assertTrue(p3.alerts.publish(AlertKind.ALERT, BOB, Component.text("a"), true));
        assertEquals(
                List.of(
                        new Received(AlertKind.VERBOSE, Component.text("v"), "pvp", false),
                        new Received(AlertKind.ALERT, Component.text("a"), "pvp", true)),
                p7.received);

        p7.wantsVerbose.set(false);
        p7.alerts.demandChanged();
        clock.addAndGet(1_000);
        assertFalse(p3.alerts.verboseWanted());
    }

    @Test
    void anInstanceWithoutATransportSendsNothing() {
        NetworkService local = new NetworkService(
                SETTINGS,
                new NetworkPlatform() {
                    @Override
                    public Collection<OnlinePlayer> onlinePlayers() {
                        return List.of();
                    }

                    @Override
                    public String address() {
                        return "test:1";
                    }
                },
                null,
                "p3",
                KEY,
                null,
                clock::get,
                null);
        Instance p7 = new Instance("p7");
        AlertNetwork alerts = new AlertNetwork(() -> local, p7, () -> true, clock::get);
        alerts.attach(local);
        local.start();

        assertFalse(alerts.running());
        assertFalse(alerts.publish(AlertKind.ALERT, BOB, Component.text("a"), false));
        assertTrue(p7.received.isEmpty());
    }
}
