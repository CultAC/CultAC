package ac.cult.cultac.cloud;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * {@code cloud.uncaptured-servers}: the backends whose players get no capture segment. Each entry is a backend name,
 * matched whole and ignoring case, in which {@code *} stands for any run of characters, none included. Blank entries
 * are ignored.
 */
final class UncapturedServers {
    static final UncapturedServers NONE = new UncapturedServers(List.of());

    private final List<String> patterns;

    private UncapturedServers(List<String> patterns) {
        this.patterns = patterns;
    }

    static UncapturedServers of(@Nullable Collection<String> entries) {
        if (entries == null || entries.isEmpty()) return NONE;
        List<String> patterns = new ArrayList<>(entries.size());
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            String pattern = entry.strip().toLowerCase(Locale.ROOT);
            if (!patterns.contains(pattern)) patterns.add(pattern);
        }
        return patterns.isEmpty() ? NONE : new UncapturedServers(List.copyOf(patterns));
    }

    boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** Whether backend {@code server} is one of these; false for a null or blank name. */
    boolean matches(@Nullable String server) {
        if (patterns.isEmpty() || server == null || server.isBlank()) return false;
        String name = server.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < patterns.size(); i++) {
            if (glob(patterns.get(i), name)) return true;
        }
        return false;
    }

    /** Whether {@code pattern}, whose {@code *} matches any run of characters, matches all of {@code name}. */
    static boolean glob(String pattern, String name) {
        int p = 0;
        int n = 0;
        int star = -1;
        int resume = 0;
        while (n < name.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                resume = n;
            } else if (p < pattern.length() && pattern.charAt(p) == name.charAt(n)) {
                p++;
                n++;
            } else if (star >= 0) {
                p = star + 1;
                n = ++resume;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    @Override
    public String toString() {
        return patterns.toString();
    }
}
