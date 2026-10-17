package ac.cult.cultac.platform.velocity.bridge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertToggle;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckCatalog;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Metrics;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reloaded;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Setback;
import ac.cult.cultac.bridge.protocol.BridgeMessage.UserInfo;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.FakeBridgeSession;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.HostContext;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.alerts.AlertManager;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.event.events.GrimReloadEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.internal.event.OptimizedEventBus;
import ac.grim.grimac.internal.plugin.resolver.GrimExtensionManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeApiPublisherTest {
    private final UUID playerId = UUID.randomUUID();
    private final GrimUser user = mock(GrimUser.class);
    private final GrimUser otherUser = mock(GrimUser.class);
    private final AbstractCheck first = check("Reach");
    private final AbstractCheck second = check("Timer");
    private final AlertManager alerts = mock(AlertManager.class);
    private final GrimPlugin plugin = mock(GrimPlugin.class);
    private EventBus bus;
    private BridgeApiPublisher publisher;

    @BeforeEach
    void setUp() {
        when(user.getUniqueId()).thenReturn(playerId);
        when(user.getBrand()).thenReturn("vanilla");
        when(user.getVersionName()).thenReturn("26.3");
        doReturnChecks();
        when(alerts.hasAlertsEnabled(user)).thenReturn(true);
        bus = new OptimizedEventBus(new GrimExtensionManager());
        publisher = new BridgeApiPublisher(
                id -> id.equals(playerId) ? user : id.equals(otherUser.getUniqueId()) ? otherUser : null,
                alerts,
                bus,
                plugin,
                ignored -> Runnable::run,
                Logger.getLogger("test"));
        publisher.subscribe();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doReturnChecks() {
        when(user.getChecks()).thenReturn((List) List.of(first, second));
    }

    private static AbstractCheck check(String name) {
        AbstractCheck check = mock(AbstractCheck.class);
        when(check.getCheckName()).thenReturn(name);
        when(check.getConfigName()).thenReturn(name);
        when(check.getStableKey()).thenReturn(name.toLowerCase());
        when(check.isEnabled()).thenReturn(true);
        return check;
    }

    @Test
    void readySendsUserInfoCatalogAndAlertState() {
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);

        UserInfo info = session.first(UserInfo.class);
        assertEquals("vanilla", info.brand());
        assertEquals("26.3", info.versionName());
        CheckCatalog catalog = session.first(CheckCatalog.class);
        assertEquals(2, catalog.checks().size());
        assertEquals(0, catalog.checks().get(0).idx());
        assertEquals("Reach", catalog.checks().get(0).checkName());
        assertEquals(1, catalog.checks().get(1).idx());
        assertEquals("timer", catalog.checks().get(1).stableKey());
        assertEquals(AlertState.ALERTS, session.first(AlertState.class).bits());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void largeCatalogIsSplitIntoChunksUnderThePayloadCap() {
        List<AbstractCheck> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            AbstractCheck check = check("Check" + i);
            when(check.getDescription()).thenReturn("é".repeat(5000));
            many.add(check);
        }
        when(user.getChecks()).thenReturn((List) many);
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);

        List<CheckCatalog> chunks = session.sent.stream()
                .filter(CheckCatalog.class::isInstance)
                .map(CheckCatalog.class::cast)
                .toList();
        assertTrue(chunks.size() > 1);
        int next = 0;
        for (CheckCatalog chunk : chunks) {
            ByteBuf buffer = Unpooled.buffer();
            BridgeCodec.encode(buffer, chunk);
            assertTrue(buffer.readableBytes() < 32767, "chunk of " + buffer.readableBytes() + " bytes");
            buffer.release();
            for (var info : chunk.checks()) assertEquals(next++, info.idx());
            assertEquals(chunk == chunks.getLast(), chunk.last());
        }
        assertEquals(150, next);
    }

    @Test
    void backendWithoutGrimApiGetsNothing() {
        FakeBridgeSession session = fake(playerId, false, ListenerKind.FLAG.flag());
        publisher.onReady(session);
        bus.get(FlagEvent.class).fire(user, second, "verbose");
        assertTrue(session.sent.isEmpty());
    }

    @Test
    void flagIsSentOnlyWhenTheBackendListens() {
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);
        session.sent.clear();

        assertFalse(bus.get(FlagEvent.class).fire(user, second, "first"));
        assertTrue(session.sent.isEmpty());

        session.listeners = ListenerKind.FLAG.flag();
        publisher.onMessage(session, new Listeners(session.listeners));
        when(second.getViolations()).thenReturn(3.0);
        assertFalse(bus.get(FlagEvent.class).fire(user, second, "second"));
        Flag flag = session.first(Flag.class);
        assertEquals(1, flag.checkIdx());
        assertEquals(4.0, flag.violations());
        assertEquals("second", flag.verbose());
        assertFalse(flag.awaitVerdict());
        assertEquals(BridgeFrames.NOTIFY_FLAG_ID, flag.flagId());

        session.sent.clear();
        assertFalse(bus.get(FlagEvent.class).fire(user, first, "x".repeat(10000)));
        Flag capped = session.first(Flag.class);
        assertEquals(BridgeFrames.NOTIFY_FLAG_ID, capped.flagId());
        assertEquals(BridgeFrames.MAX_VERBOSE_CHARS, capped.verbose().length());
    }

    @Test
    void flagOwnedByAnArbiterIsNotNotified() {
        FakeBridgeSession session = fake(playerId, true, ListenerKind.FLAG.flag());
        publisher.onReady(session);
        session.sent.clear();
        publisher.flagOwnedBy(u -> u == user);

        assertFalse(bus.get(FlagEvent.class).fire(user, first, "verbose"));
        assertTrue(session.sent.isEmpty());
    }

    @Test
    void checkIndexFollowsTheSessionCatalog() {
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);

        assertEquals(0, publisher.checkIndex(session, first));
        assertEquals(1, publisher.checkIndex(session, second));
        assertEquals(-1, publisher.checkIndex(session, check("Fly")));
        assertEquals(-1, publisher.checkIndex(fake(playerId, true, 0), first));
    }

    @Test
    void flagFiredWithReloadHostHandlesIsSentWithItsCatalogIndex() throws ReflectiveOperationException {
        bindHost(new HandleHost().context());
        try {
            FakeBridgeSession session = fake(playerId, true, ListenerKind.FLAG.flag());
            publisher.onReady(session);
            session.sent.clear();
            GrimUser outwardUser = RuntimeEnvironment.outwardUser(user);
            AbstractCheck outwardCheck = RuntimeEnvironment.outwardCheck(second);
            assertNotSame(user, outwardUser);
            assertNotSame(second, outwardCheck);
            when(second.getViolations()).thenReturn(2.0);

            assertFalse(bus.get(FlagEvent.class)
                    .fire(outwardUser, outwardCheck, RuntimeEnvironment.outwardVerbose(() -> "wrapped")));
            Flag flag = session.first(Flag.class);
            assertEquals(1, flag.checkIdx());
            assertEquals(3.0, flag.violations());
            assertEquals("wrapped", flag.verbose());
            assertEquals(1, publisher.checkIndex(session, second));

            session.sent.clear();
            publisher.flagOwnedBy(u -> u == user);
            assertFalse(bus.get(FlagEvent.class).fire(outwardUser, outwardCheck, () -> "owned"));
            assertTrue(session.sent.isEmpty());
        } finally {
            bindHost(null);
        }
    }

    private static void bindHost(HostContext host) throws ReflectiveOperationException {
        Field context = RuntimeEnvironment.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(null, host);
    }

    /** Mirrors the reload host's API boundary: one cached delegating handle per object, verbose rendered eagerly. */
    private static final class HandleHost implements InvocationHandler {
        private final Map<Object, Object> handles = new IdentityHashMap<>();

        HostContext context() {
            return (HostContext) Proxy.newProxyInstance(
                    HostContext.class.getClassLoader(), new Class<?>[] {HostContext.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (!method.getName().equals("wrap")) throw new UnsupportedOperationException(method.getName());
            Object value = args[0];
            if (value instanceof Supplier<?> supplier) {
                String text = String.valueOf(supplier.get());
                return (Supplier<String>) () -> text;
            }
            return handles.computeIfAbsent(value, target -> {
                List<Class<?>> contracts = new ArrayList<>();
                for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                    for (Class<?> contract : type.getInterfaces()) {
                        if (contract.getName().startsWith("ac.grim.grimac.api.")) contracts.add(contract);
                    }
                }
                return Proxy.newProxyInstance(
                        BridgeApiPublisherTest.class.getClassLoader(),
                        contracts.toArray(Class<?>[]::new),
                        (handle, call, callArgs) -> call.getDeclaringClass() == Object.class
                                ? switch (call.getName()) {
                                    case "equals" -> handle == callArgs[0];
                                    case "hashCode" -> System.identityHashCode(handle);
                                    default -> "handle";
                                }
                                : call.invoke(target, callArgs));
            });
        }
    }

    @Test
    void flagCancelledByAProxyPluginIsNotSentAndStaysCancelled() {
        FakeBridgeSession session = fake(playerId, true, ListenerKind.FLAG.flag());
        publisher.onReady(session);
        session.sent.clear();
        bus.get(FlagEvent.class).onFlagSupplier(plugin, (u, c, v, cancelled) -> true, 0);

        assertTrue(bus.get(FlagEvent.class).fire(user, first, "verbose"));
        assertTrue(session.sent.isEmpty());
    }

    @Test
    void setbackIsSentOnlyWhenTheBackendListens() {
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);
        session.sent.clear();
        GrimPlayerSetbackEvent.Channel channel = bus.get(GrimPlayerSetbackEvent.class);

        channel.fire(user, 7, 1, 2, 3, 0L);
        assertTrue(session.sent.isEmpty());

        session.listeners = ListenerKind.SETBACK.flag();
        publisher.onMessage(session, new Listeners(session.listeners));
        channel.fire(user, 7, 1, 2, 3, 0L);
        assertEquals(new Setback(7, 1, 2, 3), session.first(Setback.class));
    }

    @Test
    void metricsAreSentOnReadyAndThenOnlyOnThresholdChanges() {
        when(user.getTransactionPing()).thenReturn(42);
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);
        assertEquals(42, session.first(Metrics.class).transactionPing());
        session.sent.clear();

        publisher.tick();
        assertTrue(session.sent.isEmpty());

        when(user.getLastTransactionSent()).thenReturn(1000);
        when(user.getLastTransactionReceived()).thenReturn(990);
        publisher.tick();
        assertTrue(session.sent.isEmpty());

        when(user.getTransactionPing()).thenReturn(46);
        publisher.tick();
        assertTrue(session.sent.isEmpty());

        when(user.getTransactionPing()).thenReturn(47);
        publisher.tick();
        assertEquals(new Metrics(47, 0, 0, 1000, 990), session.first(Metrics.class));
        session.sent.clear();

        when(user.getHorizontalSensitivity()).thenReturn(0.5);
        when(user.getLastTransactionSent()).thenReturn(1020);
        publisher.tick();
        assertEquals(new Metrics(47, 0.5, 0, 1020, 990), session.first(Metrics.class));
    }

    @Test
    void pingThresholdIsTheLargerOfFiveMillisAndTenPercent() {
        assertFalse(BridgeApiPublisher.pingMoved(42, 46));
        assertTrue(BridgeApiPublisher.pingMoved(42, 37));
        assertFalse(BridgeApiPublisher.pingMoved(200, 219));
        assertTrue(BridgeApiPublisher.pingMoved(200, 220));
        assertTrue(BridgeApiPublisher.pingMoved(0, 5));
    }

    @Test
    void proxyEventsAreSubscribedOnlyWhileAMirroredBackendListens() {
        FlagEvent.Channel flags = bus.get(FlagEvent.class);
        GrimPlayerSetbackEvent.Channel setbacks = bus.get(GrimPlayerSetbackEvent.class);
        assertTrue(flags.isEmpty());
        assertTrue(setbacks.isEmpty());

        publisher.onReady(fake(playerId, false, ListenerKind.FLAG.flag() | ListenerKind.SETBACK.flag()));
        FakeBridgeSession quiet = fake(playerId, true, 0);
        publisher.onReady(quiet);
        assertTrue(flags.isEmpty());
        assertTrue(setbacks.isEmpty());
        publisher.onClosed(quiet);

        FakeBridgeSession listening = fake(playerId, true, ListenerKind.FLAG.flag());
        publisher.onReady(listening);
        assertFalse(flags.isEmpty());
        assertTrue(setbacks.isEmpty());

        UUID otherId = UUID.randomUUID();
        when(otherUser.getUniqueId()).thenReturn(otherId);
        FakeBridgeSession other = fake(otherId, true, ListenerKind.FLAG.flag());
        publisher.onReady(other);
        publisher.onClosed(listening);
        assertFalse(flags.isEmpty());

        other.listeners = ListenerKind.SETBACK.flag();
        publisher.onMessage(other, new Listeners(other.listeners));
        assertTrue(flags.isEmpty());
        assertFalse(setbacks.isEmpty());

        publisher.onClosed(other);
        assertTrue(flags.isEmpty());
        assertTrue(setbacks.isEmpty());
    }

    @Test
    void closeUnsubscribesEverything() {
        publisher.onReady(fake(playerId, true, ListenerKind.FLAG.flag() | ListenerKind.SETBACK.flag()));
        publisher.close();
        assertTrue(bus.get(FlagEvent.class).isEmpty());
        assertTrue(bus.get(GrimPlayerSetbackEvent.class).isEmpty());
    }

    @Test
    void alertToggleAppliesThroughTheProxyAlertManager() {
        when(user.hasPermission("cult.verbose")).thenReturn(true);
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);
        session.sent.clear();

        publisher.onMessage(session, new AlertToggle(1, true, false));
        verify(alerts).setVerboseEnabled(user, true, false);
        assertInstanceOf(AlertState.class, session.sent.get(0));
    }

    @Test
    void rateLimitedAlertToggleIsNotAppliedAndTheStateCorrected() {
        when(user.hasPermission("cult.verbose")).thenReturn(true);
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);
        session.sent.clear();
        session.admitted = 0;

        publisher.onMessage(session, new AlertToggle(1, true, false));

        verify(alerts, never()).setVerboseEnabled(user, true, false);
        assertEquals(1, session.sent.size());
        assertInstanceOf(AlertState.class, session.sent.getFirst());
    }

    @Test
    void enablingWithoutPermissionIsRefused() {
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);
        session.sent.clear();

        publisher.onMessage(session, new AlertToggle(0, true, false));
        publisher.onMessage(session, new AlertToggle(1, true, false));
        publisher.onMessage(session, new AlertToggle(2, true, true));

        verify(user).hasPermission("cult.alerts");
        verify(user).hasPermission("cult.verbose");
        verify(user).hasPermission("cult.brand");
        verify(alerts, never()).setAlertsEnabled(user, true, false);
        verify(alerts, never()).setVerboseEnabled(user, true, false);
        verify(alerts, never()).setBrandsEnabled(user, true, true);
        assertEquals(
                List.of(
                        new AlertState(AlertState.ALERTS),
                        new AlertState(AlertState.ALERTS),
                        new AlertState(AlertState.ALERTS)),
                session.sent);
    }

    @Test
    void disablingNeedsNoPermission() {
        FakeBridgeSession session = fake(playerId, true, 0);
        publisher.onReady(session);

        publisher.onMessage(session, new AlertToggle(0, false, true));

        verify(alerts).setAlertsEnabled(user, false, true);
        verify(user, never()).hasPermission(anyString());
    }

    @Test
    void reloadAnnouncesOncePerBackendServer() {
        Map<UUID, GrimUser> users = new HashMap<>();
        List<FakeBridgeSession> sessions = new ArrayList<>();
        for (String server : List.of("lobby", "lobby", "lobby", "survival")) {
            GrimUser member = mock(GrimUser.class);
            UUID id = UUID.randomUUID();
            when(member.getUniqueId()).thenReturn(id);
            when(member.getBrand()).thenReturn("vanilla");
            when(member.getVersionName()).thenReturn("26.3");
            users.put(id, member);
            sessions.add(fake(id, true, ListenerKind.RELOAD.flag(), server));
        }
        EventBus reloadBus = new OptimizedEventBus(new GrimExtensionManager());
        var reloads = new BridgeApiPublisher(
                users::get, alerts, reloadBus, plugin, ignored -> Runnable::run, Logger.getLogger("test"));
        reloads.subscribe();
        for (FakeBridgeSession session : sessions) {
            reloads.onReady(session);
            session.sent.clear();
        }

        reloadBus.get(GrimReloadEvent.class).fire(true);

        long announced = sessions.stream()
                .flatMap(session -> session.sent.stream())
                .filter(Reloaded.class::isInstance)
                .count();
        assertEquals(2, announced);
        assertEquals(
                1,
                sessions.getLast().sent.stream()
                        .filter(Reloaded.class::isInstance)
                        .count());
        for (FakeBridgeSession session : sessions) {
            assertTrue(session.sent.stream().anyMatch(CheckCatalog.class::isInstance));
        }
    }

    @Test
    void reloadIsNotAnnouncedThroughAClosedSession() {
        Map<UUID, GrimUser> users = new HashMap<>();
        List<FakeBridgeSession> sessions = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            GrimUser member = mock(GrimUser.class);
            UUID id = UUID.randomUUID();
            when(member.getUniqueId()).thenReturn(id);
            when(member.getBrand()).thenReturn("vanilla");
            when(member.getVersionName()).thenReturn("26.3");
            users.put(id, member);
            sessions.add(fake(id, true, ListenerKind.RELOAD.flag(), "lobby"));
        }
        EventBus reloadBus = new OptimizedEventBus(new GrimExtensionManager());
        var reloads = new BridgeApiPublisher(
                users::get, alerts, reloadBus, plugin, ignored -> Runnable::run, Logger.getLogger("test"));
        reloads.subscribe();
        for (FakeBridgeSession session : sessions) reloads.onReady(session);
        sessions.forEach(session -> session.sent.clear());
        sessions.getFirst().state = BridgeSession.State.CLOSED;

        reloadBus.get(GrimReloadEvent.class).fire(false);

        assertTrue(sessions.getFirst().sent.stream().noneMatch(Reloaded.class::isInstance));
        assertEquals(new Reloaded(false), sessions.getLast().first(Reloaded.class));
    }

    @Test
    void closedSessionStopsMirroring() {
        FakeBridgeSession session = fake(playerId, true, ListenerKind.FLAG.flag());
        publisher.onReady(session);
        publisher.onClosed(session);
        session.sent.clear();

        bus.get(FlagEvent.class).fire(user, first, "verbose");
        publisher.tick();
        assertTrue(session.sent.isEmpty());
    }

    private static FakeBridgeSession fake(UUID playerId, boolean grimApi, int listeners) {
        return fake(playerId, grimApi, listeners, "lobby");
    }

    private static FakeBridgeSession fake(UUID playerId, boolean grimApi, int listeners, String serverName) {
        Set<ServerCapability> capabilities =
                grimApi ? EnumSet.of(ServerCapability.GRIM_API) : EnumSet.noneOf(ServerCapability.class);
        return new FakeBridgeSession(playerId, serverName, capabilities, listeners);
    }
}
