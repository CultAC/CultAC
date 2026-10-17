package ac.cult.cultac.platform.velocity.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Marker;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerFacts;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.player.CultPlayer;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudBackendsTest {
    private static final long POLL_MILLIS = 10;
    private static final long FACTS_WAIT_MILLIS = 400;

    /** The player's event loop, run by hand: tasks run at once, scheduled ones as {@link #advance} passes them. */
    private final EventLoop loop = mock(EventLoop.class);

    private record Scheduled(long at, long order, Runnable task) {}

    private final List<Scheduled> scheduled = new ArrayList<>();
    private long now;
    private long order;
    private final UUID id = UUID.randomUUID();
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player player = mock(Player.class);
    private final CultPlayer cult = mock(CultPlayer.class);
    private final Channel channel = mock(Channel.class);
    private final ServerConnection a = server("a");
    private final ServerConnection b = server("b");
    private final Set<ServerConnection> inPlay = ConcurrentHashMap.newKeySet();
    private final List<String> bound = new CopyOnWriteArrayList<>();
    private volatile ServerConnection current;
    private CloudBackends backends;

    @BeforeEach
    void setUp() {
        when(proxy.getEventManager()).thenReturn(mock(EventManager.class));
        when(proxy.getPlayer(id)).thenReturn(Optional.of(player));
        when(player.getUniqueId()).thenReturn(id);
        when(player.getCurrentServer()).thenAnswer(invocation -> Optional.ofNullable(current));
        when(cult.getUniqueId()).thenReturn(id);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        when(loop.inEventLoop()).thenReturn(true);
        doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(loop)
                .execute(any(Runnable.class));
        when(loop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            long delay = invocation.<TimeUnit>getArgument(2).toNanos(invocation.<Long>getArgument(1));
            scheduled.add(new Scheduled(now + delay, order++, invocation.getArgument(0)));
            return null;
        });
        backends = new CloudBackends(
                proxy, new Object(), ignored -> channel, inPlay::contains, POLL_MILLIS, FACTS_WAIT_MILLIS, () -> now);
    }

    @AfterEach
    void shutdown() {
        backends.close();
    }

    @Test
    void firstJoinBindsOnPostConnect() {
        onLoop(() -> backends.started(cult, bound::add, false));
        settle();
        assertTrue(bound.isEmpty());

        current = a;
        inPlay.add(a);
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        assertEquals(List.of("a"), bound);
    }

    @Test
    void switchBindsTheNewBackendNeverTheOldEvenWhenLoginCameFirst() {
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.configuring(cult));
        inPlay.remove(a);
        current = null;
        onLoop(() -> backends.started(cult, bound::add, false));
        current = b;
        inPlay.add(b);
        settle();
        assertTrue(bound.isEmpty(), "a switch never binds without PostConnect");

        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        settle();
        assertEquals(List.of("b"), bound);
    }

    @Test
    void reconfigurationOnTheSameConnectionBindsWithoutPostConnect() {
        current = a;
        onLoop(() -> backends.configuring(cult));
        onLoop(() -> backends.started(cult, bound::add, false));
        settle();
        assertTrue(bound.isEmpty(), "binds only once the connection is back in play");

        inPlay.add(a);
        await(() -> !bound.isEmpty());
        settle();
        assertEquals(List.of("a"), bound);
    }

    @Test
    void adoptedReconfigurationBindsWithoutPostConnect() {
        current = a;
        onLoop(() -> backends.adopted(player));
        onLoop(() -> backends.started(cult, bound::add, false));
        settle();
        assertTrue(bound.isEmpty(), "binds only once the connection is back in play");

        inPlay.add(a);
        await(() -> !bound.isEmpty());
        settle();
        assertEquals(List.of("a"), bound);
    }

    @Test
    void reconfigurationOnAnotherConnectionWaitsForPostConnect() {
        current = a;
        onLoop(() -> backends.configuring(cult));
        ServerConnection again = server("a");
        current = again;
        inPlay.add(again);
        onLoop(() -> backends.started(cult, bound::add, false));
        settle();
        assertTrue(bound.isEmpty());

        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        assertEquals(List.of("a"), bound);
    }

    @Test
    void disconnectDropsThePendingBind() {
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onDisconnect(new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        settle();
        assertTrue(bound.isEmpty());

        onLoop(() -> backends.configuring(cult));
        onLoop(() -> backends.started(cult, bound::add, false));
        onLoop(() -> backends.disconnected(cult));
        settle();
        assertTrue(bound.isEmpty(), "a reconfiguration poll stops once the player is gone");
    }

    @Test
    void anAbandonedSegmentIsNeverBound() {
        current = a;
        onLoop(() -> backends.configuring(cult));
        onLoop(() -> backends.started(cult, bound::add, false));
        onLoop(() -> backends.abandoned(cult));

        inPlay.add(a);
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        settle();
        assertTrue(bound.isEmpty());
    }

    @Test
    void aSegmentStartedMidPlayBindsToTheBackendInPlay() {
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.started(cult, bound::add, true));
        await(() -> !bound.isEmpty());
        settle();
        assertEquals(List.of("a"), bound);
    }

    @Test
    void aSegmentStartedMidSwitchWaitsForPostConnect() {
        current = a;
        onLoop(() -> backends.started(cult, bound::add, true));
        settle();
        assertTrue(bound.isEmpty(), "the backend is not in play yet");

        current = b;
        inPlay.add(b);
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        assertEquals(List.of("b"), bound);
    }

    @Test
    void aBindWaitsForTheFactsOfABridgeSessionNotYetReady() {
        BridgeSession handshake = session("a", "Paper 26.3");
        backends.start(sessions(handshake));
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        settle();
        assertTrue(bound.isEmpty(), "the session has not brought the backend's facts");

        onLoop(() -> {
            backends.onReady(handshake);
            backends.onMessage(handshake, new BridgeMessage.CloudFacts(50_000, Map.of()));
        });
        await(() -> !bound.isEmpty());
        assertEquals(List.of("a"), bound);
    }

    @Test
    void aBindWaitsForFactsOnlySoLong() {
        backends.start(sessions(session("a", "Paper 26.3")));
        current = a;
        inPlay.add(a);
        long started = now;
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        assertTrue(now - started >= TimeUnit.MILLISECONDS.toNanos(FACTS_WAIT_MILLIS));
        assertEquals(List.of("a"), bound);
    }

    @Test
    void aBindToABackendWhoseFactsAreKnownDoesNotWait() {
        BridgeSession ready = session("a", "Paper 26.3");
        backends.start(sessions(ready));
        backends.onReady(ready);
        backends.onMessage(ready, new BridgeMessage.CloudFacts(50_000, Map.of()));
        current = a;
        inPlay.add(a);
        long started = now;
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        assertTrue(now - started < TimeUnit.MILLISECONDS.toNanos(FACTS_WAIT_MILLIS));
    }

    @Test
    void aBindToABackendWithoutACompanionDoesNotWait() {
        BridgeSession handshake = session("a", "");
        backends.start(sessions(handshake));
        backends.onClosed(handshake);
        current = a;
        inPlay.add(a);
        long started = now;
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        assertTrue(now - started < TimeUnit.MILLISECONDS.toNanos(FACTS_WAIT_MILLIS));
    }

    @Test
    void aBackendThatGainsACompanionIsWaitedForAgain() {
        BridgeSession session = session("a", "Paper 26.3");
        backends.start(sessions(session));
        backends.onClosed(session);
        backends.onReady(session);
        current = a;
        inPlay.add(a);
        long started = now;
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        await(() -> !bound.isEmpty());
        assertTrue(now - started >= TimeUnit.MILLISECONDS.toNanos(FACTS_WAIT_MILLIS));
    }

    @Test
    void aBindToACompanionReadyWithoutCloudFactsDoesNotWait() {
        BridgeSession ready = session("a", "Paper 26.3");
        when(ready.isReady()).thenReturn(true);
        backends.start(sessions(ready));
        backends.onReady(ready);
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        assertEquals(List.of("a"), bound, "an older companion never sends the facts");
    }

    @Test
    void aBindToACompanionReadyWithCloudFactsWaitsForThem() {
        BridgeSession ready = session("a", "Paper 26.3");
        when(ready.isReady()).thenReturn(true);
        when(ready.has(ServerCapability.CLOUD_FACTS)).thenReturn(true);
        backends.start(sessions(ready));
        backends.onReady(ready);
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        settle();
        assertTrue(bound.isEmpty());
        backends.onMessage(ready, new BridgeMessage.CloudFacts(50_000, Map.of()));
        await(() -> !bound.isEmpty());
        assertTrue(now < TimeUnit.MILLISECONDS.toNanos(FACTS_WAIT_MILLIS));
    }

    @Test
    void aProxyWithoutTheBridgeNeverWaits() {
        backends.start(BridgeSessions.NONE);
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.started(cult, bound::add, false));
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        assertEquals(List.of("a"), bound);
        assertTrue(scheduled.isEmpty(), "not even one poll");
    }

    private BridgeSessions sessions(BridgeSession session) {
        return new BridgeSessions() {
            @Override
            public BridgeSession current(UUID player) {
                return session;
            }

            @Override
            public void addListener(BridgeMessageListener listener) {}
        };
    }

    @Test
    void bindsAtMostOnce() {
        current = a;
        inPlay.add(a);
        onLoop(() -> backends.configuring(cult));
        onLoop(() -> backends.started(cult, bound::add, false));
        await(() -> !bound.isEmpty());
        backends.onServerPostConnect(new ServerPostConnectEvent(player, null));
        settle();
        assertEquals(List.of("a"), bound);
    }

    @Test
    void factsAreKeptPerBackendAndPlayerFactsOnlyForTheirBackend() {
        List<UUID> changed = new CopyOnWriteArrayList<>();
        backends.onFactsChanged(changed::add);
        BridgeSession onA = session("a", "Paper 26.3");
        BridgeSession onB = session("b", "Paper 26.3");

        assertEquals(ServerFacts.NONE, backends.factsOf(id, "a"));
        backends.onReady(onA);
        assertEquals(new ServerFacts(0, "Paper 26.3", Map.of()), backends.factsOf(id, "a"));

        backends.onMessage(onA, new BridgeMessage.CloudFacts(50_000, Map.of("keepalive-interval-ms", "1000")));
        backends.onMessage(onA, new PlayerFacts(Map.of("attack-speed-base", "4.0")));
        ServerFacts facts = backends.factsOf(id, "a");
        assertEquals(50_000, facts.tickMicros());
        assertEquals("Paper 26.3", facts.server());
        assertEquals(Map.of("keepalive-interval-ms", "1000", "attack-speed-base", "4.0"), facts.settings());
        assertEquals(List.of(id, id), changed);

        assertEquals(ServerFacts.NONE, backends.factsOf(id, "b"), "player facts never cross backends");
        backends.onClosed(onB);
        assertEquals("4.0", backends.factsOf(id, "a").settings().get("attack-speed-base"));
        backends.onClosed(onA);
        assertNull(backends.factsOf(id, "a").settings().get("attack-speed-base"));
        assertEquals(50_000, backends.factsOf(id, "a").tickMicros(), "backend facts outlive the session");
    }

    @Test
    void markersGoOnlyToASessionThatAnswersThem() {
        BridgeSession session = session("a", "Paper 26.3");
        backends.start(sessions(session));
        assertFalse(backends.marks(cult));
        backends.mark(cult, 4);
        verify(session, never()).send(any());

        when(session.has(ServerCapability.MARKER)).thenReturn(true);
        assertTrue(backends.marks(cult));
        backends.mark(cult, 4);
        verify(session).send(new Marker(4));
    }

    @Test
    void withoutACompanionThereAreNoFacts() {
        assertEquals(ServerFacts.NONE, CloudBackends.facts(id, "a"));
        backends.onReady(session("a", ""));
        assertEquals(ServerFacts.NONE, backends.factsOf(id, "a"));
    }

    private BridgeSession session(String server, String version) {
        BridgeSession session = mock(BridgeSession.class);
        when(session.serverName()).thenReturn(server);
        when(session.serverVersion()).thenReturn(version);
        when(session.playerId()).thenReturn(id);
        return session;
    }

    private static ServerConnection server(String name) {
        ServerConnection server = mock(ServerConnection.class);
        when(server.getServerInfo())
                .thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", 25566)));
        return server;
    }

    private void onLoop(Runnable task) {
        task.run();
    }

    /** Runs the tasks scheduled within the next {@code millis}, in time order. */
    private void advance(long millis) {
        long end = now + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            Scheduled next = scheduled.stream()
                    .filter(task -> task.at() <= end)
                    .min(Comparator.comparingLong(Scheduled::at).thenComparingLong(Scheduled::order))
                    .orElse(null);
            if (next == null) break;
            scheduled.remove(next);
            now = Math.max(now, next.at());
            next.task().run();
        }
        now = end;
    }

    /** Lets several polls run. */
    private void settle() {
        advance(POLL_MILLIS * 6);
    }

    /** Advances a poll at a time until {@code condition} holds. */
    private void await(BooleanSupplier condition) {
        for (int polls = 0; !condition.getAsBoolean(); polls++) {
            if (polls > 1000) fail("never happened");
            advance(POLL_MILLIS);
        }
    }
}
