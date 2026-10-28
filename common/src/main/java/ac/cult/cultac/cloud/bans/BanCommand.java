package ac.cult.cultac.cloud.bans;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Fills a staff-ban command template. Every value is made safe first: control characters and newlines go, whitespace
 * collapses to single spaces, and {@code reason} and {@code staff} keep at most {@link #MAX_TEXT} characters. Text
 * other than {@code reason} never contains {@code =}, so it cannot read as a key in a {@code key=value} command, and
 * text that would start with {@code -} gains a leading {@code '} within that limit, so it never reads as a flag. A
 * {@code player} name outside {@code [A-Za-z0-9_.*-]} or starting with {@code -} becomes the uuid, {@code staff_uuid}
 * is a dashed UUID or empty, and {@code link} and {@code evidence} keep only ASCII letters, digits and
 * {@code :/?=&.%_-~} and are empty unless they then start with {@code https://} or {@code http://}.
 */
public final class BanCommand {
    static final int MAX_TEXT = 200;

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.*-]+");
    private static final Pattern URL_DROPPED = Pattern.compile("[^A-Za-z0-9:/?=&.%_~-]");
    private static final long[] UNIT_SECONDS = {86400, 3600, 60};
    private static final String[] UNITS = {"d", "h", "m"};

    private BanCommand() {}

    /** {@code template} with each placeholder {@code ban} has a value for filled once; others stay as they are. */
    public static String fill(String template, StaffBans.Ban ban) {
        Map<String, String> values = values(ban);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        String command = out.toString().strip();
        return command.startsWith("/") ? command.substring(1) : command;
    }

    static Map<String, String> values(StaffBans.Ban ban) {
        String uuid = ban.player().toString();
        String name = clean(ban.name(), Integer.MAX_VALUE);
        Map<String, String> values = new HashMap<>();
        values.put("player", NAME.matcher(name).matches() && !name.startsWith("-") ? name : uuid);
        values.put("uuid", uuid);
        values.put("reason", unflagged(clean(ban.reason(), MAX_TEXT), MAX_TEXT));
        values.put("cheat", keyless(ban.cheatName() == null ? ban.cheat() : ban.cheatName(), Integer.MAX_VALUE));
        values.put("cheat_id", keyless(ban.cheat(), Integer.MAX_VALUE));
        values.put("duration", ban.durationSeconds() == null ? "" : duration(ban.durationSeconds()));
        values.put("seconds", ban.durationSeconds() == null ? "" : Long.toString(ban.durationSeconds()));
        values.put("staff", keyless(ban.staff(), MAX_TEXT));
        values.put("ban_id", keyless(ban.id(), Integer.MAX_VALUE));
        values.put("link", url(ban.link()));
        values.put("staff_uuid", staffUuid(ban.staffUuid()));
        values.put("evidence", url(ban.evidence()));
        return values;
    }

    /** {@code text} cleaned to at most {@code max} characters without {@code =}, so it never reads as a key. */
    static String keyless(@Nullable String text, int max) {
        return unflagged(clean(text == null ? null : text.replace("=", ""), max), max);
    }

    /** {@code text} as a lower-case dashed UUID; empty unless it is one. */
    static String staffUuid(@Nullable String text) {
        return text != null && StaffBans.ID.matcher(text).matches() ? text.toLowerCase(Locale.ROOT) : "";
    }

    /** {@code text} without characters outside {@link #URL_DROPPED}; empty when null or not an http(s) URL then. */
    static String url(@Nullable String text) {
        if (text == null) return "";
        String url = URL_DROPPED.matcher(text).replaceAll("");
        return url.startsWith("https://") || url.startsWith("http://") ? url : "";
    }

    /**
     * {@code text} with a {@code '} before a leading {@code -}, so a ban plugin never reads it as a flag, cut so the
     * whole stays within {@code max} characters.
     */
    static String unflagged(String text, int max) {
        return text.startsWith("-") ? "'" + cut(text, max - 1) : text;
    }

    /** {@code text}'s first {@code max} characters, one fewer when that would split a surrogate pair, stripped. */
    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).strip();
    }

    /** {@code seconds} in the largest of days, hours and minutes that divides it evenly, else in seconds. */
    static String duration(long seconds) {
        for (int i = 0; i < UNITS.length; i++) {
            if (seconds > 0 && seconds % UNIT_SECONDS[i] == 0) return seconds / UNIT_SECONDS[i] + UNITS[i];
        }
        return seconds + "s";
    }

    /** {@code text} without control characters, its whitespace runs one space, stripped, cut to {@code max}. */
    static String clean(String text, int max) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), 256));
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int type = Character.getType(c);
            if (Character.isISOControl(c)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) continue;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = true;
                continue;
            }
            if (space && !out.isEmpty()) out.append(' ');
            space = false;
            out.append(c);
        }
        return cut(out.toString(), max);
    }
}
