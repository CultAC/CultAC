package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.alerts.AlertKind;
import ac.cult.cultac.manager.alerts.AlertNetwork;
import ac.cult.cultac.manager.alerts.AlertSubject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
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
        final AtomicReference<Set<String>> wantsVerbose = new AtomicReference<>(Set.of());
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
        public Set<String> verboseServers() {
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
    void verboseCrossesOnlyWhileAPeerCoversItsSubjectsServer() {
        Instance p3 = new Instance("p3");
        Instance p7 = new Instance("p7");

        String pair = AlertNetwork.newPair();
        assertFalse(p3.alerts.publish(AlertKind.VERBOSE, BOB, Component.text("v"), pair));

        p7.wantsVerbose.set(Set.of("lobby"));
        p7.service.sendDelta();
        assertFalse(p3.alerts.verboseWanted());
        clock.addAndGet(1_000);
        assertTrue(p3.alerts.verboseWanted());
        assertFalse(p3.alerts.publish(AlertKind.VERBOSE, BOB, Component.text("v"), pair));
        assertFalse(p3.alerts.publish(AlertKind.VERBOSE, AlertSubject.NONE, Component.text("v"), pair));
        assertFalse(p3.alerts.publish(AlertKind.VERBOSE, BOB.on(null), Component.text("v"), pair));

        p7.wantsVerbose.set(Set.of("lobby", "pvp"));
        p7.service.sendDelta();
        clock.addAndGet(1_000);
        assertTrue(p3.alerts.publish(AlertKind.VERBOSE, BOB, Component.text("v"), pair));
        assertTrue(p3.alerts.publish(AlertKind.ALERT, BOB, Component.text("a"), pair));
        assertEquals(
                List.of(
                        new Received(AlertKind.VERBOSE, Component.text("v"), "pvp", pair),
                        new Received(AlertKind.ALERT, Component.text("a"), "pvp", pair)),
                p7.received);

        p7.wantsVerbose.set(Set.of(NetworkService.EVERY_SERVER));
        p7.service.sendDelta();
        clock.addAndGet(1_000);
        assertTrue(p3.alerts.publish(AlertKind.VERBOSE, AlertSubject.NONE, Component.text("none"), null));
        assertTrue(p3.alerts.publish(AlertKind.VERBOSE, BOB.on(null), Component.text("unknown"), null));

        p7.wantsVerbose.set(Set.of());
        p7.service.sendDelta();
        clock.addAndGet(1_000);
        assertFalse(p3.alerts.verboseWanted());
    }

    @Test
    void theComponentCrossesAsJsonTextAndAnOlderObjectFormStillReads() {
        Instance p3 = new Instance("p3");
        Instance p7 = new Instance("p7");
        Component flag = Component.text("Bob failed Reach", NamedTextColor.RED);
        assertTrue(p7.alerts.publish(AlertKind.ALERT, BOB, flag, null));
        assertTrue(networks.broadcasts(AlertNetwork.TYPE).getFirst().get("component").isJsonPrimitive());

        JsonObject body = new JsonObject();
        body.addProperty("kind", AlertKind.ALERT.id());
        body.addProperty("player", true);
        body.addProperty("server", "pvp");
        body.add("component", JsonParser.parseString(GsonComponentSerializer.gson().serialize(flag)));
        assertTrue(p7.service.broadcast(AlertNetwork.TYPE, body));

        assertEquals(
                List.of(
                        new Received(AlertKind.ALERT, flag, "pvp", null),
                        new Received(AlertKind.ALERT, flag, "pvp", null)),
                p3.received);
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
