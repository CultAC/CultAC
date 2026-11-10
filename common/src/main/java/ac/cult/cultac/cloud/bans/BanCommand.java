package ac.cult.cultac.cloud.bans;

import ac.cult.cultac.cloud.CommandText;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Fills a staff-ban command template. Every value is made safe first: control characters and newlines go, whitespace
 * collapses to single spaces, and {@code reason}, {@code reason_label} and {@code staff} keep at most
 * {@link #MAX_TEXT} characters. Text other than {@code reason} and {@code reason_label} never contains {@code =}, so it
 * cannot read as a key in a {@code key=value} command, and text that would start with {@code -} gains a leading
 * {@code '} within that limit, so it never reads as a flag. A {@code player} name outside {@code [A-Za-z0-9_.*-]} or
 * starting with {@code -} becomes the uuid, {@code staff_uuid} is a dashed UUID or empty, and {@code link} and
 * {@code evidence} keep only ASCII letters, digits and {@code :/?=&.%_-~} and are empty unless they then start with
 * {@code https://} or {@code http://}.
 *
 * <p>The template is filled one whitespace-separated word at a time: a word holding a placeholder that fills empty is
 * left out whole, and the words that remain are joined by single spaces. A ban is {@linkplain Refused refused} rather
 * than filled when a placeholder the template uses would put a word starting with {@code -} into the command, other
 * than {@code silent}, which must be one flag starting with {@code -} or empty. It is also refused when the template
 * holds {@code =%} (a {@code key=value} command) and {@code reason} or {@code reason_label} holds a word starting
 * {@code name=}, and when the {@code reason} as the service sent it, used as {@code reason} or as a {@code reason_label}
 * that is the reason, starts with a {@linkplain #LENGTH_WORD length or server word}. {@code reason},
 * {@code reason_label}, {@code staff} and {@code cheat} lose {@code <}, section signs and {@code &} format codes, also
 * those the other removals expose, and a {@code reason} or {@code reason_label} that starts with a length or server
 * word only once they are gone gains a leading {@code '}.
 */
public final class BanCommand {
    static final int MAX_TEXT = 200;

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.*-]+");
    private static final Pattern URL_DROPPED = Pattern.compile("[^A-Za-z0-9:/?=&.%_~-]");
    /** A length token the service may send as {@code duration}, such as {@code 30d} or a permanent ban's {@code perm}. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:+-]{0,32}");
    private static final Pattern SILENT = Pattern.compile("-[A-Za-z0-9_.:+-]{0,31}");
    private static final Pattern FLAG_WORD = Pattern.compile("(?:^|\\s)-[A-Za-z-]");
    /** A word that starts {@code name=}, which a {@code key=value} command reads as another option. */
    private static final Pattern KEY_WORD = Pattern.compile("(?:^|\\s)[A-Za-z_][A-Za-z0-9_-]*=");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** A word ban plugins read as a ban's length or server scope, such as {@code 7d}, {@code 1h30m} or {@code server:lobby}. */
    private static final Pattern LENGTH_WORD = Pattern.compile(
            "(?i)(\\d+(s|secs?|seconds?|m|mins?|minutes?|h|hrs?|hours?|d|days?|w|wks?|weeks?|mo|mons?|months?|y|yrs?|years?))+"
                    + "|server:.*");
    private static final long[] UNIT_SECONDS = {86400, 3600, 60};
    private static final String[] UNITS = {"d", "h", "m"};
    /** The placeholders {@link #fill} fills, the keys of {@link #values}. */
    static final Set<String> NAMES = Set.of(
            "player",
            "uuid",
            "reason",
            "reason_label",
            "cheat",
            "cheat_id",
            "duration",
            "seconds",
            "silent",
            "staff",
            "ban_id",
            "link",
            "staff_uuid",
            "evidence");

    private BanCommand() {}

    /** The placeholders of {@link #NAMES} that {@code template} holds, sorted. */
    static SortedSet<String> used(String template) {
        SortedSet<String> used = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (NAMES.contains(matcher.group(1))) used.add(matcher.group(1));
        }
        return used;
    }

    /**
     * {@code template} with each placeholder {@code ban} has a value for filled once and others left as they are,
     * without the words whose placeholder filled empty, its whitespace runs single spaces and a leading {@code /}
     * dropped.
     *
     * @throws Refused when a used placeholder's value would add a flag or, in a {@code key=value} template, a key, or
     *     nothing is left to run
     */
    public static String fill(String template, StaffBans.Ban ban) {
        Map<String, String> values = values(ban);
        boolean keyed = template.contains("=%");
        Set<String> lengthless = ban.reasonLabel() == null ? Set.of("reason", "reason_label") : Set.of("reason");
        StringJoiner command = new StringJoiner(" ");
        for (String word : WHITESPACE.split(template.strip())) {
            Matcher matcher = PLACEHOLDER.matcher(word);
            StringBuilder out = new StringBuilder();
            boolean empty = false;
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = values.get(name);
                if (value == null) {
                    value = matcher.group();
                } else {
                    check(name, value, keyed);
                    if (lengthless.contains(name) && lengthFirst(clean(ban.reason(), Integer.MAX_VALUE))) {
                        throw new Refused("%" + name + "% starts with a length or a server: word");
                    }
                    empty |= value.isEmpty();
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(out);
            if (!empty && !out.isEmpty()) command.add(out);
        }
        String filled = command.toString();
        if (filled.startsWith("/")) filled = filled.substring(1).strip();
        if (filled.isEmpty()) throw new Refused("the command is empty once filled");
        return filled;
    }

    /**
     * Refuses {@code value} for the placeholder {@code name} when it would add a flag the panel did not send as one,
     * or, in a {@code keyed} template, when a reason holds a word starting {@code name=}.
     */
    private static void check(String name, String value, boolean keyed) {
        if (name.equals("silent")) {
            if (!value.isEmpty() && !SILENT.matcher(value).matches()) {
                throw new Refused("%silent% is not one flag starting with -");
            }
        } else if (FLAG_WORD.matcher(value).find()) {
            throw new Refused("%" + name + "% has a flag word (- and a letter)");
        } else if (keyed
                && (name.equals("reason") || name.equals("reason_label"))
                && KEY_WORD.matcher(value).find()) {
            throw new Refused("%" + name + "% has a name= word, which this key=value command would read as an option");
        }
    }

    /** Whether {@code text}'s first word is a {@link #LENGTH_WORD}. */
    static boolean lengthFirst(String text) {
        int space = text.indexOf(' ');
        return LENGTH_WORD.matcher(space < 0 ? text : text.substring(0, space)).matches();
    }

    static Map<String, String> values(StaffBans.Ban ban) {
        String uuid = ban.player().toString();
        String name = clean(ban.name(), Integer.MAX_VALUE);
        Map<String, String> values = new HashMap<>();
        values.put("player", NAME.matcher(name).matches() && !name.startsWith("-") ? name : uuid);
        values.put("uuid", uuid);
        values.put("reason", reason(ban.reason()));
        values.put("reason_label", reason(ban.reasonLabel() == null ? ban.reason() : ban.reasonLabel()));
        values.put("cheat", plainKeyless(ban.cheatName() == null ? ban.cheat() : ban.cheatName(), Integer.MAX_VALUE));
        values.put("cheat_id", keyless(ban.cheat(), Integer.MAX_VALUE));
        values.put("duration", duration(ban));
        values.put(
                "seconds",
                ban.term() == StaffBans.Term.TIMED && ban.durationSeconds() != null
                        ? Long.toString(ban.durationSeconds())
                        : "");
        values.put("silent", clean(ban.silentFlag(), Integer.MAX_VALUE));
        values.put("staff", plainKeyless(ban.staff(), MAX_TEXT));
        values.put("ban_id", keyless(ban.id(), Integer.MAX_VALUE));
        values.put("link", url(ban.link()));
        values.put("staff_uuid", staffUuid(ban.staffUuid()));
        values.put("evidence", url(ban.evidence()));
        return values;
    }

    /**
     * {@code %duration%}: the service's length token when it is one, else, for a timed ban, its seconds in the largest
     * unit that divides them; empty for a permanent ban without a token and for a ban whose length the server decides.
     */
    static String duration(StaffBans.Ban ban) {
        String token = ban.duration() == null ? null : clean(ban.duration(), Integer.MAX_VALUE);
        if (token != null && !TOKEN.matcher(token).matches()) token = null;
        return switch (ban.term()) {
            case TIMED -> token != null && !token.isEmpty()
                    ? token
                    : ban.durationSeconds() == null ? "" : duration(ban.durationSeconds());
            case PERMANENT -> token == null ? "" : token;
            case SERVER -> "";
        };
    }

    /**
     * {@code text} plain, cleaned and {@linkplain #unflagged unflagged} within {@link #MAX_TEXT}, with a {@code '}
     * before a {@link #LENGTH_WORD} that only its removals bring to the front.
     */
    static String reason(@Nullable String text) {
        String shown = unflagged(plain(text, false, MAX_TEXT), MAX_TEXT);
        return lengthFirst(shown) && !lengthFirst(clean(text, Integer.MAX_VALUE))
                ? "'" + cut(shown, MAX_TEXT - 1)
                : shown;
    }

    /**
     * {@code text} {@linkplain #clean cleaned} to at most {@code max} characters, without {@code <}, section signs and
     * {@code &} format codes, and without {@code =} when {@code keyless}; cleaning it again changes nothing.
     */
    static String plain(@Nullable String text, boolean keyless, int max) {
        if (text == null) return "";
        return clean(CommandText.plain(clean(keyless ? text.replace("=", "") : text, Integer.MAX_VALUE)), max);
    }

    /** {@code text} cleaned to at most {@code max} characters without {@code =}, so it never reads as a key. */
    static String keyless(@Nullable String text, int max) {
        return unflagged(clean(text == null ? null : text.replace("=", ""), max), max);
    }

    /** {@code text} {@linkplain #plain plain} and {@linkplain #keyless keyless} within {@code max}. */
    static String plainKeyless(@Nullable String text, int max) {
        return unflagged(plain(text, true, max), max);
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
     * {@code text} with a {@code '} before a leading {@code -} and a letter or {@code -}, so a ban plugin never reads it as a flag, cut so the
     * whole stays within {@code max} characters.
     */
    static String unflagged(String text, int max) {
        return FLAG_WORD.matcher(text).lookingAt() ? "'" + cut(text, max - 1) : text;
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

    /** A ban that is not run: a value would add a flag, or no command fits it; the message says which. */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String message) {
            super(message);
        }
    }
}
