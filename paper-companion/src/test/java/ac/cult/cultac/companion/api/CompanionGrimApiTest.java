package ac.cult.cultac.companion.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertToggle;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckCatalog;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckInfo;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandExecute;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.UserInfo;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Verdict;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.companion.FakeBridge;
import ac.cult.cultac.companion.FakeSession;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import ac.grim.grimac.api.plugin.BasicGrimPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.internal.event.OptimizedEventBus;
import ac.grim.grimac.internal.plugin.resolver.GrimExtensionManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompanionGrimApiTest {
    private static final String SUFFIX = " is not available through the CultAC proxy companion; use a proxy plugin";

    private final GrimPlugin plugin =
            new BasicGrimPlugin(Logger.getLogger("test"), new File("."), "1.0", "test", List.of());
    private final FakeBridge bridge = new FakeBridge();
    private final FakeSession session = new FakeSession(UUID.randomUUID());
    private final FakeMainThread mainThread = new FakeMainThread();
    private final List<String> dispatched = new ArrayList<>();
    private CompanionGrimAPI api;
    private GrimApiEvents events;

    @BeforeEach
    void setUp() {
        GrimExtensionManager extensions = new GrimExtensionManager();
        api = new CompanionGrimAPI(
                new OptimizedEventBus(extensions), extensions, "1.0", Logger.getLogger("test"), Runnable::run, () -> 7);
        events = new GrimApiEvents(
                api, plugin, bridge, new LegacyEvents(event -> {}), () -> 1234L, mainThread, command -> {
                    dispatched.add(command);
                    return CompletableFuture.completedFuture(true);
                });
        bridge.addHandler(events);
    }

    private CompanionUser readyUser() {
        events.onReady(session);
        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(
                                new CheckInfo(0, "Reach", "Reach", "Reach", "", "reach", 5, 0.05, false, true),
                                new CheckInfo(1, "Timer", "Timer", "Timer", "", "timer", 10, 0.02, false, true)),
                        true));
        return (CompanionUser) api.getGrimUser(session.playerId());
    }

    @Test
    void unsupportedMethodsThrowTheDocumentedMessage() {
        CompanionUser user = readyUser();
        assertEquals(
                "getPacketWorld" + SUFFIX,
                assertThrows(UnsupportedOperationException.class, user::getPacketWorld)
                        .getMessage());
        assertEquals(
                "addRealTimeTask" + SUFFIX,
                assertThrows(UnsupportedOperationException.class, () -> user.addRealTimeTask(1, () -> {}))
                        .getMessage());
        assertEquals(
                "addRealTimeTaskNow" + SUFFIX,
                assertThrows(UnsupportedOperationException.class, () -> user.addRealTimeTaskNow(() -> {}))
                        .getMessage());
        assertEquals(
                "addRealTimeTaskNext" + SUFFIX,
                assertThrows(UnsupportedOperationException.class, () -> user.addRealTimeTaskNext(() -> {}))
                        .getMessage());
    }

    @Test
    void userExistsFromJoinWithDefaultsUntilQuit() {
        assertNull(api.getGrimUser(session.playerId()));
        CompanionUser joined = api.join(session.player());
        assertSame(joined, api.getGrimUser(session.player()));
        assertEquals(session.playerId(), joined.getUniqueId());
        assertEquals("Unknown", joined.getBrand());
        assertTrue(joined.getChecks().isEmpty());
        assertEquals(0, joined.getTransactionPing());
        api.getAlertManager().setAlertsEnabled(joined, true, false);
        assertFalse(api.getAlertManager().hasAlertsEnabled(joined));
        assertTrue(session.sent.isEmpty());

        CompanionUser user = readyUser();
        assertSame(joined, user);
        assertEquals(2, user.getChecks().size());
        assertEquals(7, api.getCurrentTick());
        assertEquals("1.0", api.getGrimVersion());

        events.onClosed(session);
        assertSame(joined, api.getGrimUser(session.playerId()));
        assertNull(api.userOf(session));
        api.quit(session.player());
        assertNull(api.getGrimUser(session.playerId()));
    }

    @Test
    void readyBeforeJoinKeepsTheSessionUser() {
        CompanionUser user = readyUser();
        assertSame(user, api.join(session.player()));
        assertSame(user, api.userOf(session));
    }

    @Test
    void quitBeforeSessionCloseStillFiresGrimQuit() {
        List<GrimUser> quit = new ArrayList<>();
        api.getEventBus().get(GrimQuitEvent.class).onQuit(plugin, quit::add);
        CompanionUser user = readyUser();
        events.onMessage(session, new UserInfo("vanilla", "1.21.11", true));

        events.playerQuit(session.player());
        events.onClosed(session);
        assertEquals(List.of(user), quit);
        assertNull(api.getGrimUser(session.playerId()));
    }

    @Test
    void sessionReopenedWhileOnlineReattachesWithoutJoinOrQuit() {
        List<GrimUser> joined = new ArrayList<>();
        List<GrimUser> quit = new ArrayList<>();
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, joined::add);
        api.getEventBus().get(GrimQuitEvent.class).onQuit(plugin, quit::add);
        CompanionUser user = api.join(session.player());
        events.onReady(session);
        mainThread.delayed.getLast().run();
        assertEquals(List.of(user), joined);

        events.onClosed(session);
        assertTrue(quit.isEmpty());
        FakeSession reopened = new FakeSession(session.playerId(), session.player());
        events.onReady(reopened);
        mainThread.delayed.getLast().run();
        events.onMessage(reopened, new UserInfo("vanilla", "1.21.11", true));
        events.onMessage(reopened, new CheckCatalog(List.of(), true));
        assertEquals(1, joined.size());
        assertSame(user, api.userOf(reopened));
        assertEquals("vanilla", user.getBrand());

        events.onClosed(reopened);
        assertTrue(quit.isEmpty());
        events.playerQuit(session.player());
        assertEquals(List.of(user), quit);
    }

    @Test
    void quitBeforeTheJoinFiredSuppressesBoth() {
        List<GrimUser> joined = new ArrayList<>();
        List<GrimUser> quit = new ArrayList<>();
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, joined::add);
        api.getEventBus().get(GrimQuitEvent.class).onQuit(plugin, quit::add);
        events.onReady(session);

        events.playerQuit(session.player());
        mainThread.delayed.getLast().run();
        events.onClosed(session);

        assertTrue(joined.isEmpty());
        assertTrue(quit.isEmpty());
    }

    @Test
    void flagUpdatesViolationsAndFiresFlagEvent() {
        CompanionUser user = readyUser();
        AtomicReference<String> seen = new AtomicReference<>();
        List<Double> violationsSeen = new ArrayList<>();
        api.getEventBus().get(FlagEvent.class).onFlagSupplier(plugin, (u, check, verbose, cancelled) -> {
            assertSame(user, u);
            seen.set(check.getCheckName() + ":" + verbose.get());
            violationsSeen.add(check.getViolations());
            return true;
        });

        events.onMessage(session, new Flag(1, 1, 5.0, "speed=1.3", false));

        assertEquals("Timer:speed=1.3", seen.get());
        assertEquals(List.of(4.0), violationsSeen);
        AbstractCheck timer = user.getChecks().stream()
                .filter(check -> check.getCheckName().equals("Timer"))
                .findFirst()
                .orElseThrow();
        assertEquals(5.0, timer.getViolations());
        assertEquals(1234L, timer.getLastViolationTime());
    }

    @Test
    void catalogChunksAccumulateAndARestartKeepsViolations() {
        CompanionUser user = readyUser();
        events.onMessage(session, new Flag(1, 1, 5.0, "", false));
        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(new CheckInfo(2, "Fly", "Fly", "Fly", "", "fly", 5, 0.05, false, true)), true));
        assertEquals(List.of("Reach", "Timer", "Fly"), names(user));

        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(new CheckInfo(0, "Timer", "Timer", "Timer", "", "timer", 10, 0.02, false, true)),
                        false));
        assertEquals(List.of("Timer"), names(user));
        assertEquals(5.0, user.getChecks().iterator().next().getViolations());

        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(new CheckInfo(1, "Reach", "Reach", "Reach", "", "reach", 5, 0.05, false, true)), true));
        assertEquals(List.of("Timer", "Reach"), names(user));
    }

    private static List<String> names(CompanionUser user) {
        return user.getChecks().stream().map(CompanionCheck::getCheckName).toList();
    }

    @Test
    void flagForUnknownCheckIsIgnored() {
        readyUser();
        AtomicReference<GrimUser> seen = new AtomicReference<>();
        api.getEventBus().get(FlagEvent.class).onFlagSupplier(plugin, (u, check, verbose, cancelled) -> {
            seen.set(u);
            return cancelled;
        });
        events.onMessage(session, new Flag(1, 9, 1.0, "", false));
        assertNull(seen.get());
    }

    @Test
    void listenerBitsFollowSubscriptions() {
        assertEquals(0, events.listenerBits());
        events.refreshListeners();
        assertEquals(List.of(0), bridge.listenerBits);

        events.refreshListeners();
        assertEquals(List.of(0), bridge.listenerBits);

        api.getEventBus().get(FlagEvent.class).onFlagSupplier(plugin, (u, check, verbose, cancelled) -> cancelled);
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, user -> {});
        int expected = ListenerKind.FLAG.flag();
        assertEquals(expected, events.listenerBits());
        events.refreshListeners();
        assertEquals(List.of(0, expected), bridge.listenerBits);
    }

    @Test
    void alertSettersSendToggleAndUpdateLocally() {
        CompanionUser user = readyUser();
        events.onMessage(session, new AlertState(AlertState.VERBOSE));
        assertTrue(api.getAlertManager().hasVerboseEnabled(user));
        assertFalse(api.getAlertManager().hasAlertsEnabled(user));

        api.getAlertManager().setAlertsEnabled(user, true, true);
        assertTrue(api.getAlertManager().hasAlertsEnabled(user));
        assertEquals(new AlertToggle(0, true, true), session.sent.getLast());
    }

    @Test
    void joinWaitsForUserInfoAndCatalog() {
        List<GrimUser> joined = new ArrayList<>();
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, joined::add);
        CompanionUser user = readyUser();
        assertTrue(joined.isEmpty());

        events.onMessage(session, new UserInfo("vanilla", "1.21.11", true));
        assertEquals(List.of(user), joined);
        assertTrue(mainThread.cancelled);

        events.onMessage(session, new UserInfo("fabric", "1.21.11", true));
        mainThread.delayed.forEach(Runnable::run);
        assertEquals(1, joined.size());
    }

    @Test
    void joinWaitsForTheLastCatalogChunk() {
        List<Integer> checksAtJoin = new ArrayList<>();
        api.getEventBus()
                .get(GrimJoinEvent.class)
                .onJoin(plugin, u -> checksAtJoin.add(u.getChecks().size()));
        events.onReady(session);
        events.onMessage(session, new UserInfo("vanilla", "1.21.11", true));
        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(new CheckInfo(0, "Reach", "Reach", "Reach", "", "reach", 5, 0.05, false, true)),
                        false));
        assertTrue(checksAtJoin.isEmpty());

        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(new CheckInfo(1, "Timer", "Timer", "Timer", "", "timer", 10, 0.02, false, true)),
                        true));
        assertEquals(List.of(2), checksAtJoin);
    }

    @Test
    @SuppressWarnings("removal")
    void legacyJoinPrecedesTheFirstLegacyFlag() {
        List<String> called = new ArrayList<>();
        events = new GrimApiEvents(
                api,
                plugin,
                bridge,
                new LegacyEvents(event -> called.add(event.getEventName())),
                () -> 1234L,
                mainThread,
                command -> CompletableFuture.completedFuture(true));
        bridge.addHandler(events);
        Listener listener = new Listener() {};
        ac.grim.grimac.api.events.GrimJoinEvent.getHandlerList()
                .register(new RegisteredListener(listener, (l, e) -> {}, EventPriority.NORMAL, null, false));
        ac.grim.grimac.api.events.GrimQuitEvent.getHandlerList()
                .register(new RegisteredListener(listener, (l, e) -> {}, EventPriority.NORMAL, null, false));
        ac.grim.grimac.api.events.FlagEvent.getHandlerList()
                .register(new RegisteredListener(listener, (l, e) -> {}, EventPriority.NORMAL, null, false));
        try {
            events.refreshListeners();
            readyUser();
            events.onMessage(session, new UserInfo("vanilla", "1.21.11", true));
            events.onMessage(session, new Flag(1, 1, 3.0, "x", false));
            events.playerQuit(session.player());
            assertEquals(List.of("GrimJoinEvent", "FlagEvent", "GrimQuitEvent"), called);
        } finally {
            HandlerList.unregisterAll(listener);
        }
    }

    @Test
    void quitDuringTheJoinHandlerFiresAfterItReturns() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, user -> {
            order.add("join-start");
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add("join-end");
        });
        api.getEventBus().get(GrimQuitEvent.class).onQuit(plugin, user -> order.add("quit"));
        readyUser();

        var netty = new Thread(() -> events.onMessage(session, new UserInfo("vanilla", "1.21.11", true)));
        netty.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        events.playerQuit(session.player());
        assertEquals(List.of("join-start"), order);

        release.countDown();
        netty.join(5000);
        assertFalse(netty.isAlive());
        assertEquals(List.of("join-start", "join-end", "quit"), order);
    }

    @Test
    void quitAfterTheJoinFiresOnce() {
        List<String> order = new ArrayList<>();
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, user -> order.add("join"));
        api.getEventBus().get(GrimQuitEvent.class).onQuit(plugin, user -> order.add("quit"));
        readyUser();
        events.onMessage(session, new UserInfo("vanilla", "1.21.11", true));
        events.playerQuit(session.player());
        events.playerQuit(session.player());
        assertEquals(List.of("join", "quit"), order);
    }

    @Test
    void joinFallsBackOneSecondAfterReady() {
        List<GrimUser> joined = new ArrayList<>();
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, joined::add);
        events.onReady(session);
        assertEquals(List.of(GrimApiEvents.JOIN_FALLBACK_TICKS), mainThread.ticks);

        mainThread.delayed.getFirst().run();
        assertEquals(1, joined.size());
        assertEquals(1, session.executed.size());
        events.onMessage(session, new UserInfo("vanilla", "1.21.11", true));
        events.onMessage(session, new CheckCatalog(List.of(), true));
        assertEquals(1, joined.size());
    }

    @Test
    void quitFollowsOnlyAJoin() {
        List<GrimUser> quit = new ArrayList<>();
        api.getEventBus().get(GrimQuitEvent.class).onQuit(plugin, quit::add);
        events.onReady(session);
        events.onClosed(session);
        assertTrue(mainThread.cancelled);
        mainThread.delayed.getFirst().run();
        events.playerQuit(session.player());
        assertTrue(quit.isEmpty());

        FakeSession next = new FakeSession(session.playerId());
        events.onReady(next);
        mainThread.delayed.getLast().run();
        events.onClosed(next);
        assertTrue(quit.isEmpty());
        events.playerQuit(next.player());
        assertEquals(1, quit.size());
    }

    @Test
    void flagAwaitingVerdictRepliesWithTheListenersCancel() {
        CompanionUser user = readyUser();
        List<Double> violationsSeen = new ArrayList<>();
        api.getEventBus().get(FlagEvent.class).onFlagSupplier(plugin, (u, check, verbose, cancelled) -> {
            violationsSeen.add(check.getViolations());
            return verbose.get().equals("exempt");
        });
        CompanionCheck timer = user.check(1);

        events.onMessage(session, new Flag(7, 1, 3.0, "exempt", true));

        assertEquals(List.of(new Verdict(7, true)), session.sent);
        assertEquals(List.of(2.0), violationsSeen);
        assertEquals(2.0, timer.getViolations());
        assertEquals(0L, timer.getLastViolationTime());

        events.onMessage(session, new Flag(8, 1, 3.0, "cheating", true));

        assertEquals(new Verdict(8, false), session.sent.get(1));
        assertEquals(3.0, timer.getViolations());
        assertEquals(1234L, timer.getLastViolationTime());
    }

    @Test
    @SuppressWarnings("removal")
    void legacyFlagFiresAtPriorityZeroAndNotAfterACancel() {
        List<String> order = new ArrayList<>();
        useCancellingLegacyEvents(order, command -> CompletableFuture.completedFuture(true));
        HandlerList legacyHandlers = ac.grim.grimac.api.events.FlagEvent.getHandlerList();
        Listener listener = new Listener() {};
        try {
            assertListenerBitFollows(legacyHandlers, listener, ListenerKind.FLAG);

            FlagEvent.Channel channel = api.getEventBus().get(FlagEvent.class);
            channel.onFlagSupplier(
                    plugin,
                    (u, check, verbose, cancelled) -> {
                        order.add("early");
                        return verbose.get().equals("early-cancel");
                    },
                    -1,
                    false);
            channel.onFlagSupplier(
                    plugin,
                    (u, check, verbose, cancelled) -> {
                        order.add("late");
                        return cancelled;
                    },
                    1,
                    false);
            register(legacyHandlers, listener);
            readyUser();

            events.onMessage(session, new Flag(1, 1, 3.0, "x", true));
            assertEquals(List.of("early", "legacy"), order);
            assertEquals(List.of(new Verdict(1, true)), session.sent);

            order.clear();
            events.onMessage(session, new Flag(2, 1, 3.0, "early-cancel", true));
            assertEquals(List.of("early"), order);
            assertEquals(new Verdict(2, true), session.sent.get(1));
        } finally {
            HandlerList.unregisterAll(listener);
        }
    }

    @Test
    @SuppressWarnings("removal")
    void legacyCommandExecuteFiresAtPriorityZeroAndNotAfterACancel() {
        List<String> order = new ArrayList<>();
        useCancellingLegacyEvents(order, command -> {
            dispatched.add(command);
            return CompletableFuture.completedFuture(true);
        });
        HandlerList legacyHandlers = ac.grim.grimac.api.events.CommandExecuteEvent.getHandlerList();
        Listener listener = new Listener() {};
        try {
            assertListenerBitFollows(legacyHandlers, listener, ListenerKind.COMMAND_EXECUTE);

            CommandExecuteEvent.Channel channel = api.getEventBus().get(CommandExecuteEvent.class);
            channel.onCommandExecuteSupplier(
                    plugin,
                    (u, check, verbose, command, cancelled) -> {
                        order.add("early");
                        return command.equals("early-cancel");
                    },
                    -1,
                    false);
            channel.onCommandExecuteSupplier(
                    plugin,
                    (u, check, verbose, command, cancelled) -> {
                        order.add("late");
                        return cancelled;
                    },
                    1,
                    false);
            register(legacyHandlers, listener);
            readyUser();

            events.onMessage(session, new CommandExecute(1, 0, 6.0, "reach=3.4", "kick Steve"));
            assertEquals(List.of("early", "legacy"), order);
            assertEquals(List.of(new Result(1, false)), session.sent);

            order.clear();
            events.onMessage(session, new CommandExecute(2, 0, 6.0, "reach=3.4", "early-cancel"));
            assertEquals(List.of("early"), order);
            assertEquals(new Result(2, false), session.sent.get(1));
            assertEquals(List.of(), dispatched);
        } finally {
            HandlerList.unregisterAll(listener);
        }
    }

    /** Events whose legacy Bukkit events record "legacy" in {@code order} and are cancelled by their listener. */
    private void useCancellingLegacyEvents(List<String> order, GrimApiEvents.ConsoleCommands console) {
        events = new GrimApiEvents(
                api,
                plugin,
                bridge,
                new LegacyEvents(event -> {
                    order.add("legacy");
                    ((Cancellable) event).setCancelled(true);
                }),
                () -> 1234L,
                mainThread,
                console);
        bridge.addHandler(events);
    }

    private void assertListenerBitFollows(HandlerList handlers, Listener listener, ListenerKind kind) {
        register(handlers, listener);
        assertTrue(ListenerKind.has(events.listenerBits(), kind));
        HandlerList.unregisterAll(listener);
        events.refreshListeners();
        assertFalse(ListenerKind.has(events.listenerBits(), kind));
    }

    private void register(HandlerList handlers, Listener listener) {
        handlers.register(new RegisteredListener(listener, (l, e) -> {}, EventPriority.NORMAL, null, false));
        events.refreshListeners();
    }

    @Test
    void flagAwaitingVerdictForUnknownCheckIsNotCancelled() {
        readyUser();
        events.onMessage(session, new Flag(3, 9, 1.0, "", true));
        assertEquals(List.of(new Verdict(3, false)), session.sent);
    }

    @Test
    void cancelledCommandExecuteDoesNotDispatch() {
        CompanionUser user = readyUser();
        List<String> commands = new ArrayList<>();
        api.getEventBus()
                .get(CommandExecuteEvent.class)
                .onCommandExecute(plugin, (u, check, verbose, command, cancelled) -> {
                    assertSame(user, u);
                    commands.add(check.getCheckName() + ":" + verbose + ":" + command);
                    return command.startsWith("kick");
                });

        events.onMessage(session, new CommandExecute(4, 0, 6.0, "reach=3.4", "kick Steve"));

        assertEquals(List.of("Reach:reach=3.4:kick Steve"), commands);
        assertEquals(List.of(), dispatched);
        assertEquals(List.of(new Result(4, false)), session.sent);
        assertEquals(7.0, user.check(0).getViolations());

        events.onMessage(session, new CommandExecute(5, 0, 6.0, "reach=3.4", "say hi"));

        assertEquals(List.of("say hi"), dispatched);
        assertEquals(new Result(5, true), session.sent.get(1));
    }

    @Test
    void commandExecuteLeavesTheCheckAtTheCommittedViolations() {
        CompanionUser user = readyUser();
        List<Double> seen = new ArrayList<>();
        api.getEventBus()
                .get(CommandExecuteEvent.class)
                .onCommandExecute(plugin, (u, check, verbose, command, cancelled) -> {
                    seen.add(check.getViolations());
                    return cancelled;
                });
        events.onMessage(session, new Flag(1, 0, 6.0, "", false));

        events.onMessage(session, new CommandExecute(4, 0, 5.0, "", "say hi"));

        assertEquals(List.of(5.0), seen);
        assertEquals(6.0, user.check(0).getViolations());
    }

    @Test
    void joinFiresOnTheBridgeThreadBeforeTheFirstFlag() {
        List<String> order = new ArrayList<>();
        api.getEventBus().get(GrimJoinEvent.class).onJoin(plugin, u -> order.add("join"));
        api.getEventBus().get(FlagEvent.class).onFlagSupplier(plugin, (u, check, verbose, cancelled) -> {
            order.add("flag");
            return cancelled;
        });

        readyUser();
        events.onMessage(session, new UserInfo("vanilla", "1.21.11", true));
        events.onMessage(session, new Flag(1, 0, 1.0, "", false));

        assertEquals(List.of("join", "flag"), order);
        assertTrue(mainThread.cancelled);
    }

    @Test
    void catalogReplacementDropsTheCarryOver() {
        CompanionUser user = readyUser();
        events.onMessage(session, new Flag(1, 1, 5.0, "", false));
        events.onMessage(session, new Flag(2, 0, 3.0, "", false));
        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(new CheckInfo(0, "Timer", "Timer", "Timer", "", "timer", 10, 0.02, false, true)),
                        true));
        assertEquals(5.0, user.check(0).getViolations());

        events.onMessage(
                session,
                new CheckCatalog(
                        List.of(new CheckInfo(1, "Reach", "Reach", "Reach", "", "reach", 5, 0.05, false, true)), true));

        assertEquals(List.of("Timer", "Reach"), names(user));
        assertEquals(0.0, user.check(1).getViolations());
    }

    @Test
    void nullFunctionRemovesTheKey() {
        Function<Object, Object> function = value -> value;
        api.registerFunction("key", function);
        assertSame(function, api.getFunction("key"));

        api.registerFunction("key", null);

        assertNull(api.getFunction("key"));
    }

    @Test
    void resyncHandlerDefaultsToANoOp() {
        CompanionUser user = readyUser();
        assertNotNull(user.getResyncHandler());
        user.getResyncHandler().resync(0, 0, 0, 1, 1, 1);
    }

    @Test
    void reloadWithoutRelayCompletesFalse() {
        assertFalse(api.reloadAsync().join());
    }

    @Test
    void reloadRunsThroughTheRelay() {
        var forwarded = new CompletableFuture<Boolean>();
        api.reloadVia(() -> forwarded);
        assertSame(forwarded, api.reloadAsync());
        assertSame(forwarded, api.reloadAsync(api.getConfigManager()));
    }

    private static final class FakeMainThread implements GrimApiEvents.Later {
        final List<Runnable> delayed = new ArrayList<>();
        final List<Long> ticks = new ArrayList<>();
        boolean cancelled;

        @Override
        public Runnable later(Runnable task, long ticks) {
            delayed.add(task);
            this.ticks.add(ticks);
            return () -> cancelled = true;
        }
    }
}
