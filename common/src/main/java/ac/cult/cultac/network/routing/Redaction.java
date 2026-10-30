package ac.cult.cultac.network.routing;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Hides the network's secrets from text bound for a log or a reply: the chosen broker's password, token and
 * {@code user:password}, and the network secret, then any URL's user info. Values under {@value #MIN_KNOWN_LENGTH}
 * characters stay.
 */
final class Redaction {
    static final String MASK = "***";

    static final int MIN_KNOWN_LENGTH = 4;

    private static final String SEPARATOR = "://";

    private final List<String> known;

    private Redaction(Collection<String> known) {
        this.known = known.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    static Redaction of(NetworkSettings settings) {
        NetworkSettings.Redis redis = settings.redis();
        NetworkSettings.Nats nats = settings.nats();
        return of(List.of(
                settings.secret(),
                redis.password(),
                redis.username() + ":" + redis.password(),
                nats.password(),
                nats.username() + ":" + nats.password(),
                nats.token()));
    }

    static Redaction of(Collection<String> secrets) {
        Set<String> known = new LinkedHashSet<>();
        for (String secret : secrets) {
            if (secret.length() >= MIN_KNOWN_LENGTH && !secret.equals(MASK)) known.add(secret);
        }
        return new Redaction(known);
    }

    /** {@code text} with every known value replaced, then masked as {@link #text} does; {@code "null"} for null. */
    String redact(@Nullable String text) {
        if (text == null) return "null";
        for (String value : known) text = text.replace(value, MASK);
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
}
