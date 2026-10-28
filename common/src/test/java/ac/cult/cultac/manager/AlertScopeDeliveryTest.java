package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.alerts.AlertKind;
import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.manager.alerts.AlertScopes;
import ac.cult.cultac.manager.alerts.AlertSubject;
import ac.cult.cultac.network.routing.NetworkService;
import ac.cult.cultac.network.routing.TestNetworks;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Instance p3 delivers and broadcasts; p7 is a peer on the same broker. */
class AlertScopeDeliveryTest {
    private static final AlertSubject ON_PVP = new AlertSubject(UUID.randomUUID(), "Bob", "pvp");

    private final TestNetworks networks = new TestNetworks();
    private final NetworkService service = networks.started("p3");
    private final NetworkService peer = networks.started("p7");
    private final Sender console = mock(Sender.class);
    private final List<PlatformPlayer> staff = new ArrayList<>();
    private final AlertScopes scopes = new AlertScopes(null, () -> null);
    private boolean proxy = true;
    private boolean current = true;
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

    private AlertManagerImpl manager(NetworkService running) {
        AlertManagerImpl manager = new AlertManagerImpl(scopes, () -> running, () -> proxy, () -> current);
        manager.network().attach(running);
        return manager;
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
            case ALERT -> alerts.sendAlert(subject, message, null);
            case VERBOSE -> alerts.sendVerbose(subject, message, null);
            case BRAND -> alerts.sendBrand(subject, message, null);
        };
    }

    private static long messages(Object recipient) {
        return mockingDetails(recipient).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .count();
    }

    private boolean peerSeesGlobalVerbose() {
        return peer.peerFlags().get("p3").get("gv").getAsBoolean();
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

            alerts.deliverReceived(kind, Component.text(kind.id()), "pvp", false);
            alerts.deliverReceived(kind, Component.text(kind.id()), null, false);

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
        for (AlertKind kind : AlertKind.values()) raise(kind, AlertSubject.UNKNOWN);
        assertEquals(3, messages(console));
    }

    @Test
    void onAServerEveryLocalMessageIsAboutThatServerAndNoReceivedOneIs() {
        proxy = false;
        PlatformPlayer global = staff(null, AlertScope.GLOBAL);
        PlatformPlayer local = staff(null, AlertScope.SERVER);
        subscribe(AlertKind.ALERT, global, local);

        alerts.sendAlert(AlertSubject.UNKNOWN, Component.text("here"), null);
        assertEquals(1, messages(global));
        assertEquals(1, messages(local));

        alerts.deliverReceived(AlertKind.ALERT, Component.text("elsewhere"), null, false);
        assertEquals(2, messages(global));
        assertEquals(1, messages(local));
    }

    @Test
    void anUnknownServerOnAProxyReachesGlobalStaffOnly() {
        PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
        PlatformPlayer server = staff(null, AlertScope.SERVER);
        subscribe(AlertKind.ALERT, global, server);

        alerts.sendAlert(Component.text("unknown"), null);

        assertEquals(1, messages(global));
        assertEquals(0, messages(server));
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
        assertEquals("Bob", alert.getAsJsonObject("player").get("n").getAsString());
        assertEquals(
                ON_PVP.uuid().toString(),
                alert.getAsJsonObject("player").get("u").getAsString());
        assertEquals(
                Component.text("flag"),
                GsonComponentSerializer.gson()
                        .deserialize(alert.get("component").toString()));
        assertFalse(alert.has("verbose"));
        assertEquals("brand", sent.get(1).get("kind").getAsString());
        assertFalse(alerts.hasVerboseListeners());
    }

    @Test
    void verboseGoesOutWhileAPeerWantsItAndItsAlertSaysSo() {
        peer.flag("gv", new JsonPrimitive(true));
        assertTrue(alerts.hasVerboseListeners());

        Set<PlatformPlayer> verboseRecipients = alerts.sendVerbose(ON_PVP, Component.text("verbose"), null);
        alerts.sendAlert(ON_PVP, Component.text("flag"), verboseRecipients);
        alerts.sendAlert(ON_PVP, Component.text("alone"), null);

        List<JsonObject> sent = networks.broadcasts("alert");
        assertEquals(3, sent.size());
        assertEquals("verbose", sent.get(0).get("kind").getAsString());
        assertTrue(sent.get(1).get("verbose").getAsBoolean());
        assertFalse(sent.get(2).has("verbose"));
    }

    @Test
    void alertRecipientsDoNotAlsoGetTheVerboseCopyLocallyOrReceived() {
        PlatformPlayer verbose = staff("pvp", AlertScope.SERVER);
        PlatformPlayer alertsOnly = staff("pvp", AlertScope.SERVER);
        subscribe(AlertKind.VERBOSE, verbose);
        subscribe(AlertKind.ALERT, alertsOnly);

        Set<PlatformPlayer> verboseRecipients = alerts.sendVerbose(ON_PVP, Component.text("verbose"), null);
        alerts.sendAlert(ON_PVP, Component.text("flag"), verboseRecipients);
        assertEquals(1, messages(verbose));
        assertEquals(1, messages(alertsOnly));

        alerts.deliverReceived(AlertKind.VERBOSE, Component.text("verbose"), "pvp", false);
        alerts.deliverReceived(AlertKind.ALERT, Component.text("flag"), "pvp", true);
        assertEquals(2, messages(verbose));
        assertEquals(2, messages(alertsOnly));

        // No verbose copy came first, so the verbose subscriber takes the alert.
        alerts.deliverReceived(AlertKind.ALERT, Component.text("flag"), "pvp", false);
        assertEquals(3, messages(verbose));
        assertEquals(3, messages(alertsOnly));
    }

    @Test
    void aPeersAlertReachesStaffThroughTheCurrentGenerationOnly() {
        PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
        subscribe(AlertKind.ALERT, global);
        JsonObject body = new JsonObject();
        body.addProperty("kind", "alert");
        body.addProperty("server", "pvp");
        JsonObject component = new JsonObject();
        component.addProperty("text", "flag");
        body.add("component", component);

        current = false;
        peer.broadcast("alert", body);
        assertEquals(0, messages(global));

        current = true;
        service.sendLocal("alert", body);
        assertEquals(0, messages(global));
        peer.broadcast("alert", body);
        assertEquals(1, messages(global));
    }

    @Test
    void thePermissionDefaultAppliesUntilAChoiceAndIsNeverStored() {
        PlatformPlayer player = staff("lobby");
        assertEquals(AlertScope.SERVER, alerts.scope(player));
        when(player.hasPermission(AlertScopes.GLOBAL_PERMISSION, false)).thenReturn(true);
        assertEquals(AlertScope.GLOBAL, alerts.scope(player));
        assertNull(alerts.chosenScope(player));

        alerts.chooseScope(player, AlertScope.SERVER);
        assertEquals(AlertScope.SERVER, alerts.scope(player));
        assertEquals(AlertScope.SERVER, alerts.chosenScope(player));

        alerts.handlePlayerQuit(player);
        assertNull(alerts.chosenScope(player));
        assertEquals(AlertScope.GLOBAL, alerts.scope(player));
    }

    @Test
    void theGlobalVerboseFlagFollowsSubscribers() {
        PlatformPlayer global = staff("lobby", AlertScope.GLOBAL);
        PlatformPlayer server = staff("lobby", AlertScope.SERVER);
        assertFalse(peerSeesGlobalVerbose());

        alerts.setVerboseEnabled(server, true, true);
        assertFalse(peerSeesGlobalVerbose());
        alerts.setVerboseEnabled(global, true, true);
        assertTrue(peerSeesGlobalVerbose());

        alerts.chooseScope(global, AlertScope.SERVER);
        assertFalse(peerSeesGlobalVerbose());
        alerts.chooseScope(global, AlertScope.GLOBAL);
        assertTrue(peerSeesGlobalVerbose());

        alerts.handlePlayerQuit(global);
        assertFalse(peerSeesGlobalVerbose());
    }
}
