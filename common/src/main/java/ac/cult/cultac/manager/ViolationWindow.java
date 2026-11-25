package ac.cult.cultac.manager;

import java.util.ArrayDeque;

/** The flags a punishment group counts, oldest first; each flag counts once until it is older than the window. */
final class ViolationWindow {
    private final ArrayDeque<Run> runs = new ArrayDeque<>();
    private int size;

    /** Counts one flag of the check with {@code checkKey} at {@code now}. */
    void add(long now, String checkKey) {
        Run last = runs.peekLast();
        if (last != null && last.time == now && last.checkKey.equals(checkKey)) {
            last.count++;
        } else {
            runs.addLast(new Run(now, checkKey));
        }
        size++;
    }

    /** Drops the flags more than {@code windowMillis} older than {@code now}. */
    void expire(long now, long windowMillis) {
        Run first;
        while ((first = runs.peekFirst()) != null && now - first.time > windowMillis) {
            size -= first.count;
            runs.pollFirst();
        }
    }

    /** The flags in the window. */
    int size() {
        return size;
    }

    /** The flags in the window of the check with {@code checkKey}. */
    int count(String checkKey) {
        int count = 0;
        for (Run run : runs) {
            if (run.checkKey.equals(checkKey)) count += run.count;
        }
        return count;
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
