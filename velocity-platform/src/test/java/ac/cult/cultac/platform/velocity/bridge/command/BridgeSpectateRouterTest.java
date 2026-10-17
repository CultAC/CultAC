package ac.cult.cultac.platform.velocity.bridge.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerStateResult;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.TeleportToPlayer;
import ac.cult.cultac.manager.SpectateManager.ServerState;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.velocity.bridge.BackendWorld;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.platform.velocity.bridge.FakeBridgeSession;
import ac.cult.cultac.protocol.value.GameMode;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeSpectateRouterTest {
    private static final UUID STAFF = UUID.fromString("2b5f1e7a-6a39-4d63-9a3f-0f1e2d3c4b5a");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player player = mock(Player.class);
    private final PlatformPlayer staff = mock(PlatformPlayer.class);
    private final PlatformPlayer target = mock(PlatformPlayer.class);
    private final RegisteredServer survival = mock(RegisteredServer.class);
    private final ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
    private final CompletableFuture<ConnectionRequestBuilder.Result> connected = new CompletableFuture<>();
    private final Sessions sessions = new Sessions();
    private BridgeSpectateRouter router;

    @BeforeEach
    void setUp() {
        when(staff.getNative()).thenReturn(player);
        when(staff.getUniqueId()).thenReturn(STAFF);
        when(target.getUniqueId()).thenReturn(TARGET);
        when(player.getUniqueId()).thenReturn(STAFF);
        when(proxy.getServer("survival")).thenReturn(Optional.of(survival));
        when(player.createConnectionRequest(survival)).thenReturn(request);
        when(request.connect()).thenReturn(connected);
        router = new BridgeSpectateRouter(proxy, sessions, 10_000, 10_000 + BridgeSpectateRouter.CONNECT_MARGIN_MILLIS);
    }

    @Test
    void serverNameIsTheCurrentBackend() {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo()).thenReturn(new ServerInfo("lobby", new InetSocketAddress(25566)));
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));

        assertEquals("lobby", router.serverName(staff));
    }

    @Test
    void connectCompletesWhenTheNewBackendIsReady() {
        CompletableFuture<Boolean> arrived = router.connect(staff, "survival");
        connected.complete(result(ConnectionRequestBuilder.Status.SUCCESS));
        assertFalse(arrived.isDone());

        router.onReady(new FakeBridgeSession(STAFF, "lobby"));
        assertFalse(arrived.isDone());
        router.onReady(new FakeBridgeSession(STAFF, "survival"));

        assertTrue(arrived.join());
    }

    @Test
    void readyBeforeTheConnectResultStillCounts() {
        CompletableFuture<Boolean> arrived = router.connect(staff, "survival");
        sessions.current.put(STAFF, new FakeBridgeSession(STAFF, "survival"));
        connected.complete(result(ConnectionRequestBuilder.Status.SUCCESS));

        assertTrue(arrived.join());
    }

    @Test
    void failedConnectionFails() {
        CompletableFuture<Boolean> arrived = router.connect(staff, "survival");
        connected.complete(result(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED));

        assertFalse(arrived.join());
    }

    @Test
    void noCompanionTimesOut() {
        router = new BridgeSpectateRouter(proxy, sessions, 20, 20 + BridgeSpectateRouter.CONNECT_MARGIN_MILLIS);
        CompletableFuture<Boolean> arrived = router.connect(staff, "survival");
        connected.complete(result(ConnectionRequestBuilder.Status.SUCCESS));

        assertFalse(arrived.join());
    }

    @Test
    void theArrivalWaitStartsOnceTheConnectSucceeds() {
        router = new BridgeSpectateRouter(proxy, sessions, 20, 20 + BridgeSpectateRouter.CONNECT_MARGIN_MILLIS);
        CompletableFuture<Boolean> arrived = router.connect(staff, "survival");

        assertThrows(TimeoutException.class, () -> arrived.get(200, TimeUnit.MILLISECONDS));
        sessions.current.put(STAFF, new FakeBridgeSession(STAFF, "survival"));
        connected.complete(result(ConnectionRequestBuilder.Status.SUCCESS));

        assertTrue(arrived.join());
    }

    @Test
    void aConnectThatNeverCompletesFails() {
        router = new BridgeSpectateRouter(proxy, sessions, 10_000, 20);
        CompletableFuture<Boolean> arrived = router.connect(staff, "survival");

        assertFalse(arrived.join());
        sessions.current.put(STAFF, new FakeBridgeSession(STAFF, "survival"));
        connected.complete(result(ConnectionRequestBuilder.Status.SUCCESS));
        assertFalse(arrived.join());
    }

    @Test
    void unknownServerFails() {
        assertFalse(router.connect(staff, "missing").join());
    }

    @Test
    void teleportToPlayerRidesTheCurrentSession() {
        FakeBridgeSession session = new FakeBridgeSession(STAFF, "survival");
        sessions.current.put(STAFF, session);

        CompletableFuture<Boolean> teleported = router.teleportToPlayer(staff, target);
        session.requests.getFirst().complete(true);

        assertEquals(List.of(new TeleportToPlayer(1, TARGET)), session.sent);
        assertTrue(teleported.join());
    }

    @Test
    void teleportWithoutTheCapabilityFails() {
        FakeBridgeSession session = new FakeBridgeSession(STAFF, "survival");
        session.capabilities.clear();
        sessions.current.put(STAFF, session);

        assertFalse(router.teleportToPlayer(staff, target).join());
        assertTrue(session.sent.isEmpty());
    }

    @Test
    void playerStateComesFromTheCompanionWithATeleportableWorld() {
        FakeBridgeSession session = new FakeBridgeSession(STAFF, "survival");
        sessions.current.put(STAFF, session);
        UUID world = UUID.randomUUID();

        CompletableFuture<@Nullable ServerState> state = router.playerState(staff);
        assertEquals(List.of(new PlayerState(1)), session.sent);
        session.exchanges.getFirst().complete(new PlayerStateResult(1, 1, world, 4.5, 70, -8, 90f, 15f));

        ServerState read = state.join();
        assertEquals(GameMode.CREATIVE, read.gameMode());
        assertEquals(4.5, read.location().getX());
        assertEquals(-8, read.location().getZ());
        assertEquals(90f, read.location().getYaw());
        BackendWorld backend =
                assertInstanceOf(BackendWorld.class, read.location().getWorld());
        assertEquals(world, backend.getUID());
        ServerConnection here = mock(ServerConnection.class);
        when(here.getServerInfo()).thenReturn(new ServerInfo("survival", new InetSocketAddress(25566)));
        ServerConnection elsewhere = mock(ServerConnection.class);
        when(elsewhere.getServerInfo()).thenReturn(new ServerInfo("lobby", new InetSocketAddress(25567)));
        assertEquals(world, backend.uidOn(here));
        assertNull(backend.uidOn(elsewhere));
    }

    @Test
    void playerStateIsUnknownWhenTheCompanionCannotAnswer() {
        FakeBridgeSession session = new FakeBridgeSession(STAFF, "survival");
        sessions.current.put(STAFF, session);

        CompletableFuture<@Nullable ServerState> refused = router.playerState(staff);
        session.exchanges.getFirst().complete(new Result(1, false));
        assertNull(refused.join());

        CompletableFuture<@Nullable ServerState> timedOut = router.playerState(staff);
        session.exchanges.getLast().complete(null);
        assertNull(timedOut.join());

        session.capabilities.clear();
        assertNull(router.playerState(staff).join());
        sessions.current.clear();
        assertNull(router.playerState(staff).join());
    }

    private static ConnectionRequestBuilder.Result result(ConnectionRequestBuilder.Status status) {
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.getStatus()).thenReturn(status);
        when(result.isSuccessful()).thenReturn(status == ConnectionRequestBuilder.Status.SUCCESS);
        return result;
    }

    private static final class Sessions implements BridgeSessions {
        final Map<UUID, BridgeSession> current = new HashMap<>();

        @Override
        public @Nullable BridgeSession current(UUID player) {
            return current.get(player);
        }

        @Override
        public void addListener(BridgeMessageListener listener) {}
    }
}
