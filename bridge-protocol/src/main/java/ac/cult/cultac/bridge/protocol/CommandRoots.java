package ac.cult.cultac.bridge.protocol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class CommandRoots {
    public static final List<String> DEFAULT = List.of("cult", "cultac", "grim", "grimac");
    public static final String KEY = "command-aliases";

    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+");

    private CommandRoots() {}

    public static List<String> resolve(List<String> configured, Consumer<String> warn) {
        if (configured == null) return DEFAULT;
        Set<String> roots = new LinkedHashSet<>();
        for (String name : configured) {
            String root = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
            if (!NAME.matcher(root).matches()) {
                warn.accept(KEY + " names an invalid command root \"" + name + "\"; using " + DEFAULT);
                return DEFAULT;
            }
            roots.add(root);
        }
        if (roots.isEmpty()) {
            warn.accept(KEY + " names no command root; using " + DEFAULT);
            return DEFAULT;
        }
        return List.copyOf(roots);
    }
}
