package ac.cult.cultac.manager;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;

/** The flags a punishment group counts, oldest first; each flag counts once until it is older than the window. */
final class ViolationWindow {
    private final ArrayDeque<Run> runs = new ArrayDeque<>();
    private final Object2IntOpenHashMap<String> counts = new Object2IntOpenHashMap<>();
    private int size;
    private long added;

    /** Counts one flag of the check with {@code checkKey} at {@code now}. */
    void add(long now, String checkKey) {
        Run last = runs.peekLast();
        if (last != null && last.time == now && last.checkKey.equals(checkKey)) {
            last.count++;
        } else {
            runs.addLast(new Run(now, checkKey));
        }
        counts.addTo(checkKey, 1);
        size++;
        added++;
    }

    /** The flags ever added, a mark for {@link #countAfter}. */
    long added() {
        return added;
    }

    /** The flags in the window added after {@code mark}. */
    int countAfter(long mark) {
        return (int) Math.min(size, added - mark);
    }

    /** Drops the flags more than {@code windowMillis} older than {@code now}. */
    void expire(long now, long windowMillis) {
        Run first;
        while ((first = runs.peekFirst()) != null && now - first.time > windowMillis) {
            size -= first.count;
            if (counts.addTo(first.checkKey, -first.count) == first.count) counts.removeInt(first.checkKey);
            runs.pollFirst();
        }
    }

    /** The flags in the window. */
    int size() {
        return size;
    }

    /** The flags in the window of the check with {@code checkKey}. */
    int count(String checkKey) {
        return counts.getInt(checkKey);
    }

    /** Consecutive flags of one check at one instant. */
    private static final class Run {
        private final long time;
        private final String checkKey;
        private int count = 1;

        private Run(long time, String checkKey) {
            this.time = time;
            this.checkKey = checkKey;
        }
    }
}
