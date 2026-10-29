package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.alerts.AlertKind;
import ac.cult.cultac.manager.alerts.AlertNetwork;
import ac.cult.cultac.manager.alerts.AlertSubject;
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
    private static final AlertSubject BOB = new AlertSubject(UUID.randomUUID(), "Bob", "pvp");

    private final FakeBroker broker = new FakeBroker();
    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);
    private final TestNetworks networks = new TestNetworks(broker, clock);

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    private record Received(
            AlertKind kind,
            Component component,
            @Nullable String server,
            @Nullable String pair) {}

    private final class Instance implements AlertNetwork.Staff {
        final List<Received> received = new CopyOnWriteArrayList<>();
        final AtomicBoolean wantsVerbose = new AtomicBoolean();
        final NetworkService service;
        final AlertNetwork alerts;

        Instance(String id) {
            this(id, new SharedClaims(null, AlertNetwork.CLAIMS, clock::get));
        }

        /** {@code claims}: shared with the other engine generations of instance {@code id}. */
        Instance(String id, SharedClaims claims) {
            service = networks.service(id).build();
            alerts = new AlertNetwork(() -> service, this, claims, clock::get);
            alerts.attach(service);
            service.start();
        }

        @Override
        public void received(AlertKind kind, Component component, @Nullable String server, @Nullable String pair) {
            received.add(new Received(kind, component, server, pair));
        }

        @Override
        public boolean wantVerbose() {
            return wantsVerbose.get();
        }
    }

    @Test
    void anAlertRaisedOnOneInstanceReachesTheOtherWithItsServer() {
        Instance p3 = new Instance("p3");
        Instance p7 = new Instance("p7");
        Component flag = Component.text("Bob failed Reach", NamedTextColor.RED);

        assertTrue(p3.alerts.publish(AlertKind.ALERT, BOB, flag, null));
        assertTrue(p3.alerts.publish(AlertKind.BRAND, BOB, Component.text("lunar"), null));

        assertEquals(
                List.of(
                        new Received(AlertKind.ALERT, flag, "pvp", null),
                        new Received(AlertKind.BRAND, Component.text("lunar"), "pvp", null)),
                p7.received);
        assertTrue(p3.received.isEmpty());
    }

    @Test
    void verboseCrossesOnlyWhileAPeerFlagsGlobalVerbose() {
        Instance p3 = new Instance("p3");
        Instance p7 = new Instance("p7");

        String pair = AlertNetwork.newPair();
        assertFalse(p3.alerts.publish(AlertKind.VERBOSE, BOB, Component.text("v"), pair));

        p7.wantsVerbose.set(true);
        p7.alerts.demandChanged();
        p7.service.sendDelta();
        assertFalse(p3.alerts.verboseWanted());
        clock.addAndGet(1_000);
        assertTrue(p3.alerts.verboseWanted());
        assertTrue(p3.alerts.publish(AlertKind.VERBOSE, BOB, Component.text("v"), pair));
        assertTrue(p3.alerts.publish(AlertKind.ALERT, BOB, Component.text("a"), pair));
        assertEquals(
                List.of(
                        new Received(AlertKind.VERBOSE, Component.text("v"), "pvp", pair),
                        new Received(AlertKind.ALERT, Component.text("a"), "pvp", pair)),
                p7.received);

        p7.wantsVerbose.set(false);
        p7.alerts.demandChanged();
        p7.service.sendDelta();
        clock.addAndGet(1_000);
        assertFalse(p3.alerts.verboseWanted());
    }

    @Test
    void anInstanceWithoutATransportSendsNothing() {
        NetworkService local = networks.service("p3").transport(null).build();
        Instance p7 = new Instance("p7");
        AlertNetwork alerts =
                new AlertNetwork(() -> local, p7, new SharedClaims(null, AlertNetwork.CLAIMS, clock::get), clock::get);
        alerts.attach(local);
        local.start();

        assertFalse(alerts.running());
        assertFalse(alerts.publish(AlertKind.ALERT, BOB, Component.text("a"), null));
        assertTrue(p7.received.isEmpty());
    }

    @Test
    void ofTwoGenerationsOnOneInstanceOnlyOneTakesEachMessage() {
        SharedClaims claims = new SharedClaims(null, AlertNetwork.CLAIMS, clock::get);
        Instance older = new Instance("p3", claims);
        Instance newer = new Instance("p3", claims);
        Instance p7 = new Instance("p7");

        for (int i = 0; i < 3; i++) p7.alerts.publish(AlertKind.ALERT, BOB, Component.text("a" + i), null);

        assertEquals(3, older.received.size() + newer.received.size());
        assertEquals(3, older.received.size());
    }

    @Test
    void anAlertAboutNoPlayerArrivesWithoutAServer() {
        Instance p3 = new Instance("p3");
        Instance p7 = new Instance("p7");

        p3.alerts.publish(AlertKind.ALERT, new AlertSubject(null, null, "pvp"), Component.text("a"), null);

        assertEquals(List.of(new Received(AlertKind.ALERT, Component.text("a"), null, null)), p7.received);
    }
}
