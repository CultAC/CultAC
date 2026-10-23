package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.commands.LabelWizard.Verdict;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

/**
 * The wording of the {@code /cult label <player>} menu: what each line says, and which label on file the new ones would
 * clash with.
 */
final class LabelLines {
    static String javaOnly() {
        return "The label menu needs a Java client. "
                + "Use " + CloudCommandService.command("label <player> <cheat> <high|extreme> <24h|3d|7d|30d> [note]")
                + ", or " + CloudCommandService.command("label <player> clean <high|extreme> [note]") + ".";
    }

    static final String PICK_ONE = "Pick at least one check.";
    /** A Clean label's window: the service labels the stored play and keeps the player clean until it is revoked. */
    static final String ALWAYS = "Always: stored play and from now on, until revoked";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter JOINED = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);
    private static final String DOT = " · ";
    private static final String ARMED = "armed";

    private LabelLines() {}

    /** {@code prefix} white, then the name yellow and bold. */
    static Component title(String prefix, String name) {
        return Component.text()
                .append(Component.text(prefix, NamedTextColor.WHITE))
                .append(Component.text(name, NamedTextColor.YELLOW, TextDecoration.BOLD))
                .asComponent();
    }

    /** Every Next button's label: plain yellow, never bold and never with a count. */
    static Component next() {
        return Component.text("Next ▶", NamedTextColor.YELLOW);
    }

    static Component verdict(Verdict verdict) {
        return Component.text(verdict.display, verdict.color, TextDecoration.BOLD);
    }

    /** The display name of a label cheat; Clean for {@code all}. */
    static String name(String cheat) {
        if (cheat.equals(LabelRequest.ALL)) return Verdict.CLEAN.display;
        return LabelWizard.NAMES.getOrDefault(cheat, cheat);
    }

    static String lastDays(int days) {
        return days == 1 ? "last 1 day" : "last " + days + " days";
    }

    /** A time online: {@code 42m}, {@code 3h 5m}, {@code 2d 4h}. */
    static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + minutes % 60 + "m";
        return hours / 24 + "d " + hours % 24 + "h";
    }

    /** Page 1's status line: how long the target has been online, else that they are offline. */
    static String status(@Nullable Long onlineMillis) {
        return onlineMillis == null ? "Offline" : "Online " + duration(onlineMillis);
    }

    /** Page 4's first card line: {@code Notch · online 42m} green, or {@code Notch · offline} grey. */
    static Component presence(String name, @Nullable Long onlineMillis) {
        return onlineMillis == null
                ? Component.text(name + DOT + "offline", NamedTextColor.GRAY)
                : Component.text(name + DOT + "online " + duration(onlineMillis), NamedTextColor.GREEN);
    }

    /** When the target first joined; null when the server does not know. */
    static @Nullable String firstJoined(long millis) {
        if (millis <= 0) return null;
        return "First joined: " + JOINED.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC));
    }

    /** The verdict, the confidence when it has one, and the span unless Clean. */
    static Component summary(Verdict verdict, boolean asksConfidence, String confidence, int days) {
        String rest = (asksConfidence ? DOT + capitalized(confidence) + " confidence" : "")
                + (verdict == Verdict.CLEAN ? "" : DOT + lastDays(days));
        return Component.text()
                .append(verdict(verdict))
                .append(Component.text(rest, NamedTextColor.WHITE))
                .asComponent();
    }

    /** The frozen window: {@code Oct 8 14:02 → Oct 15 14:02 UTC}. */
    static String window(long from, long to) {
        return MINUTE.format(utc(from)) + " → " + MINUTE.format(utc(to)) + " UTC";
    }

    /**
     * A picked check, with how often it flagged in the window when the service said; {@code other} has no count, and
     * a check the service gave no count for shows its name alone.
     */
    static Component check(String cheat, @Nullable Map<String, Integer> flags, int days) {
        Component name = Component.text(name(cheat), NamedTextColor.WHITE);
        Integer count = flags == null || cheat.equals("other") ? null : flags.get(cheat);
        if (count == null) return name;
        return Component.text()
                .append(name)
                .append(Component.text(flagCount(count, days), NamedTextColor.GRAY))
                .asComponent();
    }

    static String flagCount(int count, int days) {
        return DOT + count + (count == 1 ? " flag" : " flags") + " in the " + lastDays(days);
    }

    /**
     * The label on file the new labels clash with, as the service's overlap check sees it: for Cheating or Suspected
     * an active clean label whose span meets the window from {@code from} to {@code to} (inclusive), for Clean, which
     * has no span and so covers all time, any active positive or armed label with a span. A forward clean label runs
     * on from when it was made; a label without a span in time clashes with nothing. The service refuses the clash
     * except for a suspected post.
     */
    static @Nullable JsonObject conflict(Verdict verdict, List<JsonObject> labels, long from, long to) {
        long start = verdict == Verdict.CLEAN ? Long.MIN_VALUE : from;
        long end = verdict == Verdict.CLEAN ? Long.MAX_VALUE : to;
        for (JsonObject label : labels) {
            if (!active(label)) continue;
            boolean clean =
                    LabelRequest.ALL.equals(text(label, "cheat")) && LabelRequest.NEGATIVE.equals(text(label, "value"));
            boolean cheat = List.of(LabelRequest.POSITIVE, ARMED).contains(text(label, "value"));
            if (verdict == Verdict.CLEAN ? !cheat : !clean) continue;
            long[] span = span(label);
            if (span != null && start <= span[1] && span[0] <= end) return label;
        }
        return null;
    }

    /**
     * The conflict block: what is on file and by whom over when, then that the new labels will be refused, or for
     * Suspected that they are still accepted. The labeller is shown as {@link LabelClient#field} reads it from
     * {@code reply}.
     */
    static List<Component> conflictLines(Verdict verdict, JsonObject label, LabelClient.Reply reply) {
        boolean clean = verdict == Verdict.CLEAN;
        long[] span = span(label);
        String labeller = LabelClient.field(label, "labeller", reply);
        String when = span == null ? "" : DOT + spanDates(span[0], span[1]);
        Component head = Component.text()
                .append(Component.text(
                        "⚠ Active " + (clean ? "Cheating" : "Clean") + " label",
                        NamedTextColor.GOLD,
                        TextDecoration.BOLD))
                .append(Component.text(DOT + labeller + when, NamedTextColor.GRAY))
                .asComponent();
        Component outcome = verdict == Verdict.SUSPECTED
                ? Component.text("Suspected labels are still accepted.", NamedTextColor.GRAY)
                : Component.text(
                        clean
                                ? "This will be refused; revoke it first."
                                : "This will be refused; revoke the Clean first.",
                        NamedTextColor.GOLD);
        return List.of(head, outcome);
    }

    /** A label's dates: {@code Oct 10 → 16}, {@code Oct 28 → Nov 2}, or {@code Oct 10 → ongoing}. */
    static String spanDates(long from, long to) {
        ZonedDateTime start = utc(from);
        if (to == Long.MAX_VALUE) return DAY.format(start) + " → ongoing";
        ZonedDateTime end = utc(to);
        boolean sameMonth = start.getYear() == end.getYear() && start.getMonth() == end.getMonth();
        return DAY.format(start) + " → " + (sameMonth ? String.valueOf(end.getDayOfMonth()) : DAY.format(end));
    }

    /** The outcome's colour: all saved, some saved, or none. */
    static NamedTextColor resultColor(int saved, int total) {
        if (saved == total && total > 0) return NamedTextColor.GREEN;
        return saved > 0 ? NamedTextColor.GOLD : NamedTextColor.RED;
    }

    /** The chat line when no label saved: {@code Label failed for Notch: <reason>}. */
    static String failed(String player, String reason) {
        return "Label failed for " + player + ": " + reason;
    }

    /** What a confirm labelled: {@code Labelled Notch cheating (KillAura), high (last 7 days)}. */
    static String echo(
            String player, Verdict verdict, List<String> checks, boolean asksConfidence, String confidence, int days) {
        String what = verdict == Verdict.CLEAN
                ? "clean across all checks"
                : verdict.display.toLowerCase(Locale.ROOT) + " ("
                        + String.join(
                                ", ", checks.stream().map(LabelLines::name).toList()) + ")";
        return "Labelled " + player + " " + what + (asksConfidence ? ", " + confidence : "")
                + (verdict == Verdict.CLEAN ? "" : " (" + lastDays(days) + ")");
    }

    static String capitalized(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static boolean active(JsonObject label) {
        JsonElement revoked = label.get("revokedAt");
        return revoked == null || revoked.isJsonNull();
    }

    /** A label's span in epoch millis, ending at {@link Long#MAX_VALUE} for a forward clean label; null for none. */
    private static long @Nullable [] span(JsonObject label) {
        if (label.has("forwardClean")
                && label.get("forwardClean").isJsonPrimitive()
                && label.get("forwardClean").getAsBoolean()) {
            String created = text(label, "createdAt");
            if (created == null) return new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
            try {
                return new long[] {Instant.parse(created).toEpochMilli(), Long.MAX_VALUE};
            } catch (DateTimeParseException unreadable) {
                return new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
            }
        }
        Long from = number(label, "fromEpochMillis");
        Long to = number(label, "toEpochMillis");
        return from == null || to == null ? null : new long[] {from, to};
    }

    private static @Nullable Long number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) return null;
        return value.getAsLong();
    }

    private static @Nullable String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static ZonedDateTime utc(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC);
    }
}
