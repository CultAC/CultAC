package ac.cult.cultac.cloud.findings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * The recent findings of each player, in memory only: the last {@link #CAPACITY} per player and {@link #MAX_FINDINGS}
 * in all, kept while the player is online and for {@link #RETENTION_MILLIS} after they leave. Safe to use from any
 * thread.
 */
public final class FindingsLog {
    public static final int CAPACITY = 200;
    /** Findings kept in all; past it the longest-gone player's log goes, else the fullest log's oldest finding. */
    public static final int MAX_FINDINGS = 20_000;

    public static final long RETENTION_MILLIS = TimeUnit.MINUTES.toMillis(30);
    static final int TOP_CHECKS = 3;
    static final long SWEEP_MILLIS = TimeUnit.MINUTES.toMillis(1);

    /** A player's place in {@link #top}: findings in the window, their highest confidence and most frequent checks. */
    public record Ranked(UUID player, String name, int count, double max, List<Map.Entry<String, Integer>> checks) {}

    private static final class PlayerLog {
        final UUID player;
        final ArrayDeque<Finding> ring = new ArrayDeque<>();
        String name;
        /** When the player left; 0 while online. Changes only through {@link #depart} and {@link #rejoin}. */
        long leftAt;
        /** Orders departures at the same {@link #leftAt}. */
        long sequence;

        PlayerLog(UUID player, String name) {
            this.player = player;
            this.name = name;
        }

        /** False when {@code finding} took the oldest one's place. */
        boolean add(Finding finding) {
            boolean full = ring.size() == CAPACITY;
            if (full) ring.pollFirst();
            ring.addLast(finding);
            return !full;
        }
    }

    /** Guards the logs and every count here; fair, so a stream of adds cannot starve a leave or a read. */
    private final ReentrantLock lock = new ReentrantLock(true);

    private final Map<UUID, PlayerLog> logs = new HashMap<>();
    /** The logs holding findings whose players are gone, longest gone first. */
    private final TreeSet<PlayerLog> gone = new TreeSet<>(
            Comparator.<PlayerLog>comparingLong(log -> log.leftAt).thenComparingLong(log -> log.sequence));
    /** The logs holding findings, by how many they hold. */
    private final List<Set<PlayerLog>> bySize = new ArrayList<>(CAPACITY + 1);
    /** No log holds more findings than this. */
    private int fullest;

    private final LongSupplier clock;
    private final Predicate<UUID> online;
    private long nextSweep;
    /** Departures so far, which orders those at the same time. */
    private long lastDeparture;
    /** Findings in every log. */
    private int total;

    public FindingsLog(LongSupplier clock, Predicate<UUID> online) {
        this.clock = clock;
        this.online = online;
        for (int size = 0; size <= CAPACITY; size++) bySize.add(new LinkedHashSet<>());
    }

    /** Keeps {@code finding} if {@code player} is online or their log is still kept; drops it otherwise. */
    public void add(UUID player, String name, Finding finding) {
        lock.lock();
        try {
            PlayerLog log = logs.get(player);
            if (log == null) {
                if (!online.test(player)) return;
                log = new PlayerLog(player, name);
                logs.put(player, log);
            }
            if (name != null) log.name = name;
            if (log.leftAt != 0 && online.test(player)) rejoin(log);
            int size = log.ring.size();
            if (log.add(finding)) {
                total++;
                resized(log, size, size + 1);
                if (size == 0 && log.leftAt != 0) gone.add(log);
            }
            sweep();
            while (total > MAX_FINDINGS) evict();
        } finally {
            lock.unlock();
        }
    }

    /** {@code player} left: their log, empty if they have none, is kept for findings that arrive afterwards. */
    public void left(UUID player) {
        lock.lock();
        try {
            depart(logs.computeIfAbsent(player, ignored -> new PlayerLog(player, null)), clock.getAsLong());
            sweep();
        } finally {
            lock.unlock();
        }
    }

    /** {@code player} is tracked again: their log, if they have one, is no longer gone; no log is made for them. */
    public void joined(UUID player) {
        lock.lock();
        try {
            PlayerLog log = logs.get(player);
            if (log != null && log.leftAt != 0) rejoin(log);
        } finally {
            lock.unlock();
        }
    }

    /** {@code log}'s player is gone from {@code at}; its place in {@link #gone} moves with its key. */
    private void depart(PlayerLog log, long at) {
        gone.remove(log);
        log.leftAt = at;
        log.sequence = ++lastDeparture;
        if (!log.ring.isEmpty()) gone.add(log);
    }

    private void rejoin(PlayerLog log) {
        gone.remove(log);
        log.leftAt = 0;
    }

    /**
     * Drops the log of the player gone longest, else the fullest log's oldest finding. Logs in {@link #gone} whose
     * players are back online, or that are empty or no longer their player's, leave it on the way.
     */
    private void evict() {
        PlayerLog log;
        while ((log = gone.pollFirst()) != null) {
            if (log.ring.isEmpty() || logs.get(log.player) != log) continue;
            if (online.test(log.player)) {
                rejoin(log);
                continue;
            }
            logs.remove(log.player);
            int size = log.ring.size();
            total -= size;
            resized(log, size, 0);
            return;
        }
        while (bySize.get(fullest).isEmpty()) fullest--;
        log = bySize.get(fullest).iterator().next();
        log.ring.pollFirst();
        total--;
        resized(log, fullest, fullest - 1);
    }

    /** {@code log} went from holding {@code from} findings to {@code to}. */
    private void resized(PlayerLog log, int from, int to) {
        if (from > 0) bySize.get(from).remove(log);
        if (to > 0) {
            bySize.get(to).add(log);
            fullest = Math.max(fullest, to);
        }
    }

    /** Up to {@code limit} findings, newest first. */
    public List<Finding> recent(UUID player, int limit) {
        lock.lock();
        try {
            PlayerLog log = logs.get(player);
            if (log == null || expired(log, clock.getAsLong())) return List.of();
            List<Finding> newest = new ArrayList<>(Math.min(limit, log.ring.size()));
            for (Iterator<Finding> findings = log.ring.descendingIterator();
                    findings.hasNext() && newest.size() < limit; ) newest.add(findings.next());
            return newest;
        } finally {
            lock.unlock();
        }
    }

    /** Every check name logged, sorted, ignoring case. */
    public Set<String> checks() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            Set<String> checks = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (PlayerLog log : logs.values()) {
                if (expired(log, now)) continue;
                for (Finding finding : log.ring) checks.add(finding.check());
            }
            return checks;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Up to {@code limit} players by their findings since {@code since}, all checks or only {@code check}: highest
     * confidence first, then most findings.
     */
    public List<Ranked> top(@Nullable String check, long since, int limit) {
        List<Ranked> ranked = new ArrayList<>();
        lock.lock();
        try {
            long now = clock.getAsLong();
            for (PlayerLog log : logs.values()) {
                if (expired(log, now)) continue;
                int count = 0;
                double max = 0;
                Map<String, Integer> checks = new HashMap<>();
                for (Finding finding : log.ring) {
                    if (finding.at() < since
                            || (check != null && !finding.check().equalsIgnoreCase(check))) continue;
                    count++;
                    max = Math.max(max, finding.confidence());
                    checks.merge(finding.check(), 1, Integer::sum);
                }
                if (count == 0) continue;
                List<Map.Entry<String, Integer>> frequent = new ArrayList<>(checks.entrySet());
                frequent.sort(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()));
                if (frequent.size() > TOP_CHECKS) frequent = frequent.subList(0, TOP_CHECKS);
                ranked.add(new Ranked(log.player, log.name, count, max, List.copyOf(frequent)));
            }
        } finally {
            lock.unlock();
        }
        ranked.sort(Comparator.comparingDouble(Ranked::max)
                .thenComparingInt(Ranked::count)
                .reversed());
        if (ranked.size() > limit) ranked = ranked.subList(0, limit);
        return List.copyOf(ranked);
    }

    /** Forgets players gone longer than the retention who have not come back. */
    void prune() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            Iterator<PlayerLog> entries = logs.values().iterator();
            while (entries.hasNext()) {
                PlayerLog log = entries.next();
                if (online.test(log.player)) {
                    if (log.leftAt != 0) rejoin(log);
                } else if (log.leftAt == 0) {
                    depart(log, now);
                } else if (now - log.leftAt > RETENTION_MILLIS) {
                    entries.remove();
                    gone.remove(log);
                    int size = log.ring.size();
                    total -= size;
                    resized(log, size, 0);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether {@code log}'s player is gone longer than the retention. A player found online is no longer gone; one
     * found offline without a {@link #left} counts as gone from now, so a late finding cannot keep a log forever.
     */
    private boolean expired(PlayerLog log, long now) {
        if (online.test(log.player)) {
            if (log.leftAt != 0) rejoin(log);
            return false;
        }
        if (log.leftAt == 0) {
            depart(log, now);
            return false;
        }
        return now - log.leftAt > RETENTION_MILLIS;
    }

    /** {@link #prune} at most once a minute, so logs expire without anyone reading them; the lock's holder only. */
    private void sweep() {
        long now = clock.getAsLong();
        if (now < nextSweep) return;
        nextSweep = now + SWEEP_MILLIS;
        prune();
    }

    int size() {
        lock.lock();
        try {
            return logs.size();
        } finally {
            lock.unlock();
        }
    }

    int departures() {
        lock.lock();
        try {
            return gone.size();
        } finally {
            lock.unlock();
        }
    }

    int waiting() {
        return lock.getQueueLength();
    }
}
