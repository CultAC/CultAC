package ac.cult.cultac.platform.velocity.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bridge.protocol.BridgeAuth;
import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Auth;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Challenge;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Close;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Hello;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerStateResult;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reject;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.World;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BridgeServiceTest {
    private static final byte[] SECRET = "forwarding-secret".getBytes(StandardCharsets.UTF_8);
    private static final long CAPABILITIES = ServerCapability.mask(EnumSet.of(ServerCapability.WORLD_INFO));

    private final DefaultEventLoop loop = new DefaultEventLoop();
    private final UUID playerId = UUID.randomUUID();
    private final FakeBackend backend = new FakeBackend("lobby");
    private final RecordingListener listener = new RecordingListener();
    private BridgeService service = service(1000, 1000);

    @AfterEach
    void shutdown() throws Exception {
        service.close();
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).await(5, TimeUnit.SECONDS);
    }

    private BridgeService service(long helloRetryMillis, long readyTimeoutMillis) {
        return service(helloRetryMillis, readyTimeoutMillis, System::nanoTime);
    }

    private BridgeService service(long helloRetryMillis, long readyTimeoutMillis, LongSupplier clock) {
        var created = new BridgeService(
                mock(ProxyServer.class),
                new Object(),
                player -> {
                    throw new IllegalStateException("No Velocity channel in unit tests");
                },
                Logger.getLogger("BridgeServiceTest"),
                helloRetryMillis,
                readyTimeoutMillis,
                clock);
        created.enable(SECRET, server -> true);
        created.addListener(listener);
        return created;
    }

    /** A service whose players all use {@code channel}, with backends in PLAY while {@code inPlay} holds. */
    private BridgeService service(Channel channel, AtomicBoolean inPlay, Logger logger) {
        var created = new BridgeService(
                mock(ProxyServer.class), new Object(), player -> channel, logger, 1000, 1000, System::nanoTime);
        created.enable(SECRET, server -> inPlay.get());
        created.addListener(listener);
        return created;
    }

    private static Logger rejectionsTo(BlockingQueue<String> warnings) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().contains("rejected the bridge")) warnings.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return logger;
    }

    private ProxyBridgeSession open(Object connection, FakeBackend target) throws Exception {
        return loop.submit(() -> service.open(playerId, connection, target)).get(5, TimeUnit.SECONDS);
    }

    /** Plays the companion's side of the handshake with {@code secret}; returns the AUTH it received. */
    private Auth handshake(FakeBackend target, byte[] secret, boolean ready) throws Exception {
        var hello = (Hello) target.next();
        assertEquals(BridgeProtocol.VERSION, hello.version());
        assertEquals(playerId, hello.player());
        byte[] backendNonce = BridgeAuth.nonce();
        service.receiveFrame(playerId, frame(new Challenge(BridgeProtocol.VERSION, backendNonce)));
        var auth = (Auth) target.next();
        if (ready
                && BridgeAuth.verify(
                        BridgeAuth.hmac(secret, playerId, hello.proxyNonce(), backendNonce), auth.hmac())) {
            service.receiveFrame(
                    playerId,
                    frame(new Ready(
                            BridgeAuth.backendHmac(secret, playerId, backendNonce, hello.proxyNonce()),
                            CAPABILITIES,
                            "Paper 26.3",
                            UUID.randomUUID(),
                            "world",
                            0b1)));
        }
        return auth;
    }

    private BridgeSession readySession() throws Exception {
        open(new Object(), backend);
        handshake(backend, SECRET, true);
        await(() ->
                service.current(playerId) != null && service.current(playerId).isReady());
        return service.current(playerId);
    }

    @Test
    void handshakeReachesReadyAndMirrorsBackendState() throws Exception {
        var session = readySession();
        assertTrue(session.has(ServerCapability.WORLD_INFO));
        assertFalse(session.has(ServerCapability.CLEAR_ACTIVE_ITEM));
        assertFalse(session.has(ServerCapability.TELEPORT));
        assertEquals("world", session.worldName());
        assertEquals(0b1, session.listeners());
        assertEquals(1, listener.ready);

        UUID nether = UUID.randomUUID();
        service.receiveFrame(playerId, frame(new World(nether, "world_nether")));
        await(() -> "world_nether".equals(session.worldName()));
        assertEquals(nether, session.worldUid());
        await(() -> listener.messages >= 1);
    }

    @Test
    void readySendsOutsidePlayQueueThenFlushInOrder() throws Exception {
        var session = readySession();
        backend.inPlay = false;
        session.send(new BridgeMessage.SetGameMode(BridgeMessage.SetGameMode.CREATIVE));
        session.send(new BridgeMessage.CloseInventory());
        assertNull(backend.frames.poll(100, TimeUnit.MILLISECONDS));

        backend.inPlay = true;
        onLoop(() -> session.send(
                new BridgeMessage.ClearActiveItem(BridgeMessage.ClearActiveItem.OFF_HAND, "minecraft:shield")));
        assertInstanceOf(BridgeMessage.SetGameMode.class, backend.next());
        assertInstanceOf(BridgeMessage.CloseInventory.class, backend.next());
        assertInstanceOf(BridgeMessage.ClearActiveItem.class, backend.next());
    }

    @Test
    void clearOutsidePlayIsDroppedNotQueued() throws Exception {
        var session = readySession();
        backend.inPlay = false;
        onLoop(() -> session.send(
                new BridgeMessage.ClearActiveItem(BridgeMessage.ClearActiveItem.OFF_HAND, "minecraft:shield")));
        session.send(new BridgeMessage.CloseInventory());
        assertNull(backend.frames.poll(100, TimeUnit.MILLISECONDS));

        backend.inPlay = true;
        session.send(new BridgeMessage.SetGameMode(BridgeMessage.SetGameMode.CREATIVE));
        assertInstanceOf(BridgeMessage.CloseInventory.class, backend.next());
        assertInstanceOf(BridgeMessage.SetGameMode.class, backend.next());
        assertNull(backend.frames.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void clearFromOffTheLoopIsDropped() throws Exception {
        var session = readySession();
        session.send(new BridgeMessage.ClearActiveItem(BridgeMessage.ClearActiveItem.OFF_HAND, "minecraft:shield"));
        onLoop(() -> session.send(new BridgeMessage.CloseInventory()));
        assertInstanceOf(BridgeMessage.CloseInventory.class, backend.next());
        assertNull(backend.frames.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void challengeFromAnOlderCompanionIsRefused() throws Exception {
        var session = open(new Object(), backend);
        backend.next();
        service.receiveFrame(playerId, frame(new Challenge(BridgeProtocol.VERSION - 1, BridgeAuth.nonce())));

        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertNull(backend.frames.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, listener.ready);
        assertNull(service.current(playerId));
    }

    private void onLoop(Runnable task) throws Exception {
        loop.submit(task).get(5, TimeUnit.SECONDS);
    }

    @Test
    void closingTheServiceTellsReadyCompanions() throws Exception {
        readySession();
        service.close();
        assertEquals(new Close("proxy shutting down"), backend.next());
    }

    @Test
    void rejectReasonsAreLoggedOncePerServerAndSanitised() throws Exception {
        var warnings = new LinkedBlockingQueue<String>();
        service.close();
        service = service(null, new AtomicBoolean(true), rejectionsTo(warnings));
        for (int i = 0; i < 1000; i++) {
            open(new Object(), backend);
            backend.next();
            String reason = i == 0 ? "bad\nline\u001b[31m" + "x".repeat(400) : "reason " + i;
            service.receiveFrame(playerId, frame(new Reject(reason)));
        }
        loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
        String logged = warnings.poll(5, TimeUnit.SECONDS);
        assertNotNull(logged);
        String reason = logged.substring(logged.indexOf("bridge: ") + "bridge: ".length());
        assertEquals(BridgeService.MAX_LOGGED_REASON_CHARS, reason.length());
        assertTrue(reason.startsWith("bad?line?[31m"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void consoleAndPlayersHaveSeparateControlBudgets() throws Exception {
        var now = new AtomicLong();
        service.close();
        service = service(1000, 1000, now::get);
        var session = readySession();
        var outcomes = loop.submit(() -> {
                    List<Boolean> admitted = new ArrayList<>();
                    for (int i = 0; i < ControlBudget.BURST + 1; i++) admitted.add(session.admitControl(false));
                    admitted.add(session.admitControl(true));
                    now.addAndGet(ControlBudget.REFILL_NANOS);
                    admitted.add(session.admitControl(false));
                    admitted.add(session.admitControl(false));
                    return admitted;
                })
                .get(5, TimeUnit.SECONDS);
        for (int i = 0; i < ControlBudget.BURST; i++) assertTrue(outcomes.get(i), "frame " + i);
        assertFalse(outcomes.get(ControlBudget.BURST));
        assertTrue(outcomes.get(ControlBudget.BURST + 1));
        assertTrue(outcomes.get(ControlBudget.BURST + 2));
        assertFalse(outcomes.get(ControlBudget.BURST + 3));
    }

    @Test
    void rejectIsLoggedAgainAfterTheServerWasReady() throws Exception {
        var warnings = new LinkedBlockingQueue<String>();
        service.close();
        service = service(null, new AtomicBoolean(true), rejectionsTo(warnings));
        open(new Object(), backend);
        backend.next();
        service.receiveFrame(playerId, frame(new Reject("companion disabled")));
        assertNotNull(warnings.poll(5, TimeUnit.SECONDS));

        readySession();
        service.receiveFrame(playerId, frame(new Reject("Bridge protocol 1 is not supported")));
        String logged = warnings.poll(5, TimeUnit.SECONDS);
        assertNotNull(logged);
        assertTrue(logged.endsWith("Bridge protocol 1 is not supported"), logged);
    }

    @Test
    void configurationThenLoginOnTheSameBackendOpensAFreshSession() throws Exception {
        var inPlay = new AtomicBoolean(true);
        var channel = channel();
        service.close();
        service = service(channel, inPlay, Logger.getLogger("BridgeServiceTest"));
        var frames = new LinkedBlockingQueue<BridgeMessage>();
        var player = mock(Player.class);
        var server = backendConnection(player, "lobby", frames);
        var current = new AtomicReference<ServerConnection>(server);
        when(player.getCurrentServer()).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        var first = loop.submit(
                        () -> service.open(playerId, server, new VelocityBackend(server, channel, s -> inPlay.get())))
                .get(5, TimeUnit.SECONDS);
        completeHandshake(frames);
        await(() -> first.isReady() && listener.ready == 1);

        inPlay.set(false);
        service.endSession(sendEvent(), true);
        await(() -> first.state() == BridgeSession.State.CLOSED);
        await(() -> listener.closed == 1);
        service.rejoinedWorld(sendEvent());
        assertNull(frames.poll(3 * BridgeService.REOPEN_POLL_MILLIS, TimeUnit.MILLISECONDS));

        inPlay.set(true);
        completeHandshake(frames);
        var reopened = service.current(playerId);
        assertNotNull(reopened);
        assertNotSame(first, reopened);
        await(() -> reopened.isReady() && listener.ready == 2);
        assertTrue(reopened.has(ServerCapability.WORLD_INFO));
        loop.submit(() -> reopened.send(new BridgeMessage.CloseInventory())).get(5, TimeUnit.SECONDS);
        assertInstanceOf(BridgeMessage.CloseInventory.class, frames.poll(5, TimeUnit.SECONDS));
    }

    /** Plays the companion's side of the handshake for the HELLO next in {@code frames}. */
    private void completeHandshake(BlockingQueue<BridgeMessage> frames) throws Exception {
        var hello = assertInstanceOf(Hello.class, frames.poll(5, TimeUnit.SECONDS));
        byte[] backendNonce = BridgeAuth.nonce();
        service.receiveFrame(playerId, frame(new Challenge(BridgeProtocol.VERSION, backendNonce)));
        assertInstanceOf(Auth.class, frames.poll(5, TimeUnit.SECONDS));
        service.receiveFrame(
                playerId,
                frame(new Ready(
                        BridgeAuth.backendHmac(SECRET, playerId, backendNonce, hello.proxyNonce()),
                        CAPABILITIES,
                        "Paper 26.3",
                        UUID.randomUUID(),
                        "world",
                        0b1)));
    }

    @Test
    void adoptedDuringConfigurationReopensAtLogin() throws Exception {
        var inPlay = new AtomicBoolean(false);
        var channel = channel();
        service.close();
        service = service(channel, inPlay, Logger.getLogger("BridgeServiceTest"));
        var frames = new LinkedBlockingQueue<BridgeMessage>();
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        var server = backendConnection(player, "lobby", frames);
        when(player.getCurrentServer()).thenReturn(Optional.of(server));

        service.adopted(player);
        inPlay.set(true);
        service.rejoinedWorld(sendEvent());

        completeHandshake(frames);
        await(() ->
                service.current(playerId) != null && service.current(playerId).isReady());
    }

    @Test
    void reopenAfterAServerSwitchIsDropped() throws Exception {
        var inPlay = new AtomicBoolean(true);
        var channel = channel();
        service.close();
        service = service(channel, inPlay, Logger.getLogger("BridgeServiceTest"));
        var frames = new LinkedBlockingQueue<BridgeMessage>();
        var player = mock(Player.class);
        var server = backendConnection(player, "lobby", frames);
        var current = new AtomicReference<ServerConnection>(server);
        when(player.getCurrentServer()).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        var first = loop.submit(
                        () -> service.open(playerId, server, new VelocityBackend(server, channel, s -> inPlay.get())))
                .get(5, TimeUnit.SECONDS);
        completeHandshake(frames);
        await(() -> first.isReady());

        service.endSession(sendEvent(), true);
        await(() -> first.state() == BridgeSession.State.CLOSED);
        current.set(backendConnection(player, "survival", new LinkedBlockingQueue<>()));
        service.rejoinedWorld(sendEvent());

        assertNull(frames.poll(5 * BridgeService.REOPEN_POLL_MILLIS, TimeUnit.MILLISECONDS));
        assertNull(service.current(playerId));
    }

    private Channel channel() {
        var channel = mock(Channel.class);
        when(channel.eventLoop()).thenReturn(loop);
        when(channel.isActive()).thenReturn(true);
        return channel;
    }

    private static ServerConnection backendConnection(Player player, String name, BlockingQueue<BridgeMessage> frames) {
        var server = mock(ServerConnection.class, RETURNS_DEEP_STUBS);
        when(server.getServerInfo().getName()).thenReturn(name);
        when(server.getPlayer()).thenReturn(player);
        when(server.sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class)))
                .thenAnswer(invocation -> {
                    frames.add(BridgeCodec.decode(Unpooled.wrappedBuffer(invocation.getArgument(1, byte[].class))));
                    return true;
                });
        return server;
    }

    private PacketSendEvent<?> sendEvent() {
        var user = mock(User.class);
        when(user.getUUID()).thenReturn(playerId);
        PacketSendEvent<?> event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        return event;
    }

    @Test
    void printableCutsAndMasksControlCharactersAndSectionSigns() {
        assertEquals("a?b?", BridgeService.printable("a\tb\u0000cdef", 4));
        assertEquals("?cred?x", BridgeService.printable("§cred\nx", 256));
        assertEquals("short", BridgeService.printable("short", 256));
    }

    @Test
    void messagesQueueUntilReadyThenFlushInOrder() throws Exception {
        var session = open(new Object(), backend);
        session.send(new BridgeMessage.CloseInventory());
        handshake(backend, SECRET, true);
        assertInstanceOf(BridgeMessage.CloseInventory.class, backend.next());
        session.send(new BridgeMessage.CloseInventory());
        assertInstanceOf(BridgeMessage.CloseInventory.class, backend.next());
    }

    @Test
    void wrongSecretNeverReachesReadyAndTimesOut() throws Exception {
        service.close();
        service = service(1000, 200);
        var session = open(new Object(), backend);
        var auth = handshake(backend, "another-secret".getBytes(StandardCharsets.UTF_8), true);
        assertEquals(BridgeProtocol.HMAC_BYTES, auth.hmac().length);
        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertNull(service.current(playerId));
        assertEquals(0, listener.ready);
        assertEquals(0, listener.closed);
    }

    @Test
    void helloIsResentThreeTimesWithTheSameNonce() throws Exception {
        service.close();
        service = service(20, 5000);
        open(new Object(), backend);
        var first = (Hello) backend.next();
        for (int i = 0; i < ProxyBridgeSession.HELLO_RETRIES; i++) {
            assertArrayEquals(first.proxyNonce(), ((Hello) backend.next()).proxyNonce());
        }
        assertNull(backend.frames.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void readyWithAWrongBackendProofNeverBecomesReady() throws Exception {
        var session = open(new Object(), backend);
        var hello = (Hello) backend.next();
        byte[] backendNonce = BridgeAuth.nonce();
        service.receiveFrame(playerId, frame(new Challenge(BridgeProtocol.VERSION, backendNonce)));
        assertInstanceOf(Auth.class, backend.next());
        byte[] forged = BridgeAuth.hmac(SECRET, playerId, hello.proxyNonce(), backendNonce);
        service.receiveFrame(
                playerId, frame(new Ready(forged, CAPABILITIES, "Paper 26.3", UUID.randomUUID(), "world", 0)));

        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertInstanceOf(Close.class, backend.next());
        assertNull(service.current(playerId));
        assertEquals(0, listener.ready);
        assertFalse(session.has(ServerCapability.WORLD_INFO));
    }

    @Test
    void readyWithoutABackendProofNeverBecomesReady() throws Exception {
        var session = open(new Object(), backend);
        backend.next();
        service.receiveFrame(playerId, frame(new Challenge(BridgeProtocol.VERSION, BridgeAuth.nonce())));
        assertInstanceOf(Auth.class, backend.next());
        ByteBuf legacy = Unpooled.buffer();
        legacy.writeByte(Ready.TYPE);
        legacy.writeByte(1);
        legacy.writeByte(0);
        legacy.writeLong(1L);
        legacy.writeLong(2L);
        legacy.writeByte(0);
        legacy.writeByte(0);
        service.receiveFrame(playerId, ByteBufUtil.getBytes(legacy));
        legacy.release();

        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertInstanceOf(Close.class, backend.next());
        assertEquals(0, listener.ready);
    }

    @Test
    void readyTimeoutAfterAuthTellsTheCompanion() throws Exception {
        service.close();
        service = service(1000, 200);
        var session = open(new Object(), backend);
        backend.next();
        service.receiveFrame(playerId, frame(new Challenge(BridgeProtocol.VERSION, BridgeAuth.nonce())));
        assertInstanceOf(Auth.class, backend.next());
        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertInstanceOf(Close.class, backend.next());
    }

    @Test
    void timeoutBeforeAnyChallengeSendsNoClose() throws Exception {
        service.close();
        service = service(1000, 100);
        var session = open(new Object(), backend);
        backend.next();
        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertNull(backend.frames.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void rejectAfterReadyClosesTheSession() throws Exception {
        var session = readySession();
        service.receiveFrame(playerId, frame(new Reject("companion disabled")));
        await(() -> session.state() == BridgeSession.State.CLOSED);
        await(() -> listener.closed == 1);
        assertNull(backend.frames.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void duplicateHelloAnsweredTwiceAuthenticatesOnce() throws Exception {
        service.close();
        service = service(50, 1000);
        var session = open(new Object(), backend);
        var hello = (Hello) backend.next();
        assertArrayEquals(hello.proxyNonce(), ((Hello) backend.next()).proxyNonce());
        byte[] backendNonce = BridgeAuth.nonce();
        var challenge = frame(new Challenge(BridgeProtocol.VERSION, backendNonce));
        service.receiveFrame(playerId, challenge);
        service.receiveFrame(playerId, challenge);

        BridgeMessage next;
        do next = backend.next();
        while (next instanceof Hello);
        var auth = (Auth) next;
        assertTrue(BridgeAuth.verify(BridgeAuth.hmac(SECRET, playerId, hello.proxyNonce(), backendNonce), auth.hmac()));
        assertNull(backend.frames.poll(200, TimeUnit.MILLISECONDS));

        service.receiveFrame(
                playerId,
                frame(new Ready(
                        BridgeAuth.backendHmac(SECRET, playerId, backendNonce, hello.proxyNonce()),
                        CAPABILITIES,
                        "Paper 26.3",
                        UUID.randomUUID(),
                        "world",
                        0)));
        await(() -> session.isReady() && listener.ready == 1);
    }

    @Test
    void rejectClosesTheSession() throws Exception {
        var session = open(new Object(), backend);
        backend.next();
        service.receiveFrame(playerId, frame(new Reject("protocol 2")));
        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertNull(service.current(playerId));
    }

    @Test
    void requestsCompleteFromResultsAndFalseOnTimeout() throws Exception {
        var session = readySession();
        CompletableFuture<Boolean> answered = session.request(PlayerState::new, 5000);
        var query = (PlayerState) backend.next();
        assertEquals(1, query.requestId());
        service.receiveFrame(playerId, frame(new Result(query.requestId(), true)));
        assertTrue(answered.get(5, TimeUnit.SECONDS));

        CompletableFuture<Boolean> unanswered = session.request(PlayerState::new, 50);
        assertEquals(2, ((PlayerState) backend.next()).requestId());
        assertFalse(unanswered.get(5, TimeUnit.SECONDS));
    }

    @Test
    void playerStateExchangeCompletesWithItsReply() throws Exception {
        var session = readySession();
        var world = UUID.randomUUID();
        CompletableFuture<BridgeMessage> answered = session.exchange(PlayerState::new, 5000);
        var query = (PlayerState) backend.next();
        var reply = new PlayerStateResult(query.requestId(), 1, world, 1.5, 64, -2.5, 90f, 10f);
        service.receiveFrame(playerId, frame(reply));
        assertEquals(reply, answered.get(5, TimeUnit.SECONDS));

        CompletableFuture<BridgeMessage> refused = session.exchange(PlayerState::new, 5000);
        int second = ((PlayerState) backend.next()).requestId();
        service.receiveFrame(playerId, frame(new Result(second, false)));
        assertEquals(new Result(second, false), refused.get(5, TimeUnit.SECONDS));

        CompletableFuture<BridgeMessage> unanswered = session.exchange(PlayerState::new, 50);
        backend.next();
        assertNull(unanswered.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closingCompletesPendingRequestsFalse() throws Exception {
        var session = readySession();
        CompletableFuture<Boolean> pending = session.request(PlayerState::new, 60_000);
        backend.next();
        service.close();
        assertFalse(pending.get(5, TimeUnit.SECONDS));
        assertEquals(BridgeSession.State.CLOSED, session.state());
        await(() -> listener.closed == 1);
        assertFalse(session.request(PlayerState::new, 60_000).get(5, TimeUnit.SECONDS));
    }

    @Test
    void serverSwitchClosesTheOldSessionAndHandshakesTheNewOne() throws Exception {
        var first = readySession();
        var next = new FakeBackend("survival");
        var second = open(new Object(), next);
        assertEquals(BridgeSession.State.CLOSED, first.state());
        await(() -> listener.closed == 1);
        assertInstanceOf(Hello.class, next.next());
        assertSame(second, service.current(playerId));
        assertEquals("survival", second.serverName());
    }

    @Test
    void malformedFrameClosesTheSession() throws Exception {
        var session = readySession();
        service.receiveFrame(playerId, new byte[] {(byte) World.TYPE});
        await(() -> session.state() == BridgeSession.State.CLOSED);
        assertEquals(new Close("malformed frame"), backend.next());
        await(() -> listener.closed == 1);
    }

    @Test
    void failedWriteEndsTheConnection() throws Exception {
        var session = readySession();
        backend.disconnected = true;
        session.send(new BridgeMessage.CloseInventory());
        await(() -> session.state() == BridgeSession.State.CLOSED);
        await(() -> listener.closed == 1);
    }

    @Test
    void clientSentBridgePayloadIsAlwaysStripped() {
        @SuppressWarnings("unchecked")
        PacketReceiveEvent<ServerboundCustomPayload> event = mock(PacketReceiveEvent.class);
        service.stripClientPayload(event, new ServerboundCustomPayload(BridgeProtocol.CHANNEL, new byte[] {1}));
        verify(event).cancelFinally();

        @SuppressWarnings("unchecked")
        PacketReceiveEvent<ServerboundCustomPayload> other = mock(PacketReceiveEvent.class);
        service.stripClientPayload(other, new ServerboundCustomPayload("minecraft:brand", new byte[] {1}));
        verify(other, never()).setCancelled(anyBoolean());
        verify(other, never()).cancelFinally();
    }

    @Test
    void backendBridgePayloadIsConsumedAndDispatched() throws Exception {
        open(new Object(), backend);
        var hello = (Hello) backend.next();
        var user = mock(User.class);
        when(user.getUUID()).thenReturn(playerId);

        @SuppressWarnings("unchecked")
        PacketSendEvent<ClientboundCustomPayload> event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        service.interceptBackendPayload(
                event,
                new ClientboundCustomPayload(
                        BridgeProtocol.CHANNEL, frame(new Challenge(BridgeProtocol.VERSION, BridgeAuth.nonce()))));
        verify(event).cancelFinally();
        assertInstanceOf(Auth.class, backend.next());
        assertNotNull(hello);

        @SuppressWarnings("unchecked")
        PacketSendEvent<ClientboundCustomPayload> other = mock(PacketSendEvent.class);
        when(other.getUser()).thenReturn(user);
        service.interceptBackendPayload(other, new ClientboundCustomPayload("minecraft:brand", new byte[] {1}));
        verify(other, never()).setCancelled(anyBoolean());
    }

    @Test
    void backendBridgePayloadStaysCancelledWhenItsHandlingFails() {
        var user = mock(User.class);
        when(user.getUUID()).thenThrow(new IllegalStateException("handler failure"));
        @SuppressWarnings("unchecked")
        PacketSendEvent<ClientboundCustomPayload> event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);

        service.interceptBackendPayload(event, new ClientboundCustomPayload(BridgeProtocol.CHANNEL, new byte[] {1}));
        verify(event).cancelFinally();
        verify(event, never()).setCancelled(false);
    }

    @Test
    void installedServiceIsOnlyVisibleUntilReset() {
        try {
            VelocityBridge.install(service);
            assertSame(service, VelocityBridge.sessions());
            assertNull(VelocityBridge.sessions().current(playerId));
        } finally {
            VelocityBridge.install(BridgeSessions.NONE);
        }
    }

    private static byte[] frame(BridgeMessage message) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            BridgeCodec.encode(buffer, message);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Condition not reached within 5 s");
            Thread.sleep(5);
        }
    }

    private final class FakeBackend implements BridgeBackend {
        final BlockingQueue<BridgeMessage> frames = new LinkedBlockingQueue<>();
        private final String name;
        volatile boolean disconnected;
        volatile boolean inPlay = true;

        FakeBackend(String name) {
            this.name = name;
        }

        BridgeMessage next() throws InterruptedException {
            var message = frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "No frame written to " + name);
            return message;
        }

        @Override
        public EventLoop eventLoop() {
            return loop;
        }

        @Override
        public boolean inPlay() {
            return inPlay;
        }

        @Override
        public void write(byte[] frame) {
            if (disconnected) throw new IllegalStateException("disconnected");
            frames.add(BridgeCodec.decode(Unpooled.wrappedBuffer(frame)));
        }

        @Override
        public String serverName() {
            return name;
        }
    }

    private static final class RecordingListener implements BridgeMessageListener {
        volatile int ready;
        volatile int messages;
        volatile int closed;

        @Override
        public void onReady(BridgeSession session) {
            ready++;
        }

        @Override
        public void onMessage(BridgeSession session, BridgeMessage message) {
            messages++;
        }

        @Override
        public void onClosed(BridgeSession session) {
            closed++;
        }
    }
}
