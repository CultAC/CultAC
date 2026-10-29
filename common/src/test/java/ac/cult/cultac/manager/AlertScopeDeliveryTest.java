package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.AlertManagerImpl.Recipients;
import ac.cult.cultac.manager.alerts.AlertKind;
import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.manager.alerts.AlertScopes;
import ac.cult.cultac.manager.alerts.AlertSubject;
import ac.cult.cultac.network.routing.NetworkService;
import ac.cult.cultac.network.routing.TestNetworks;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Instance p3 delivers and broadcasts; p7 is a peer on the same broker. */
class AlertScopeDeliveryTest {
    private static final AlertSubject ON_PVP = new AlertSubject(UUID.randomUUID(), "Bob", "pvp");
    private static final AlertSubject BETWEEN_SERVERS = new AlertSubject(UUID.randomUUID(), "Cat", null);

    private final TestNetworks networks = new TestNetworks();
    private final NetworkService service = networks.started("p3");
    private final NetworkService peer = networks.started("p7");
    private final Sender console = mock(Sender.class);
    private final List<PlatformPlayer> staff = new ArrayList<>();
    private final AlertScopes scopes = new AlertScopes(null, () -> null);
    private final HostContext host = host();
    private boolean proxy = true;
    private boolean relay;
    private AlertManagerImpl alerts;
    private Object previousServer;
    private boolean consoleAlerts;
    private boolean consoleVerbose;
    private boolean consoleBrands;

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        alerts = manager(service);
        consoleAlerts = alerts.hasConsoleAlertsEnabled();
        consoleVerbose = alerts.hasConsoleVerboseEnabled();
        consoleBrands = alerts.hasConsoleBrandsEnabled();
        alerts.setConsoleAlertsEnabled(false, true);
        alerts.setConsoleBrandsEnabled(false, true);
        PlatformServer server = mock(PlatformServer.class);
        when(server.getConsoleSender()).thenReturn(console);
        previousServer = platformServer().get(null);
        platformServer().set(null, server);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        leave();
        alerts.setConsoleAlertsEnabled(consoleAlerts, true);
        alerts.setConsoleVerboseEnabled(consoleVerbose, true);
        alerts.setConsoleBrandsEnabled(consoleBrands, true);
        platformServer().set(null, previousServer);
    }

    /** An engine generation of instance {@code running.instanceId()}, sharing the host with every other. */
    private AlertManagerImpl manager(NetworkService running) {
        AlertManagerImpl manager = new AlertManagerImpl(scopes, () -> running, () -> proxy, host, () -> relay);
        manager.network().attach(running);
        return manager;
    }

    @SuppressWarnings("unchecked")
    private static HostContext host() {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        HostContext host = mock(HostContext.class);
        when(host.shared(any(), any()))
                .thenAnswer(invocation -> shared.computeIfAbsent(
                        invocation.getArgument(0), key -> ((Supplier<Object>) invocation.getArgument(1)).get()));
        return host;
    }

    private static Field platformServer() throws NoSuchFieldException {
        Field field = AlertManagerImpl.class.getDeclaredField("platformServer");
        field.setAccessible(true);
        return field;
    }

    private PlatformPlayer staff(String server) {
        PlatformPlayer player = mock(PlatformPlayer.class);
        Object handle = new Object();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getNative()).thenReturn(handle);
        when(player.serverName()).thenReturn(server);
        staff.add(player);
        return player;
    }

    private PlatformPlayer staff(String server, AlertScope scope) {
        PlatformPlayer player = staff(server);
        scopes.choose(player, scope);
        return player;
    }

    private void leave() {
        for (PlatformPlayer each : staff) alerts.handlePlayerQuit(each);
        staff.clear();
    }

    private void subscribe(AlertKind kind, PlatformPlayer... players) {
        for (PlatformPlayer player : players) {
            switch (kind) {
                case ALERT -> alerts.setAlertsEnabled(player, true, true);
                case VERBOSE -> alerts.setVerboseEnabled(player, true, true);
                case BRAND -> alerts.setBrandsEnabled(player, true, true);
            }
        }
    }

    private Set<PlatformPlayer> raise(AlertKind kind, AlertSubject subject) {
        Component message = Component.text(kind.id());
        return switch (kind) {
            case ALERT -> alerts.sendAlert(subject, message, null).players();
            case VERBOSE -> alerts.sendVerbose(subject, message, null).players();
            case BRAND -> alerts.sendBrand(subject, message, null).players();
        };
    }

    private static long messages(Object recipient) {
        return mockingDetails(recipient).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .count();
    }

    private boolean peerSeesVerbose() {
        TestNetworks.sendDelta(service);
        return peer.peersWantVerbose();
    }

    private static JsonObject body(String kind, String text, String server, boolean withPlayer) {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("player", withPlayer);
        body.addProperty("server", server);
        JsonObject component = new JsonObject();
        component.addProperty("text", text);
        body.add("component", component);
        return body;
    }

    @Test
    void localMessagesOnAProxyReachGlobalStaffAndServerStaffOnTheSubjectsServer() {
        for (AlertKind kind : AlertKind.values()) {
            PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
            PlatformPlayer sameServer = staff("pvp", AlertScope.SERVER);
            PlatformPlayer otherServer = staff("lobby", AlertScope.SERVER);
            PlatformPlayer betweenServers = staff(null, AlertScope.SERVER);
            subscribe(kind, global, sameServer, otherServer, betweenServers);

            raise(kind, ON_PVP);

            assertEquals(1, messages(global), kind.id());
            assertEquals(1, messages(sameServer), kind.id());
            assertEquals(0, messages(otherServer), kind.id());
            assertEquals(0, messages(betweenServers), kind.id());
            leave();
        }
    }

    @Test
    void receivedMessagesReachTheSameStaffAndNeverTheConsole() {
        alerts.setConsoleVerboseEnabled(true, true);
        alerts.setConsoleBrandsEnabled(true, true);
        for (AlertKind kind : AlertKind.values()) {
            PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
            PlatformPlayer sameServer = staff("pvp", AlertScope.SERVER);
            PlatformPlayer otherServer = staff("lobby", AlertScope.SERVER);
            subscribe(kind, global, sameServer, otherServer);

            alerts.deliverReceived(kind, Component.text(kind.id()), "pvp", null);
            alerts.deliverReceived(kind, Component.text(kind.id()), null, null);

            assertEquals(2, messages(global), kind.id());
            assertEquals(1, messages(sameServer), kind.id());
            assertEquals(0, messages(otherServer), kind.id());
            leave();
        }
        assertEquals(0, messages(console));
    }

    @Test
    void theConsoleGetsEveryLocalMessage() {
        alerts.setConsoleVerboseEnabled(true, true);
        alerts.setConsoleBrandsEnabled(true, true);
        for (AlertKind kind : AlertKind.values()) raise(kind, AlertSubject.NONE);
        assertEquals(3, messages(console));
    }

    @Test
    void onAServerEveryLocalMessageIsAboutThatServerAndNoReceivedOneIs() {
        proxy = false;
        PlatformPlayer global = staff(null, AlertScope.GLOBAL);
        PlatformPlayer local = staff(null, AlertScope.SERVER);
        subscribe(AlertKind.ALERT, global, local);

        alerts.sendAlert(BETWEEN_SERVERS, Component.text("here"), null);
        assertEquals(1, messages(global));
        assertEquals(1, messages(local));

        alerts.deliverReceived(AlertKind.ALERT, Component.text("elsewhere"), null, null);
        assertEquals(2, messages(global));
        assertEquals(1, messages(local));
    }

    @Test
    void anUnknownServerOnAProxyReachesGlobalStaffOnly() {
        PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
        PlatformPlayer server = staff(null, AlertScope.SERVER);
        subscribe(AlertKind.ALERT, global, server);

        alerts.sendAlert(BETWEEN_SERVERS, Component.text("unknown"), null);

        assertEquals(1, messages(global));
        assertEquals(0, messages(server));
    }

    @Test
    void anAlertAboutNoPlayerReachesEverySubscriberHereAndOnlyGlobalStaffWhenReceived() {
        PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
        PlatformPlayer sameServer = staff("pvp", AlertScope.SERVER);
        PlatformPlayer betweenServers = staff(null, AlertScope.SERVER);
        subscribe(AlertKind.ALERT, global, sameServer, betweenServers);

        alerts.sendAlert(Component.text("sent"), null);
        assertEquals(1, messages(global));
        assertEquals(1, messages(sameServer));
        assertEquals(1, messages(betweenServers));
        JsonObject sent = networks.broadcasts("alert").get(0);
        assertFalse(sent.get("player").getAsBoolean());
        assertTrue(sent.get("server").isJsonNull());

        peer.broadcast("alert", body("alert", "received", "pvp", false));
        assertEquals(2, messages(global));
        assertEquals(1, messages(sameServer));
        assertEquals(1, messages(betweenServers));
    }

    @Test
    void aServerNamesItselfAsItsPlayersServerOnTheWire() {
        proxy = false;
        alerts.sendAlert(BETWEEN_SERVERS, Component.text("flag"), null);
        alerts.sendAlert(Component.text("no player"), null);

        List<JsonObject> sent = networks.broadcasts("alert");
        assertEquals("p3", sent.get(0).get("server").getAsString());
        assertTrue(sent.get(1).get("server").isJsonNull());
    }

    @Test
    void relayedAlertsReachGlobalStaffOnlyAndAllowGlobalWithoutATransport() {
        proxy = false;
        relay = true;
        AlertManagerImpl server = manager(networks.withoutTransport("p9"));
        alerts.setConsoleAlertsEnabled(true, true);
        PlatformPlayer global = staff(null, AlertScope.GLOBAL);
        PlatformPlayer local = staff(null, AlertScope.SERVER);
        subscribe(AlertKind.ALERT, global, local);

        assertTrue(server.networkAlerts());
        assertEquals(AlertScope.GLOBAL, server.scope(global));
        server.relayAlert(Component.text("from another server"));

        assertEquals(1, messages(global));
        assertEquals(0, messages(local));
        assertEquals(0, messages(console));
        assertTrue(networks.broadcasts("alert").isEmpty());

        relay = false;
        assertFalse(server.networkAlerts());
        assertEquals(AlertScope.SERVER, server.scope(global));
    }

    @Test
    void withoutATransportEveryoneIsServerScopedAndNothingIsBroadcast() {
        AlertManagerImpl offline = manager(networks.withoutTransport("p9"));
        PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
        PlatformPlayer sameServer = staff("pvp", AlertScope.SERVER);
        subscribe(AlertKind.ALERT, global, sameServer);

        offline.sendAlert(ON_PVP, Component.text("flag"), null);

        assertEquals(AlertScope.SERVER, offline.scope(global));
        assertEquals(0, messages(global));
        assertEquals(1, messages(sameServer));
        assertTrue(networks.broadcasts("alert").isEmpty());
    }

    @Test
    void everyLocalMessageIsBroadcastWithItsSubjectAndVerboseOnlyOnDemand() {
        alerts.sendAlert(ON_PVP, Component.text("flag"), null);
        alerts.sendBrand(ON_PVP, Component.text("brand"), null);
        alerts.sendVerbose(ON_PVP, Component.text("verbose"), null);

        List<JsonObject> sent = networks.broadcasts("alert");
        assertEquals(2, sent.size());
        JsonObject alert = sent.get(0);
        assertEquals("alert", alert.get("kind").getAsString());
        assertEquals("pvp", alert.get("server").getAsString());
        assertTrue(alert.get("player").getAsBoolean());
        assertEquals(
                Component.text("flag"),
                GsonComponentSerializer.gson()
                        .deserialize(alert.get("component").toString()));
        assertFalse(alert.has("pair"));
        assertEquals("brand", sent.get(1).get("kind").getAsString());
        assertFalse(alerts.hasVerboseListeners());
    }

    @Test
    void verboseGoesOutWhileAPeerWantsItAndItsAlertSaysSo() {
        peer.verbose(() -> true);
        peer.verboseChanged();
        TestNetworks.sendDelta(peer);
        assertTrue(alerts.hasVerboseListeners());

        Recipients verboseRecipients = alerts.sendVerbose(ON_PVP, Component.text("verbose"), null);
        alerts.sendAlert(ON_PVP, Component.text("flag"), verboseRecipients);
        alerts.sendAlert(ON_PVP, Component.text("alone"), null);

        List<JsonObject> sent = networks.broadcasts("alert");
        assertEquals(3, sent.size());
        assertEquals("verbose", sent.get(0).get("kind").getAsString());
        assertNotNull(sent.get(0).get("pair"));
        assertEquals(sent.get(0).get("pair"), sent.get(1).get("pair"));
        assertFalse(sent.get(2).has("pair"));
    }

    @Test
    void alertRecipientsDoNotAlsoGetTheVerboseCopyLocallyOrReceived() {
        PlatformPlayer verbose = staff("pvp", AlertScope.SERVER);
        PlatformPlayer alertsOnly = staff("pvp", AlertScope.SERVER);
        subscribe(AlertKind.VERBOSE, verbose);
        subscribe(AlertKind.ALERT, alertsOnly);

        Recipients verboseRecipients = alerts.sendVerbose(ON_PVP, Component.text("verbose"), null);
        alerts.sendAlert(ON_PVP, Component.text("flag"), verboseRecipients);
        assertEquals(1, messages(verbose));
        assertEquals(1, messages(alertsOnly));

        alerts.deliverReceived(AlertKind.VERBOSE, Component.text("verbose"), "pvp", "pair");
        alerts.deliverReceived(AlertKind.ALERT, Component.text("flag"), "pvp", "pair");
        assertEquals(2, messages(verbose));
        assertEquals(2, messages(alertsOnly));

        alerts.deliverReceived(AlertKind.ALERT, Component.text("flag"), "pvp", null);
        assertEquals(3, messages(verbose));
        assertEquals(3, messages(alertsOnly));
    }

    @Test
    void verboseToggledBetweenAReceivedPairGivesEachStaffMemberOneMessage() {
        PlatformPlayer turnsOff = staff("pvp", AlertScope.SERVER);
        PlatformPlayer turnsOn = staff("pvp", AlertScope.SERVER);
        subscribe(AlertKind.VERBOSE, turnsOff);
        subscribe(AlertKind.ALERT, turnsOn);

        alerts.deliverReceived(AlertKind.VERBOSE, Component.text("verbose"), "pvp", "pair");
        alerts.setVerboseEnabled(turnsOff, false, true);
        alerts.setVerboseEnabled(turnsOn, true, true);
        alerts.deliverReceived(AlertKind.ALERT, Component.text("flag"), "pvp", "pair");

        assertEquals(1, messages(turnsOff));
        assertEquals(1, messages(turnsOn));
    }

    @Test
    void anAlertReceivedBeforeItsVerboseCopyKeepsItsRecipientsFromTheCopy() {
        PlatformPlayer verbose = staff("pvp", AlertScope.SERVER);
        PlatformPlayer alertsOnly = staff("pvp", AlertScope.SERVER);
        subscribe(AlertKind.VERBOSE, verbose);
        subscribe(AlertKind.ALERT, alertsOnly);

        alerts.deliverReceived(AlertKind.ALERT, Component.text("flag"), "pvp", "pair");
        alerts.deliverReceived(AlertKind.VERBOSE, Component.text("verbose"), "pvp", "pair");

        assertEquals(1, messages(verbose));
        assertEquals(1, messages(alertsOnly));
    }

    @Test
    void twoGenerationsReceivingOnePeersAlertDeliverItOnceWhicheverReceivesFirst() {
        for (boolean newerFirst : new boolean[] {false, true}) {
            TestNetworks broker = new TestNetworks();
            NetworkService first = broker.started("p3");
            NetworkService second = broker.started("p3");
            NetworkService sender = broker.started("p7");
            manager(newerFirst ? second : first);
            manager(newerFirst ? first : second);
            PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
            subscribe(AlertKind.ALERT, global);

            sender.broadcast("alert", body("alert", "flag", "pvp", true));
            assertEquals(1, messages(global), "newer first: " + newerFirst);
            leave();
        }
    }

    @Test
    void aPairSplitBetweenTwoGenerationsStillGivesEachStaffMemberOneMessage() {
        AlertManagerImpl newer = manager(networks.started("p3"));
        PlatformPlayer verbose = staff("pvp", AlertScope.SERVER);
        PlatformPlayer alertsOnly = staff("pvp", AlertScope.SERVER);
        subscribe(AlertKind.VERBOSE, verbose);
        subscribe(AlertKind.ALERT, alertsOnly);

        alerts.deliverReceived(AlertKind.VERBOSE, Component.text("verbose"), "pvp", "pair");
        newer.deliverReceived(AlertKind.ALERT, Component.text("flag"), "pvp", "pair");

        assertEquals(1, messages(verbose));
        assertEquals(1, messages(alertsOnly));
    }

    @Test
    void theDefaultIsServerUntilAChoiceAndIsNeverStored() {
        PlatformPlayer player = staff("lobby");
        when(player.hasPermission(anyString(), anyBoolean())).thenReturn(true);
        assertEquals(AlertScope.SERVER, alerts.scope(player));
        assertNull(alerts.chosenScope(player));

        alerts.chooseScope(player, AlertScope.GLOBAL);
        assertEquals(AlertScope.GLOBAL, alerts.scope(player));
        assertEquals(AlertScope.GLOBAL, alerts.chosenScope(player));

        alerts.handlePlayerQuit(player);
        assertNull(alerts.chosenScope(player));
        assertEquals(AlertScope.SERVER, alerts.scope(player));
    }

    @Test
    void theVerboseFlagFollowsSubscribersOfEitherScope() {
        PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
        PlatformPlayer server = staff("lobby", AlertScope.SERVER);
        assertFalse(peerSeesVerbose());

        alerts.setVerboseEnabled(server, true, true);
        assertTrue(peerSeesVerbose());
        alerts.setVerboseEnabled(global, true, true);
        alerts.setVerboseEnabled(server, false, true);
        assertTrue(peerSeesVerbose());

        alerts.handlePlayerQuit(global);
        assertFalse(peerSeesVerbose());
    }

    @Test
    void aServerScopedVerboseListenerOnOneProxyGetsVerboseAnotherProxyRaisesAboutAPlayerOnItsServer() {
        PlatformPlayer listener = staff("pvp", AlertScope.SERVER);
        PlatformPlayer elsewhere = staff("lobby", AlertScope.SERVER);
        AlertManagerImpl other =
                new AlertManagerImpl(new AlertScopes(null, () -> null), () -> peer, () -> true, host(), () -> false);
        other.network().attach(peer);
        assertFalse(other.hasVerboseListeners());

        subscribe(AlertKind.VERBOSE, listener, elsewhere);
        assertTrue(other.hasVerboseListeners());
        other.sendVerbose(ON_PVP, Component.text("verbose"), null);

        assertEquals(1, messages(listener));
        assertEquals(0, messages(elsewhere));
    }
}
