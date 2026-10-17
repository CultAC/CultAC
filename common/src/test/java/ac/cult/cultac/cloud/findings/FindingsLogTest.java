package ac.cult.cultac.cloud.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FindingsLogTest {
    private static final UUID ALEX = new UUID(1, 1);
    private static final UUID STEVE = new UUID(2, 2);

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Set<UUID> online = new HashSet<>();
    private final FindingsLog log = new FindingsLog(now::get, online::contains);

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

    private void leave(UUID player) {
        online.remove(player);
        log.left(player);
    }

    private Finding finding(String check, double confidence) {
        return new Finding(now.get(), check, "AIM", confidence, "detail", 1);
    }
}
