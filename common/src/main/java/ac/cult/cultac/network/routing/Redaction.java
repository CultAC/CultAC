package ac.cult.cultac.network.routing;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Hides the credentials a broker URL carries in its user info from text bound for a log or a reply. A URL that does not
 * parse with a host keeps only its scheme and what follows its last {@code @}, or becomes {@value #HIDDEN}.
 */
final class Redaction {
    /** What stands for a URL that does not parse with a host and has no {@code @}. */
    static final String HIDDEN = "<hidden URL>";

    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://");

    /** A scheme at the start of the text or after whitespace, an opening bracket, a quote, a comma, a semicolon or =. */
    private static final Pattern URL_START = Pattern.compile("(?<![^\\s\\[(<{\"',;=])" + SCHEME.pattern());

    /** Characters that end a sentence or a list around a URL rather than belong to it. */
    private static final String TRAILING = ".,;:!?)]}>\"'";

    private Redaction() {}

    /**
     * {@code text} with every URL in it redacted as {@link #url} does; {@code "null"} for null. A URL runs to the next
     * one, and when an {@code @} follows it before then, everything up to the last such {@code @} is its user info.
     */
    static String text(@Nullable String text) {
        if (text == null) return "null";
        Matcher start = URL_START.matcher(text);
        StringBuilder redacted = new StringBuilder(text.length());
        int copied = 0;
        boolean found = start.find();
        while (found) {
            int from = start.start();
            found = start.find();
            int to = found ? start.start() : text.length();
            redacted.append(text, copied, from).append(segment(text.substring(from, to)));
            copied = to;
        }
        return redacted.append(text, copied, text.length()).toString();
    }

    /** A stretch of text that starts with a URL, the URL redacted. */
    private static String segment(String segment) {
        if (segment.indexOf('@') >= 0) return url(segment);
        int end = 0;
        while (end < segment.length() && !Character.isWhitespace(segment.charAt(end))) end++;
        while (end > 0 && TRAILING.indexOf(segment.charAt(end - 1)) >= 0) end--;
        return url(segment.substring(0, end)) + segment.substring(end);
    }

    /**
     * {@code url} with its user info replaced by {@code ***}. One that does not parse with a host keeps its scheme and
     * what follows its last {@code @}, or becomes {@value #HIDDEN} without one; an empty one stays empty.
     */
    static String url(String url) {
        if (url.isEmpty()) return url;
        Matcher scheme = SCHEME.matcher(url);
        String prefix = scheme.lookingAt() ? scheme.group() : "";
        String rest = url.substring(prefix.length());
        URI parsed = withHost(prefix.isEmpty() ? "x://" + rest : url);
        if (parsed != null) {
            String userInfo = parsed.getRawUserInfo();
            return userInfo == null ? url : prefix + "***" + rest.substring(userInfo.length());
        }
        int at = rest.lastIndexOf('@');
        return at < 0 ? HIDDEN : prefix + "***" + rest.substring(at);
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
