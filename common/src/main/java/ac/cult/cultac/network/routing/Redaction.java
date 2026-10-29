package ac.cult.cultac.network.routing;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Hides the network's secrets from text bound for a log or a reply. A redactor knows the values the settings hold:
 * each broker URL, which becomes its {@link #url} form; the user info (a password or a token) and the password in each
 * URL, raw and decoded; the network secret. {@link #redact} replaces each of them, longest first, skipping any shorter
 * than {@value #MIN_KNOWN_LENGTH} characters, then masks what is left as {@link #text} does.
 */
final class Redaction {
    /** What stands for a URL that does not parse with a host and has no {@code @}. */
    static final String HIDDEN = "<hidden URL>";

    static final String MASK = "***";

    static final int MIN_KNOWN_LENGTH = 4;

    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://");

    private static final String SEPARATOR = "://";

    /** Each known value, longest first, with what replaces it. */
    private final List<Map.Entry<String, String>> known;

    private Redaction(Map<String, String> known) {
        this.known = known.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> value) ->
                                value.getKey().length())
                        .reversed())
                .toList();
    }

    /** A redactor for each server in the URI of {@code settings}, its user info and the network secret. */
    static Redaction of(NetworkSettings settings) {
        List<String> urls =
                settings.uri().equalsIgnoreCase(NetworkSettings.REDISBUNGEE) ? List.of() : settings.servers();
        return of(urls, List.of(settings.secret()));
    }

    /** A redactor for {@code urls} and the plain values {@code secrets}. */
    static Redaction of(Collection<String> urls, Collection<String> secrets) {
        Map<String, String> known = new LinkedHashMap<>();
        for (String url : urls) {
            String stripped = url.strip();
            know(known, stripped, url(stripped));
            for (String credential : credentials(stripped)) know(known, credential, MASK);
        }
        for (String secret : secrets) know(known, secret, MASK);
        return new Redaction(known);
    }

    private static void know(Map<String, String> known, String value, String replacement) {
        if (value.length() >= MIN_KNOWN_LENGTH && !value.equals(replacement)) known.putIfAbsent(value, replacement);
    }

    /**
     * The user info and the password after its first {@code :} in {@code url}, raw and decoded when it parses with a
     * host, else the raw text between its {@code ://} and its last {@code @}.
     */
    private static List<String> credentials(String url) {
        List<String> found = new ArrayList<>();
        URI parsed = withHost(SCHEME.matcher(url).lookingAt() ? url : "x://" + url);
        if (parsed != null) {
            userInfo(parsed.getRawUserInfo(), found);
            userInfo(parsed.getUserInfo(), found);
            return found;
        }
        int separator = url.indexOf(SEPARATOR);
        int from = separator < 0 ? 0 : separator + SEPARATOR.length();
        int at = url.lastIndexOf('@');
        if (at >= from) userInfo(url.substring(from, at), found);
        return found;
    }

    private static void userInfo(@Nullable String userInfo, List<String> found) {
        if (userInfo == null) return;
        found.add(userInfo);
        int colon = userInfo.indexOf(':');
        if (colon >= 0) found.add(userInfo.substring(colon + 1));
    }

    /** {@code text} with every known value replaced, then masked as {@link #text} does; {@code "null"} for null. */
    String redact(@Nullable String text) {
        if (text == null) return "null";
        for (Map.Entry<String, String> value : known) text = text.replace(value.getKey(), value.getValue());
        return text(text);
    }

    /**
     * {@code text} with the user info of each URL in it replaced by {@value #MASK}; {@code "null"} for null. A URL is
     * a scheme right before a {@code ://} and runs to the next whitespace; only one holding an {@code @} changes, from
     * after its {@code ://} up to its last {@code @}. Nothing outside a URL changes.
     */
    static String text(@Nullable String text) {
        if (text == null) return "null";
        StringBuilder redacted = null;
        int copied = 0;
        int separator = text.indexOf(SEPARATOR);
        while (separator >= 0) {
            int from = separator + SEPARATOR.length();
            int end = from;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            int at = text.lastIndexOf('@', end - 1);
            if (at >= from && schemeBefore(text, separator)) {
                if (redacted == null) redacted = new StringBuilder(text.length());
                redacted.append(text, copied, from).append(MASK);
                copied = at;
            }
            separator = text.indexOf(SEPARATOR, end);
        }
        return redacted == null
                ? text
                : redacted.append(text, copied, text.length()).toString();
    }

    /** Whether a scheme, a letter then letters, digits, {@code +}, {@code .} or {@code -}, ends at {@code separator}. */
    private static boolean schemeBefore(String text, int separator) {
        int start = separator;
        while (start > 0 && schemeCharacter(text.charAt(start - 1))) start--;
        for (int i = start; i < separator; i++) {
            if (letter(text.charAt(i))) return true;
        }
        return false;
    }

    private static boolean schemeCharacter(char c) {
        return letter(c) || c >= '0' && c <= '9' || c == '+' || c == '.' || c == '-';
    }

    private static boolean letter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }

    /**
     * {@code url} with its user info replaced by {@value #MASK}. One that does not parse with a host keeps its scheme
     * and what follows its last {@code @}, or becomes {@value #HIDDEN} without one; an empty one stays empty.
     */
    static String url(String url) {
        if (url.isEmpty()) return url;
        Matcher scheme = SCHEME.matcher(url);
        String prefix = scheme.lookingAt() ? scheme.group() : "";
        String rest = url.substring(prefix.length());
        URI parsed = withHost(prefix.isEmpty() ? "x://" + rest : url);
        if (parsed != null) {
            String userInfo = parsed.getRawUserInfo();
            return userInfo == null ? url : prefix + MASK + rest.substring(userInfo.length());
        }
        int at = rest.lastIndexOf('@');
        return at < 0 ? HIDDEN : prefix + MASK + rest.substring(at);
    }

    private static @Nullable URI withHost(String url) {
        try {
            URI parsed = new URI(url);
            return parsed.getHost() == null || parsed.getHost().isEmpty() ? null : parsed;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
