package ac.cult.cultac.cloud;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Server ids for intake sockets. The intake service keeps one socket per key and server id, so writers that share a
 * key need distinct ids: {@code cloud.server-id} is a template whose {@code {proxy}} is {@code cloud.proxy-id} and
 * whose {@code {server}} is the backend's name. Each value has its dots turned into {@code -} before it is put in, so
 * the template's own dots are the id's only ones and {@code {proxy}.{server}} tells every proxy and backend apart.
 */
public final class CloudServerIds {
    public static final int MAX_LENGTH = 128;

    private static final Collisions COLLISIONS = new Collisions();

    private CloudServerIds() {}

    /**
     * The id {@code template} names for {@code server}; null when the template uses {@code {proxy}} and
     * {@code proxyId} is empty, or nothing valid is left.
     */
    public static @Nullable String resolve(String template, String proxyId, String server) {
        if (template.contains("{proxy}") && proxyId.isBlank()) return null;
        String id = sanitize(
                template.replace("{proxy}", proxyId.replace('.', '-')).replace("{server}", server.replace('.', '-')));
        if (id.length() > MAX_LENGTH) id = id.substring(0, MAX_LENGTH);
        if (id.isEmpty()) return null;
        COLLISIONS.seen(id, input(template, proxyId, server));
        return id;
    }

    /** The values {@code template} uses, as a collision warning names them. */
    static String input(String template, String proxy, String server) {
        boolean usesProxy = template.contains("{proxy}");
        boolean usesServer = template.contains("{server}");
        if (usesProxy && usesServer) return "proxy-id '" + proxy + "' and server '" + server + "'";
        if (usesProxy) return "proxy-id '" + proxy + "'";
        if (usesServer) return "server '" + server + "'";
        return "template '" + template + "'";
    }

    /**
     * Keeps {@code [a-z0-9._-]} after lowercasing and turns any other character into {@code -}, dropping what precedes
     * the first letter or digit.
     */
    static String sanitize(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            boolean alphanumeric = c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
            if (alphanumeric) out.append(c);
            else if (!out.isEmpty()) out.append(c == '.' || c == '_' ? c : '-');
        }
        return out.toString();
    }

    /** The inputs each id was resolved from, warning once when two different inputs share an id. */
    static final class Collisions {
        private static final int MAX_IDS = 1024;

        private final Map<String, String> inputs = new ConcurrentHashMap<>();
        private final AtomicBoolean warned = new AtomicBoolean();

        /** Whether {@code input} gives an id another input already gave. */
        boolean seen(String id, String input) {
            String previous = inputs.size() < MAX_IDS ? inputs.putIfAbsent(id, input) : inputs.get(id);
            if (previous == null || previous.equals(input)) return false;
            if (warned.compareAndSet(false, true)) {
                LogUtil.warn("Cloud server id " + id + " names both " + previous + " and " + input
                        + "; the intake cannot tell them apart. Give them names that differ in letters or digits");
            }
            return true;
        }
    }
}
