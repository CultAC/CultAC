package ac.cult.cultac.cloud.labels;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A staff label as {@code /cult label} gives it: a cheat, always positive, over a time span ending now, or clean
 * ({@code all}, negative) with no span, which the service takes as the player's stored play and all play from now on
 * until revoked; each with a confidence ({@code high} or {@code extreme}) and an optional note. {@link Window} is a label
 * over an explicit window, which may also be suspected, or a clean one without a window.
 */
public record LabelRequest(
        String cheat, String confidence, @Nullable Span span, String note) {
    public static final List<String> CHEATS = List.of(
            "triggerbot",
            "killaura",
            "autoclicker",
            "autototem",
            "crystalaura",
            "autoanchor",
            "automace",
            "shieldbreaker",
            "autostunslam",
            "jumpreset",
            "chest-stealer",
            "other");

    public static final String ALL = "all";
    public static final String POSITIVE = "positive";
    public static final String NEGATIVE = "negative";
    /** The service's wire name for a suspected label, which may leave out its confidence. */
    public static final String SUSPECTED = "suspected";

    public static final List<String> CONFIDENCES = List.of("high", "extreme");
    /** The service caps a label's note at this many characters. */
    public static final int MAX_NOTE = 1000;

    private static final long HOUR_MS = 3_600_000L;

    /** A label's time span, ending when it is sent. */
    public enum Span {
        H24("24h", "last 24 hours", 24 * HOUR_MS),
        D3("3d", "last 3 days", 72 * HOUR_MS),
        D7("7d", "last 7 days", 168 * HOUR_MS),
        D30("30d", "last 30 days", 720 * HOUR_MS);

        private final String wire;
        private final String display;
        private final long millis;

        Span(String wire, String display, long millis) {
            this.wire = wire;
            this.display = display;
            this.millis = millis;
        }

        public String wire() {
            return wire;
        }

        public String display() {
            return display;
        }

        /** The span by its name, any case; null for none. */
        public static @Nullable Span of(String wire) {
            for (Span span : values()) if (span.wire.equalsIgnoreCase(wire.strip())) return span;
            return null;
        }

        public static List<String> wires() {
            return Arrays.stream(values()).map(Span::wire).toList();
        }
    }

    /** A clean label: every check, negative, with no span. */
    public static LabelRequest clean(String confidence, @Nullable String note) {
        return new LabelRequest(ALL, level(confidence), null, strippedNote(note));
    }

    /** A positive label of one cheat. */
    public static LabelRequest cheat(String cheat, String confidence, String span, @Nullable String note) {
        String c = knownCheat(cheat);
        String level = level(confidence);
        Span s = Span.of(span);
        if (s == null) throw new IllegalArgumentException("Time span is one of: " + String.join(", ", Span.wires()));
        return new LabelRequest(c, level, s, strippedNote(note));
    }

    private static String level(String confidence) {
        String level = confidence.toLowerCase(Locale.ROOT).strip();
        if (!CONFIDENCES.contains(level)) {
            throw new IllegalArgumentException("Confidence is one of: " + String.join(", ", CONFIDENCES));
        }
        return level;
    }

    private static String strippedNote(@Nullable String note) {
        String n = note == null ? "" : note.strip();
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("A note is at most " + MAX_NOTE + " characters.");
        return n;
    }

    private static String knownCheat(String cheat) {
        String c = cheat.toLowerCase(Locale.ROOT);
        if (!CHEATS.contains(c))
            throw new IllegalArgumentException("Unknown cheat '" + cheat + "'. One of: " + String.join(", ", CHEATS));
        return c;
    }

    public boolean clean() {
        return cheat.equals(ALL);
    }

    /** {@code negative} for clean, else {@code positive}. */
    public String value() {
        return clean() ? NEGATIVE : POSITIVE;
    }

    /**
     * The {@code POST /api/v1/labels} body for {@code player} over the span ending at {@code now}, without a span for
     * a clean label; {@code labellerId} is the labelling player's UUID.
     */
    public JsonObject body(UUID player, long now, String labeller, UUID labellerId) {
        return span == null
                ? body(player, null, null, cheat, value(), confidence, note, labeller, labellerId)
                : body(player, now - span.millis, now, cheat, value(), confidence, note, labeller, labellerId);
    }

    private static JsonObject body(
            UUID player,
            @Nullable Long from,
            @Nullable Long to,
            String cheat,
            String value,
            @Nullable String confidence,
            String note,
            String labeller,
            UUID labellerId) {
        JsonObject body = new JsonObject();
        body.addProperty("playerId", player.toString());
        if (from != null) body.addProperty("fromEpochMillis", from);
        if (to != null) body.addProperty("toEpochMillis", to);
        body.addProperty("cheat", cheat);
        body.addProperty("value", value);
        body.addProperty("source", "staff");
        if (confidence != null) body.addProperty("confidence", confidence);
        body.addProperty("lookedAtBecause", "staff");
        body.addProperty("evidenceIndependent", false);
        body.addProperty("labeller", labeller);
        body.addProperty("labellerId", labellerId.toString());
        body.addProperty("evidence", note.isEmpty() ? "in-game label by " + labeller : note);
        return body;
    }

    /**
     * A label over the window {@code from} to {@code to} (epoch millis): a cheat positive or suspected, or clean
     * ({@code all}, negative) with no window. A suspected label has no confidence.
     */
    public record Window(
            String cheat,
            String value,
            @Nullable String confidence,
            @Nullable Long from,
            @Nullable Long to,
            String note) {
        public Window {
            if ((from == null) != (to == null)) throw new IllegalArgumentException("A label's window has both ends.");
            if (from != null && from >= to) {
                throw new IllegalArgumentException("A label's window ends after it starts.");
            }
        }

        /** A positive label of one cheat. */
        public static Window cheat(String cheat, String confidence, long from, long to, @Nullable String note) {
            return new Window(knownCheat(cheat), POSITIVE, level(confidence), from, to, strippedNote(note));
        }

        /** A suspected label of one cheat, without a confidence. */
        public static Window suspected(String cheat, long from, long to, @Nullable String note) {
            return new Window(knownCheat(cheat), SUSPECTED, null, from, to, strippedNote(note));
        }

        /** A clean label: every check, negative, with no window. */
        public static Window clean(String confidence, @Nullable String note) {
            return new Window(ALL, NEGATIVE, level(confidence), null, null, strippedNote(note));
        }

        public boolean clean() {
            return cheat.equals(ALL);
        }

        /** The {@code POST /api/v1/labels} body for {@code player}; {@code labellerId} is the labelling player's UUID. */
        public JsonObject body(UUID player, String labeller, UUID labellerId) {
            return LabelRequest.body(player, from, to, cheat, value, confidence, note, labeller, labellerId);
        }
    }
}
