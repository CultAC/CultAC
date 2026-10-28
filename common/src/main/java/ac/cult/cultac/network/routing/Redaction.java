package ac.cult.cultac.network.routing;

import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Hides the credentials a broker URL carries in its user info from text bound for a log or a reply. */
final class Redaction {
    /** A scheme and everything after it up to the last {@code @} before whitespace, which ends any user info. */
    private static final Pattern USER_INFO = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)\\S*@");

    private Redaction() {}

    /** {@code text} with the user info of every URL in it replaced by {@code ***}; {@code "null"} for null. */
    static String text(@Nullable String text) {
        if (text == null) return "null";
        return USER_INFO.matcher(text).replaceAll("$1***@");
    }
}
