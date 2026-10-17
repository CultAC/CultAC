package ac.cult.cultac.platform.velocity.bridge.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Verdict;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.verdict.FlagArbiter;
import ac.cult.cultac.checks.verdict.FlagOutcome;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import ac.cult.cultac.platform.velocity.bridge.FakeBridgeSession;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerdictServiceTest {
    private final Check check = mock(Check.class);
    private final Map<UUID, FakePlayer> players = new HashMap<>();
    private VerdictService service;

    @BeforeEach
    void setUp() {
        service = new VerdictService(players::get, (session, c) -> c == check ? 3 : -1, Logger.getLogger("test"));
    }

    @Test
    void installsOnlyForVerdictCapableBackendsWithFlagListeners() {
        FakeBridgeSession withoutCapability = session("lobby", false, ListenerKind.FLAG.flag());
        service.onReady(withoutCapability);
        assertNull(player(withoutCapability).installed);

        FakeBridgeSession withoutListener = session("lobby", true, 0);
        service.onReady(withoutListener);
        assertNull(player(withoutListener).installed);

        withoutListener.listeners = ListenerKind.FLAG.flag();
        service.onMessage(withoutListener, new Listeners(withoutListener.listeners));
        assertTrue(player(withoutListener).installed instanceof SessionArbiter);

        withoutListener.listeners = 0;
        service.onMessage(withoutListener, new Listeners(0));
        assertNull(player(withoutListener).installed);
    }

    @Test
    void deferSendsFlagAwaitingVerdict() {
        FakeBridgeSession session = readySession("lobby");
        FlagArbiter arbiter = player(session).installed;

        assertTrue(arbiter.defer(check, () -> "x".repeat(10000), 4.0, new FakeOutcome()));

        Flag flag = (Flag) session.sent.getFirst();
        assertEquals(1, flag.flagId());
        assertEquals(3, flag.checkIdx());
        assertEquals(4.0, flag.violations());
        assertEquals(BridgeFrames.MAX_VERBOSE_CHARS, flag.verbose().length());
        assertTrue(flag.awaitVerdict());
        assertEquals(1, player(session).scheduled.size());
    }

    @Test
    void checkMissingFromTheCatalogIsNotDeferred() {
        FakeBridgeSession session = readySession("lobby");

        assertFalse(player(session).installed.defer(mock(Check.class), () -> "", 1.0, new FakeOutcome()));
        assertTrue(session.sent.isEmpty());
    }

    @Test
    void timeoutCommits() {
        FakeBridgeSession session = readySession("lobby");
        FakeOutcome outcome = new FakeOutcome();
        player(session).installed.defer(check, () -> "", 1.0, outcome);

        player(session).runScheduled();
        assertEquals(0, outcome.commits);
        player(session).runResolved();

        assertEquals(1, outcome.commits);
        assertEquals(0, outcome.discards);
    }

    @Test
    void verdictResolvesOnceAndUnknownIdsAreIgnored() {
        FakeBridgeSession session = readySession("lobby");
        FakeOutcome cancelled = new FakeOutcome();
        FakeOutcome allowed = new FakeOutcome();
        player(session).installed.defer(check, () -> "", 1.0, cancelled);
        player(session).installed.defer(check, () -> "", 2.0, allowed);

        service.onMessage(session, new Verdict(1, true));
        service.onMessage(session, new Verdict(2, false));
        service.onMessage(session, new Verdict(99, true));
        player(session).runScheduled();
        player(session).runResolved();

        assertEquals(1, cancelled.discards);
        assertEquals(0, cancelled.commits);
        assertEquals(1, allowed.commits);
        assertEquals(0, allowed.discards);
    }

    @Test
    void verdictFromAnotherSessionIsIgnored() {
        FakeBridgeSession session = readySession("lobby");
        FakeOutcome outcome = new FakeOutcome();
        player(session).installed.defer(check, () -> "", 1.0, outcome);

        service.onMessage(fake(session.playerId, "lobby", true, ListenerKind.FLAG.flag()), new Verdict(1, true));
        player(session).runResolved();

        assertEquals(0, outcome.discards);
        assertEquals(0, outcome.commits);
    }

    @Test
    void breakerTripsAfterConsecutiveTimeouts() {
        FakeBridgeSession first = readySession("survival");
        FakeBridgeSession second = readySession("survival");
        FlagArbiter arbiter = player(first).installed;

        for (int i = 0; i < VerdictService.BREAKER_TIMEOUTS - 1; i++) {
            arbiter.defer(check, () -> "", 1.0, new FakeOutcome());
            player(first).runScheduled();
        }
        assertSame(arbiter, player(first).installed);

        arbiter.defer(check, () -> "", 1.0, new FakeOutcome());
        player(first).runScheduled();

        assertNull(player(first).installed);
        assertTrue(player(second).installed instanceof SessionArbiter);
        Listeners unchanged = new Listeners(ListenerKind.FLAG.flag());
        service.onMessage(first, unchanged);
        assertNull(player(first).installed);

        FakeBridgeSession next = fake(first.playerId, "survival", true, ListenerKind.FLAG.flag());
        service.onReady(next);

        assertTrue(player(first).installed instanceof SessionArbiter);
        assertNotSame(arbiter, player(first).installed);
    }

    @Test
    void timeoutsOfOtherPlayersDoNotAddUp() {
        FakeBridgeSession first = readySession("survival");
        FakeBridgeSession second = readySession("survival");
        for (int i = 0; i < VerdictService.BREAKER_TIMEOUTS - 1; i++) {
            player(first).installed.defer(check, () -> "", 1.0, new FakeOutcome());
            player(first).runScheduled();
        }

        player(second).installed.defer(check, () -> "", 1.0, new FakeOutcome());
        player(second).runScheduled();

        assertTrue(player(first).installed instanceof SessionArbiter);
        assertTrue(player(second).installed instanceof SessionArbiter);
    }

    @Test
    void answeredVerdictResetsTheTimeoutCount() {
        FakeBridgeSession session = readySession("survival");
        FlagArbiter arbiter = player(session).installed;
        int flagId = 0;
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < VerdictService.BREAKER_TIMEOUTS - 1; i++) {
                arbiter.defer(check, () -> "", 1.0, new FakeOutcome());
                flagId++;
                player(session).runScheduled();
            }
            arbiter.defer(check, () -> "", 1.0, new FakeOutcome());
            service.onMessage(session, new Verdict(++flagId, false));
        }
        assertSame(arbiter, player(session).installed);
    }

    @Test
    void sessionCloseCommitsPendingFlags() {
        FakeBridgeSession session = readySession("lobby");
        FakeOutcome outcome = new FakeOutcome();
        FlagArbiter arbiter = player(session).installed;
        arbiter.defer(check, () -> "", 1.0, outcome);

        service.onClosed(session);

        assertNull(player(session).installed);
        player(session).runResolved();
        assertEquals(1, outcome.commits);
        assertFalse(arbiter.defer(check, () -> "", 1.0, new FakeOutcome()));

        service.onMessage(session, new Verdict(1, true));
        player(session).runScheduled();
        player(session).runResolved();
        assertEquals(1, outcome.commits);
        assertEquals(0, outcome.discards);
    }

    @Test
    void serviceCloseCommitsPendingFlags() {
        FakeBridgeSession session = readySession("lobby");
        FakeOutcome outcome = new FakeOutcome();
        player(session).installed.defer(check, () -> "", 1.0, outcome);

        service.close();
        player(session).runResolved();

        assertNull(player(session).installed);
        assertEquals(1, outcome.commits);
    }

    @Test
    void declinedFlagReachesTheCurrentBackend() {
        FakeBridgeSession old = readySession("lobby");
        FlagArbiter arbiter = player(old).installed;
        service.onClosed(old);
        FakeBridgeSession current = fake(old.playerId, "survival", false, ListenerKind.FLAG.flag());
        service.onReady(current);

        assertFalse(arbiter.defer(check, () -> "verbose", 2.0, new FakeOutcome()));

        assertTrue(old.sent.isEmpty());
        Flag flag = (Flag) current.sent.getFirst();
        assertEquals(BridgeFrames.NOTIFY_FLAG_ID, flag.flagId());
        assertEquals(3, flag.checkIdx());
        assertEquals(2.0, flag.violations());
        assertEquals("verbose", flag.verbose());
        assertFalse(flag.awaitVerdict());
    }

    @Test
    void declinedFlagSendsNoNotifyWithoutAFlagListenerOrSession() {
        FakeBridgeSession old = readySession("lobby");
        FlagArbiter arbiter = player(old).installed;
        service.onClosed(old);

        assertFalse(arbiter.defer(check, () -> "", 1.0, new FakeOutcome()));

        FakeBridgeSession current = fake(old.playerId, "survival", false, 0);
        service.onReady(current);
        assertFalse(arbiter.defer(check, () -> "", 1.0, new FakeOutcome()));

        assertTrue(old.sent.isEmpty());
        assertTrue(current.sent.isEmpty());
    }

    private FakeBridgeSession readySession(String server) {
        FakeBridgeSession session = session(server, true, ListenerKind.FLAG.flag());
        service.onReady(session);
        return session;
    }

    private FakeBridgeSession session(String server, boolean verdicts, int listeners) {
        FakeBridgeSession session = fake(UUID.randomUUID(), server, verdicts, listeners);
        players.put(session.playerId, new FakePlayer());
        return session;
    }

    private FakePlayer player(FakeBridgeSession session) {
        return players.get(session.playerId);
    }

    private static final class FakeOutcome implements FlagOutcome {
        int commits;
        int discards;

        @Override
        public void commit() {
            commits++;
        }

        @Override
        public void discard() {
            discards++;
        }
    }

    private static final class FakePlayer implements VerdictPlayer {
        final List<Runnable> scheduled = new ArrayList<>();
        final List<Runnable> resolved = new ArrayList<>();
        FlagArbiter installed;

        void runScheduled() {
            List<Runnable> tasks = new ArrayList<>(scheduled);
            scheduled.clear();
            tasks.forEach(Runnable::run);
        }

        void runResolved() {
            List<Runnable> tasks = new ArrayList<>(resolved);
            resolved.clear();
            tasks.forEach(Runnable::run);
        }

        @Override
        public void onLoop(Runnable task) {
            task.run();
        }

        @Override
        public void schedule(Runnable task, long delayMillis) {
            scheduled.add(task);
        }

        @Override
        public void install(FlagArbiter arbiter) {
            installed = arbiter;
        }

        @Override
        public void uninstall(FlagArbiter arbiter) {
            if (installed == arbiter) installed = null;
        }

        @Override
        public void resolveLater(Runnable task) {
            resolved.add(task);
        }
    }

    private static FakeBridgeSession fake(UUID playerId, String serverName, boolean verdicts, int listeners) {
        Set<ServerCapability> capabilities = verdicts
                ? EnumSet.of(ServerCapability.GRIM_API, ServerCapability.FLAG_VERDICT)
                : EnumSet.of(ServerCapability.GRIM_API);
        return new FakeBridgeSession(playerId, serverName, capabilities, listeners);
    }
}
