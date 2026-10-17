package ac.cult.cultac.cloud.findings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
        final ArrayDeque<Finding> ring = new ArrayDeque<>();
        volatile String name;
        /** When the player left; 0 while online. */
        volatile long leftAt;

        PlayerLog(String name) {
            this.name = name;
        }

        /** False when {@code finding} took the oldest one's place. */
        synchronized boolean add(Finding finding) {
            boolean full = ring.size() == CAPACITY;
            if (full) ring.pollFirst();
            ring.addLast(finding);
            return !full;
        }

        synchronized void dropOldest() {
            ring.pollFirst();
        }

        synchronized int size() {
            return ring.size();
        }

        synchronized List<Finding> snapshot() {
            return new ArrayList<>(ring);
        }
    }

    private final Map<UUID, PlayerLog> logs = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Predicate<UUID> online;
    private volatile long nextSweep;
    /** Findings in every log; guarded by this. */
    private int total;

    public FindingsLog(LongSupplier clock, Predicate<UUID> online) {
        this.clock = clock;
        this.online = online;
    }

    /** Keeps {@code finding} if {@code player} is online or their log is still kept; drops it otherwise. */
    public synchronized void add(UUID player, String name, Finding finding) {
        PlayerLog log = logs.get(player);
        if (log == null) {
            if (!online.test(player)) return;
            log = new PlayerLog(name);
            logs.put(player, log);
        }
        if (name != null) log.name = name;
        if (log.add(finding)) total++;
        sweep();
        if (total > MAX_FINDINGS) {
            prune();
            while (total > MAX_FINDINGS) evict();
        }
    }

    /** {@code player} left: their log, empty if they have none, is kept for findings that arrive afterwards. */
    public synchronized void left(UUID player) {
        logs.computeIfAbsent(player, ignored -> new PlayerLog(null)).leftAt = clock.getAsLong();
        sweep();
    }

    /** Drops the log of the player gone longest that holds any finding, else the fullest log's oldest finding. */
    private void evict() {
        Map.Entry<UUID, PlayerLog> gone = null;
        Map.Entry<UUID, PlayerLog> fullest = null;
        for (Map.Entry<UUID, PlayerLog> entry : logs.entrySet()) {
            PlayerLog log = entry.getValue();
            int size = log.size();
            if (log.leftAt != 0 && size > 0 && (gone == null || log.leftAt < gone.getValue().leftAt)) gone = entry;
            if (fullest == null || size > fullest.getValue().size()) fullest = entry;
        }
        if (gone != null) {
            logs.remove(gone.getKey());
            total -= gone.getValue().size();
        } else {
            fullest.getValue().dropOldest();
            total--;
        }
    }

    /** Up to {@code limit} findings, newest first. */
    public List<Finding> recent(UUID player, int limit) {
        prune();
        PlayerLog log = logs.get(player);
        if (log == null) return List.of();
        List<Finding> all = log.snapshot();
        List<Finding> newest = new ArrayList<>(Math.min(limit, all.size()));
        for (int i = all.size() - 1; i >= 0 && newest.size() < limit; i--) newest.add(all.get(i));
        return newest;
    }

    /** Every check name logged, sorted, ignoring case. */
    public Set<String> checks() {
        prune();
        Set<String> checks = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PlayerLog log : logs.values()) {
            for (Finding finding : log.snapshot()) checks.add(finding.check());
        }
        return checks;
    }

    /**
     * Up to {@code limit} players by their findings since {@code since}, all checks or only {@code check}: highest
     * confidence first, then most findings.
     */
    public List<Ranked> top(@Nullable String check, long since, int limit) {
        prune();
        List<Ranked> ranked = new ArrayList<>();
        for (Map.Entry<UUID, PlayerLog> entry : logs.entrySet()) {
            int count = 0;
            double max = 0;
            Map<String, Integer> checks = new HashMap<>();
            for (Finding finding : entry.getValue().snapshot()) {
                if (finding.at() < since || (check != null && !finding.check().equalsIgnoreCase(check))) continue;
                count++;
                max = Math.max(max, finding.confidence());
                checks.merge(finding.check(), 1, Integer::sum);
            }
            if (count == 0) continue;
            List<Map.Entry<String, Integer>> frequent = new ArrayList<>(checks.entrySet());
            frequent.sort(
                    Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            if (frequent.size() > TOP_CHECKS) frequent = frequent.subList(0, TOP_CHECKS);
            ranked.add(new Ranked(entry.getKey(), entry.getValue().name, count, max, List.copyOf(frequent)));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::max)
                .thenComparingInt(Ranked::count)
                .reversed());
        if (ranked.size() > limit) ranked = ranked.subList(0, limit);
        return List.copyOf(ranked);
    }

    /**
     * Forgets players gone longer than the retention who have not come back. A player found offline without a
     * {@link #left} counts as gone from now, so a late finding cannot keep a log forever.
     */
    synchronized void prune() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<UUID, PlayerLog>> entries = logs.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, PlayerLog> entry = entries.next();
            PlayerLog log = entry.getValue();
            if (online.test(entry.getKey())) {
                log.leftAt = 0;
            } else if (log.leftAt == 0) {
                log.leftAt = now;
            } else if (now - log.leftAt > RETENTION_MILLIS) {
                entries.remove();
                total -= log.size();
            }
        }
    }

    /** {@link #prune} at most once a minute, so logs expire without anyone reading them. */
    private void sweep() {
        long now = clock.getAsLong();
        if (now < nextSweep) return;
        nextSweep = now + SWEEP_MILLIS;
        prune();
    }

    int size() {
        return logs.size();
    }
}
