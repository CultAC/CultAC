package ac.cult.cultac.manager.config.update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Ordered log of mutations a migration performed on a {@link YamlMap}. The
 * updater drains the log AFTER the migration returns and replays each op via
 * {@link ConfigPatcher} so the bundled default's comments stay intact.
 */
abstract class WriteLog {

    enum Op {
        PUT,
        REMOVE
    }

    /**
     * @param value null for {@linkplain Op#REMOVE REMOVE}
     */
    record Entry(Op op, String path, Object value) {}

    abstract void recordPut(@NotNull String path, @NotNull Object value);

    /**
     * Snapshot of the log collapsed into a path -> value map (latest write
     * wins per path; null sentinel = REMOVE). Used by the updater for
     * own-file writes; sibling-file writes use {@link #drainQueue} instead.
     */
    abstract @NotNull Map<String, Object> finalState();

    static @NotNull WriteLog active() {
        return new ActiveLog();
    }

    static @NotNull WriteLog noop() {
        return NoopLog.INSTANCE;
    }

    /**
     * Sibling-file log: each {@code recordPut} appends to a shared
     * per-filename queue that the updater flushes at the end of
     * {@code updateAll()}.
     */
    static @NotNull WriteLog sibling(@NotNull String siblingName, @NotNull Map<String, List<Entry>> sharedQueue) {
        return new SiblingLog(siblingName, sharedQueue);
    }

    private static final class ActiveLog extends WriteLog {
        private final List<Entry> entries = new ArrayList<>();

        @Override
        void recordPut(@NotNull String path, @NotNull Object value) {
            entries.add(new Entry(Op.PUT, path, value));
        }

        @Override
        @NotNull
        Map<String, Object> finalState() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Entry e : entries) {
                out.put(e.path, e.op == Op.PUT ? e.value : null);
            }
            return out;
        }
    }

    private static final class NoopLog extends WriteLog {
        static final NoopLog INSTANCE = new NoopLog();

        @Override
        void recordPut(@NotNull String path, @NotNull Object value) {}

        @Override
        @NotNull
        Map<String, Object> finalState() {
            return Map.of();
        }
    }

    private static final class SiblingLog extends WriteLog {
        private final String siblingName;
        private final Map<String, List<Entry>> sharedQueue;

        SiblingLog(String siblingName, Map<String, List<Entry>> sharedQueue) {
            this.siblingName = siblingName;
            this.sharedQueue = sharedQueue;
        }

        @Override
        void recordPut(@NotNull String path, @NotNull Object value) {
            sharedQueue.computeIfAbsent(siblingName, k -> new ArrayList<>()).add(new Entry(Op.PUT, path, value));
        }

        @Override
        @NotNull
        Map<String, Object> finalState() {
            // Sibling logs flush via the shared queue, not finalState().
            return Map.of();
        }
    }
}
