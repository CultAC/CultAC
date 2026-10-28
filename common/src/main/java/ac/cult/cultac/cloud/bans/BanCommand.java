package ac.cult.cultac.cloud.bans;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Fills a staff-ban command template. Every value is made safe first: control characters and newlines go, whitespace
 * collapses to single spaces, {@code reason} and {@code staff} keep at most {@link #MAX_TEXT} characters and gain a
 * leading {@code '} when they start with {@code -}, a {@code staff} name never contains {@code =} so it cannot read as
 * a key in a {@code key=value} command, a {@code player} name outside {@code [A-Za-z0-9_.*-]} becomes the uuid,
 * {@code staff_uuid} keeps only hex digits and dashes, and {@code evidence} only ASCII letters, digits and
 * {@code :/?=&.%_-~}.
 */
public final class BanCommand {
    static final int MAX_TEXT = 200;

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.*-]+");
    private static final Pattern STAFF_UUID = Pattern.compile("[^0-9A-Fa-f-]");
    private static final Pattern EVIDENCE = Pattern.compile("[^A-Za-z0-9:/?=&.%_~-]");
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
        values.put("player", NAME.matcher(name).matches() ? name : uuid);
        values.put("uuid", uuid);
        values.put("reason", unflagged(clean(ban.reason(), MAX_TEXT)));
        values.put("cheat", clean(ban.cheatName() == null ? ban.cheat() : ban.cheatName(), Integer.MAX_VALUE));
        values.put("cheat_id", clean(ban.cheat(), Integer.MAX_VALUE));
        values.put("duration", ban.durationSeconds() == null ? "" : duration(ban.durationSeconds()));
        values.put("seconds", ban.durationSeconds() == null ? "" : Long.toString(ban.durationSeconds()));
        values.put("staff", unflagged(clean(ban.staff().replace("=", ""), MAX_TEXT)));
        values.put("ban_id", clean(ban.id(), Integer.MAX_VALUE));
        values.put("link", clean(ban.link(), Integer.MAX_VALUE));
        values.put("staff_uuid", keep(ban.staffUuid(), STAFF_UUID));
        values.put("evidence", keep(ban.evidence(), EVIDENCE));
        return values;
    }

    /** {@code text} without the characters {@code dropped} matches; empty when null. */
    static String keep(@Nullable String text, Pattern dropped) {
        return text == null ? "" : dropped.matcher(text).replaceAll("");
    }

    /** {@code text} with a {@code '} before a leading {@code -}, so a ban plugin never reads it as a flag. */
    static String unflagged(String text) {
        return text.startsWith("-") ? "'" + text : text;
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
        if (out.length() <= max) return out.toString();
        int end = max;
        if (Character.isHighSurrogate(out.charAt(end - 1))) end--;
        return out.substring(0, end).strip();
    }
}
