package ac.cult.cultac.manager.config.update;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Default {@link YamlMap} implementation. Wraps a parsed YAML map (snakeyaml-
 * shaped — nested {@code Map<String, Object>}). All writes additionally
 * accumulate in an external {@link WriteLog} so the updater can later replay
 * them through {@link ConfigPatcher} (preserving the bundled default's
 * comments).
 *
 * <p>A read-only view (input / sibling-file reads with no apply log) is
 * obtained by passing a {@link WriteLog#noop()}.
 */
final class YamlMapImpl implements YamlMap {

    private final Map<String, Object> root;
    private final WriteLog writeLog;

    YamlMapImpl(@NotNull Map<String, Object> root, @NotNull WriteLog writeLog) {
        this.root = root;
        this.writeLog = writeLog;
    }

    // ----- reads -----

    @Override
    public @Nullable Object get(@NotNull String dottedPath) {
        return get(YamlMap.keys(dottedPath));
    }

    @Override
    public @Nullable Object get(@NotNull List<String> keys) {
        return walkRead(root, keys);
    }

    @Override
    public @Nullable String getString(@NotNull String dottedPath) {
        Object v = get(dottedPath);
        return v == null ? null : v.toString();
    }

    @Override
    public @Nullable Integer getInt(@NotNull String dottedPath) {
        Object v = get(dottedPath);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    @Override
    public @Nullable List<?> getList(@NotNull String dottedPath) {
        Object v = get(dottedPath);
        return v instanceof List<?> l ? l : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, Object> getMap(@NotNull String dottedPath) {
        Object v = get(dottedPath);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    // ----- writes -----

    @Override
    public @NotNull YamlMap put(@NotNull String dottedPath, @NotNull Object value) {
        return put(YamlMap.keys(dottedPath), value);
    }

    @Override
    public @NotNull YamlMap put(@NotNull List<String> keys, @NotNull Object value) {
        walkWrite(root, keys, value);
        writeLog.recordPut(List.copyOf(keys), value);
        return this;
    }

    // ----- internals -----

    @SuppressWarnings("unchecked")
    private static @Nullable Object walkRead(Map<String, Object> root, List<String> keys) {
        Object cursor = root; // empty path = root map view
        for (String part : keys) {
            if (!(cursor instanceof Map<?, ?> m)) return null;
            cursor = ((Map<String, Object>) m).get(part);
            if (cursor == null) return null;
        }
        return cursor;
    }

    @SuppressWarnings("unchecked")
    private static void walkWrite(Map<String, Object> root, List<String> keys, Object value) {
        Map<String, Object> cursor = root;
        for (int i = 0; i < keys.size() - 1; i++) {
            String part = keys.get(i);
            Object next = cursor.get(part);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<>();
                cursor.put(part, next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(keys.get(keys.size() - 1), value);
    }
}
