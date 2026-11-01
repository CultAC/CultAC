package ac.cult.cultac.cloud.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FindingsLogTest {
    private static final UUID ALEX = new UUID(1, 1);
    private static final UUID STEVE = new UUID(2, 2);

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Set<UUID> online = new HashSet<>();
    private final FindingsLog log = new FindingsLog(now::get, online::contains);

    private final Set<UUID> present = ConcurrentHashMap.newKeySet();
    private final AtomicReference<UUID> pauseAt = new AtomicReference<>();
    private final CountDownLatch paused = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);
    /** Its online lookup of {@link #pauseAt}, once set, waits for {@link #resume}. */
    private final FindingsLog pausing = new FindingsLog(now::get, player -> {
        boolean found = present.contains(player);
        if (pauseAt.compareAndSet(player, null)) {
            paused.countDown();
            try {
                resume.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return found;
    });

    @Test
    void keepsTheLastTwoHundredNewestFirst() {
        online.add(ALEX);
        for (int i = 0; i < FindingsLog.CAPACITY + 50; i++) log.add(ALEX, "Alex", finding("Reach", i));

        List<Finding> recent = log.recent(ALEX, 1000);
        assertEquals(FindingsLog.CAPACITY, recent.size());
        assertEquals(FindingsLog.CAPACITY + 49, recent.get(0).confidence());
        assertEquals(50, recent.get(recent.size() - 1).confidence());
        assertEquals(
                List.of(249.0, 248.0, 247.0),
                log.recent(ALEX, 3).stream().map(Finding::confidence).toList());
    }

    @Test
    void keepsAPlayerThirtyMinutesAfterTheyLeave() {
        online.add(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 1));
        leave(ALEX);

        now.addAndGet(FindingsLog.RETENTION_MILLIS);
        assertEquals(1, log.recent(ALEX, 15).size());

        now.incrementAndGet();
        assertTrue(log.recent(ALEX, 15).isEmpty());
    }

    @Test
    void aPlayerWhoLeftExpiresWithoutARead() {
        online.add(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 1));
        leave(ALEX);
        now.addAndGet(FindingsLog.RETENTION_MILLIS + 1);

        online.add(STEVE);
        log.add(STEVE, "Steve", finding("Reach", 1));
        assertEquals(1, log.size());
    }

    @Test
    void aPlayerBackOnlineIsKept() {
        online.add(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 1));
        leave(ALEX);
        online.add(ALEX);
        now.addAndGet(FindingsLog.RETENTION_MILLIS * 2);

        assertEquals(1, log.recent(ALEX, 15).size());
    }

    @Test
    void aLateFindingDoesNotKeepAPlayerWhoLeft() {
        online.add(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 1));
        leave(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 2));
        assertEquals(2, log.recent(ALEX, 15).size());
        now.addAndGet(FindingsLog.RETENTION_MILLIS + 1);

        assertTrue(log.recent(ALEX, 15).isEmpty());
    }

    @Test
    void aPlayerFoundOfflineCountsAsGoneFromThen() {
        online.add(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 1));
        online.remove(ALEX);
        assertEquals(1, log.recent(ALEX, 15).size());

        now.addAndGet(FindingsLog.RETENTION_MILLIS + 1);
        assertTrue(log.recent(ALEX, 15).isEmpty());
    }

    @Test
    void topRanksByHighestConfidenceThenCount() {
        online.addAll(List.of(ALEX, STEVE, new UUID(3, 3)));
        for (int i = 0; i < 3; i++) log.add(ALEX, "Alex", finding("aim.snap", 0.6));
        log.add(ALEX, "Alex", finding("reach.far", 0.5));
        log.add(STEVE, "Steve", finding("aim.snap", 0.9));
        log.add(new UUID(3, 3), "Herobrine", finding("aim.snap", 0.6));
        log.add(STEVE, "Steve", new Finding(now.get() - 120_000, "aim.snap", "AIM", 0.99, "", 4));

        List<FindingsLog.Ranked> top = log.top(null, now.get() - 60_000, 10);
        assertEquals(
                List.of(STEVE, ALEX, new UUID(3, 3)),
                top.stream().map(FindingsLog.Ranked::player).toList());
        assertEquals(0.9, top.get(0).max());
        assertEquals(1, top.get(0).count());
        assertEquals(4, top.get(1).count());
        assertEquals(
                List.of(Map.entry("aim.snap", 3), Map.entry("reach.far", 1)),
                top.get(1).checks());

        top = log.top("REACH.FAR", 0, 10);
        assertEquals(List.of(ALEX), top.stream().map(FindingsLog.Ranked::player).toList());
        assertEquals(0.5, top.get(0).max());
    }

    @Test
    void topIsEmptyWhenNothingArrivedInTheWindow() {
        online.add(ALEX);
        log.add(ALEX, "Alex", new Finding(now.get() - 120_000, "aim.snap", "AIM", 0.9, "", 1));
        assertTrue(log.top(null, now.get() - 60_000, 10).isEmpty());
    }

    @Test
    void topIsCapped() {
        for (int i = 0; i < 15; i++) {
            online.add(new UUID(9, i));
            log.add(new UUID(9, i), "P" + i, finding("Reach", 1));
        }
        assertEquals(10, log.top(null, 0, 10).size());
    }

    @Test
    void aFindingForAPlayerNeitherOnlineNorKeptIsDropped() {
        log.add(ALEX, "Alex", finding("Reach", 1));
        assertEquals(0, log.size());
        assertTrue(log.recent(ALEX, 15).isEmpty());
    }

    @Test
    void aFindingAfterThePlayerLeftIsKept() {
        online.add(ALEX);
        leave(ALEX);
        log.add(ALEX, null, finding("Reach", 1));
        assertEquals(1, log.recent(ALEX, 15).size());
    }

    @Test
    void theFindingsOfEveryPlayerAreCappedTheLongestGoneFirst() {
        online.add(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 1));
        leave(ALEX);
        int players = FindingsLog.MAX_FINDINGS / FindingsLog.CAPACITY + 5;
        for (int i = 0; i < players; i++) {
            UUID player = new UUID(9, i);
            online.add(player);
            for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(player, "P" + i, finding("Reach", 1));
        }

        int kept = 0;
        for (int i = 0; i < players; i++)
            kept += log.recent(new UUID(9, i), FindingsLog.CAPACITY).size();
        assertEquals(FindingsLog.MAX_FINDINGS, kept);
        assertTrue(log.recent(ALEX, 15).isEmpty());
    }

    @Test
    void aSaturatedAddBetweenSweepsLooksUpNoPlayer() {
        AtomicInteger lookups = new AtomicInteger();
        FindingsLog counted = new FindingsLog(now::get, player -> {
            lookups.incrementAndGet();
            return online.contains(player);
        });
        int players = FindingsLog.MAX_FINDINGS / FindingsLog.CAPACITY + 20;
        for (int i = 0; i < players; i++) {
            online.add(new UUID(9, i));
            for (int j = 0; j < FindingsLog.CAPACITY; j++) counted.add(new UUID(9, i), "P" + i, finding("Reach", 1));
        }
        lookups.set(0);
        for (int i = 0; i < 10_000; i++) counted.add(new UUID(9, i % players), "P", finding("Reach", 2));

        assertEquals(0, lookups.get());
        int kept = 0;
        for (int i = 0; i < players; i++)
            kept += counted.recent(new UUID(9, i), FindingsLog.CAPACITY).size();
        assertEquals(FindingsLog.MAX_FINDINGS, kept);
    }

    @Test
    void reconnectsKeepOneDepartureALog() {
        for (int i = 0; i < 5_000; i++) {
            online.add(ALEX);
            if (i % 100 == 0) log.add(ALEX, "Alex", finding("Reach", 1));
            leave(ALEX);
            now.addAndGet(FindingsLog.SWEEP_MILLIS / 10);
        }
        assertEquals(1, log.size());
        assertTrue(log.departures() <= log.size(), log.departures() + " departures");
        assertEquals(50, log.recent(ALEX, FindingsLog.CAPACITY).size());
    }

    @Test
    void aRejoinedPlayerIsNotEvictedAsGone() {
        online.addAll(List.of(ALEX, STEVE));
        int others = (FindingsLog.MAX_FINDINGS - 2 * FindingsLog.CAPACITY) / FindingsLog.CAPACITY;
        for (int i = 0; i < others; i++) {
            UUID player = new UUID(9, i);
            online.add(player);
            for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(player, "P" + i, finding("Reach", 1));
        }
        for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(ALEX, "Alex", finding("Reach", 1));
        for (int j = 0; j < FindingsLog.CAPACITY - 1; j++) log.add(STEVE, "Steve", finding("Reach", 1));
        UUID last = new UUID(10, 0);
        online.add(last);
        log.add(last, "Last", finding("Reach", 1));

        leave(ALEX);
        online.add(ALEX);
        log.add(STEVE, "Steve", finding("Reach", 1));

        assertEquals(
                FindingsLog.CAPACITY, log.recent(ALEX, FindingsLog.CAPACITY).size());
        assertEquals(
                FindingsLog.CAPACITY, log.recent(STEVE, FindingsLog.CAPACITY).size());
    }

    @Test
    void aPlayerWhoLeftAgainIsEvictedAfterThoseGoneLonger() {
        online.addAll(List.of(ALEX, STEVE));
        int others = (FindingsLog.MAX_FINDINGS - 2 * FindingsLog.CAPACITY) / FindingsLog.CAPACITY;
        for (int i = 0; i < others; i++) {
            UUID player = new UUID(9, i);
            online.add(player);
            for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(player, "P" + i, finding("Reach", 1));
        }
        for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(ALEX, "Alex", finding("Reach", 1));
        for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(STEVE, "Steve", finding("Reach", 1));

        leave(ALEX);
        now.incrementAndGet();
        leave(STEVE);
        now.incrementAndGet();
        online.add(ALEX);
        now.incrementAndGet();
        leave(ALEX);
        UUID late = new UUID(10, 0);
        online.add(late);
        log.add(late, "Late", finding("Reach", 1));

        assertTrue(log.recent(STEVE, FindingsLog.CAPACITY).isEmpty());
        assertEquals(
                FindingsLog.CAPACITY, log.recent(ALEX, FindingsLog.CAPACITY).size());
    }

    @Test
    void aPlayerWhoLeftTwiceIsEvictedBeforeOneWhoLeftAfter() {
        online.addAll(List.of(ALEX, STEVE));
        int others = (FindingsLog.MAX_FINDINGS - 2 * FindingsLog.CAPACITY) / FindingsLog.CAPACITY;
        for (int i = 0; i < others; i++) {
            UUID player = new UUID(9, i);
            online.add(player);
            for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(player, "P" + i, finding("Reach", 1));
        }
        for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(ALEX, "Alex", finding("Reach", 1));
        for (int j = 0; j < FindingsLog.CAPACITY; j++) log.add(STEVE, "Steve", finding("Reach", 1));

        leave(ALEX);
        now.incrementAndGet();
        online.add(ALEX);
        now.incrementAndGet();
        leave(ALEX);
        now.incrementAndGet();
        leave(STEVE);
        UUID late = new UUID(10, 0);
        online.add(late);
        log.add(late, "Late", finding("Reach", 1));

        assertTrue(log.recent(ALEX, FindingsLog.CAPACITY).isEmpty());
        assertEquals(
                FindingsLog.CAPACITY, log.recent(STEVE, FindingsLog.CAPACITY).size());
    }

    @Test
    void anOverflowingAddLooksUpOnlyTheLogItEvictsAndThePlayersBack() {
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger backLookups = new AtomicInteger();
        Set<UUID> back = new HashSet<>();
        FindingsLog counted = new FindingsLog(now::get, player -> {
            lookups.incrementAndGet();
            if (back.contains(player)) backLookups.incrementAndGet();
            return online.contains(player);
        });
        int departed = 4_096;
        for (int i = 0; i < departed; i++) {
            UUID player = new UUID(8, i);
            online.add(player);
            counted.add(player, "D" + i, finding("Reach", 1));
            online.remove(player);
            counted.left(player);
        }
        int players = 160;
        for (int i = 0; i < players; i++) online.add(new UUID(9, i));
        for (int i = 0; i < FindingsLog.MAX_FINDINGS - departed; i++)
            counted.add(new UUID(9, i % players), "P", finding("Reach", 1));
        for (int i = 0; i < departed; i += 8) {
            online.add(new UUID(8, i));
            back.add(new UUID(8, i));
        }

        int drained = 0;
        for (int i = 0; i < departed; i++) {
            lookups.set(0);
            backLookups.set(0);
            counted.add(new UUID(9, i % players), "P", finding("Reach", 2));
            assertTrue(lookups.get() <= 2 + backLookups.get(), lookups.get() + " lookups in add " + i);
            drained += lookups.get();
        }

        assertEquals(departed, drained);
        assertEquals(0, counted.departures());
        assertEquals(players + back.size(), counted.size());
    }

    @Test
    void aLeaveDuringAnEvictionKeepsItsDeparture() throws Exception {
        fillPausing(ALEX, STEVE);
        leavePausing(ALEX);
        now.incrementAndGet();
        present.add(ALEX);

        overflowPausingAt(
                ALEX,
                () -> {
                    now.incrementAndGet();
                    present.remove(ALEX);
                },
                () -> pausing.left(ALEX));
        now.incrementAndGet();
        leavePausing(STEVE);
        pausing.add(new UUID(10, 0), "Late", finding("Reach", 1));

        assertTrue(pausing.recent(ALEX, FindingsLog.CAPACITY).isEmpty());
        assertEquals(
                FindingsLog.CAPACITY,
                pausing.recent(STEVE, FindingsLog.CAPACITY).size());
    }

    @Test
    void aPlayerBackAndReadDuringAnEvictionIsKept() throws Exception {
        fillPausing(ALEX, STEVE);
        leavePausing(ALEX);
        now.incrementAndGet();
        leavePausing(STEVE);
        present.add(ALEX);

        overflowPausingAt(
                ALEX,
                () -> {},
                () -> assertEquals(
                        FindingsLog.CAPACITY,
                        pausing.recent(ALEX, FindingsLog.CAPACITY).size()));

        assertEquals(
                FindingsLog.CAPACITY, pausing.recent(ALEX, FindingsLog.CAPACITY).size());
        assertTrue(pausing.recent(STEVE, FindingsLog.CAPACITY).isEmpty());
    }

    @Test
    void aPlayerWhoLeftAgainDuringAnEvictionIsEvictedAfterThoseGoneLonger() throws Exception {
        fillPausing(ALEX, STEVE);
        leavePausing(ALEX);
        now.incrementAndGet();
        leavePausing(STEVE);
        now.incrementAndGet();
        present.add(ALEX);

        overflowPausingAt(
                ALEX,
                () -> {
                    now.incrementAndGet();
                    present.remove(ALEX);
                },
                () -> pausing.left(ALEX));
        long leftAgain = now.get();

        assertTrue(pausing.recent(STEVE, FindingsLog.CAPACITY).isEmpty());
        assertEquals(
                FindingsLog.CAPACITY, pausing.recent(ALEX, FindingsLog.CAPACITY).size());
        assertEquals(1, pausing.departures());
        now.set(leftAgain + FindingsLog.RETENTION_MILLIS);
        assertEquals(
                FindingsLog.CAPACITY, pausing.recent(ALEX, FindingsLog.CAPACITY).size());
        now.incrementAndGet();
        assertTrue(pausing.recent(ALEX, FindingsLog.CAPACITY).isEmpty());
    }

    @Test
    void aFreshLeaveDuringASweepKeepsTheFindingsOfAPlayerBackFromAnOldOne() throws Exception {
        present.addAll(List.of(ALEX, STEVE));
        pausing.add(ALEX, "Alex", finding("Reach", 1));
        pausing.add(STEVE, "Steve", finding("Reach", 1));
        leavePausing(ALEX);
        present.add(ALEX);
        now.addAndGet(FindingsLog.RETENTION_MILLIS + 1);

        pausingAt(
                ALEX,
                () -> pausing.add(STEVE, "Steve", finding("Reach", 1)),
                () -> {
                    now.incrementAndGet();
                    present.remove(ALEX);
                },
                () -> pausing.left(ALEX));
        long leftAgain = now.get();

        assertEquals(1, pausing.recent(ALEX, 15).size());
        now.set(leftAgain + FindingsLog.RETENTION_MILLIS);
        assertEquals(1, pausing.recent(ALEX, 15).size());
        now.incrementAndGet();
        assertTrue(pausing.recent(ALEX, 15).isEmpty());
    }

    @Test
    void aSweepBeforeTheLeaveOfARejoinedPlayerKeepsTheirFindings() {
        online.addAll(List.of(ALEX, STEVE));
        log.add(ALEX, "Alex", finding("Reach", 1));
        leave(ALEX);
        online.add(ALEX);
        log.joined(ALEX);
        now.addAndGet(FindingsLog.RETENTION_MILLIS + 1);

        online.remove(ALEX);
        log.add(STEVE, "Steve", finding("Reach", 1));
        long leftAgain = now.get();
        log.left(ALEX);

        assertEquals(1, log.recent(ALEX, 15).size());
        now.set(leftAgain + FindingsLog.RETENTION_MILLIS);
        assertEquals(1, log.recent(ALEX, 15).size());
        now.incrementAndGet();
        assertTrue(log.recent(ALEX, 15).isEmpty());
    }

    @Test
    void aJoinMakesNoLog() {
        online.add(ALEX);
        log.joined(ALEX);
        assertEquals(0, log.size());
    }

    @Test
    void aPlayerFoundOfflineAgainAfterAnOnlineReadIsGoneFromThen() {
        online.add(ALEX);
        log.add(ALEX, "Alex", finding("Reach", 1));
        online.remove(ALEX);
        assertEquals(1, log.recent(ALEX, 15).size());
        online.add(ALEX);
        now.addAndGet(FindingsLog.RETENTION_MILLIS + 1);
        assertEquals(1, log.recent(ALEX, 15).size());

        online.remove(ALEX);
        assertEquals(1, log.recent(ALEX, 15).size());
        assertEquals(Set.of("Reach"), log.checks());
        assertEquals(1, log.top(null, 0, 10).size());
        now.addAndGet(FindingsLog.RETENTION_MILLIS);
        assertEquals(1, log.recent(ALEX, 15).size());
        now.incrementAndGet();
        assertTrue(log.recent(ALEX, 15).isEmpty());
    }

    private void leave(UUID player) {
        online.remove(player);
        log.left(player);
    }

    /** Fills {@link #pausing} to {@link FindingsLog#MAX_FINDINGS}, {@code players} last, all online. */
    private void fillPausing(UUID... players) {
        int others = (FindingsLog.MAX_FINDINGS - players.length * FindingsLog.CAPACITY) / FindingsLog.CAPACITY;
        for (int i = 0; i < others; i++) {
            UUID player = new UUID(9, i);
            present.add(player);
            for (int j = 0; j < FindingsLog.CAPACITY; j++) pausing.add(player, "P" + i, finding("Reach", 1));
        }
        for (UUID player : players) {
            present.add(player);
            for (int j = 0; j < FindingsLog.CAPACITY; j++) pausing.add(player, null, finding("Reach", 1));
        }
    }

    private void leavePausing(UUID player) {
        present.remove(player);
        pausing.left(player);
    }

    /** Adds one finding past the cap to {@link #pausing}, running {@code meanwhile} while eviction looks up {@code at}. */
    private void overflowPausingAt(UUID at, Runnable meanwhile, Runnable concurrent) throws Exception {
        UUID late = new UUID(10, 0);
        present.add(late);
        pausingAt(at, () -> pausing.add(late, "Late", finding("Reach", 1)), meanwhile, concurrent);
    }

    /**
     * Runs {@code held} on one thread until its online lookup of {@code at}, then {@code meanwhile} here, then
     * {@code concurrent} on another thread until it finishes or waits for {@link #pausing}'s lock, then lets both end.
     */
    private void pausingAt(UUID at, Runnable held, Runnable meanwhile, Runnable concurrent) throws Exception {
        pauseAt.set(at);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(held);
            assertTrue(paused.await(30, TimeUnit.SECONDS));
            meanwhile.run();
            Future<?> second = threads.submit(concurrent);
            while (!second.isDone() && pausing.waiting() == 0) Thread.onSpinWait();
            resume.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            threads.shutdownNow();
        }
    }

    private Finding finding(String check, double confidence) {
        return new Finding(now.get(), check, "AIM", confidence, "detail", 1);
    }
}
