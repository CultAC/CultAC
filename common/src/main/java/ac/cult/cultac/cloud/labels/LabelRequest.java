package ac.cult.cultac.cloud.labels;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A staff label as {@code /cult label} gives it: a cheat, always positive, or clean ({@code all}, negative), with
 * a confidence ({@code high} or {@code extreme}), a time span ending now and an optional note.
 */
public record LabelRequest(String cheat, String confidence, Span span, String note) {
    public static final List<String> CHEATS = List.of(
            "triggerbot",
            "aimassist",
            "autoclicker",
            "autototem",
            "crystalaura",
            "autoanchor",
            "automace",
            "shieldbreaker",
            "autostunslam",
            "jumpreset",
            "other");

    public static final String ALL = "all";
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

    /** A clean label: every check, negative. */
    public static LabelRequest clean(String confidence, String span, @Nullable String note) {
        return of(ALL, confidence, span, note);
    }

    /** A positive label of one cheat. */
    public static LabelRequest cheat(String cheat, String confidence, String span, @Nullable String note) {
        String c = cheat.toLowerCase(Locale.ROOT);
        if (!CHEATS.contains(c))
            throw new IllegalArgumentException("Unknown cheat '" + cheat + "'. One of: " + String.join(", ", CHEATS));
        return of(c, confidence, span, note);
    }

    private static LabelRequest of(String cheat, String confidence, String span, @Nullable String note) {
        String level = confidence.toLowerCase(Locale.ROOT).strip();
        if (!CONFIDENCES.contains(level)) {
            throw new IllegalArgumentException("Confidence is one of: " + String.join(", ", CONFIDENCES));
        }
        Span s = Span.of(span);
        if (s == null) throw new IllegalArgumentException("Time span is one of: " + String.join(", ", Span.wires()));
        String n = note == null ? "" : note.strip();
        if (n.length() > MAX_NOTE) throw new IllegalArgumentException("A note is at most " + MAX_NOTE + " characters.");
        return new LabelRequest(cheat, level, s, n);
    }

    public boolean clean() {
        return cheat.equals(ALL);
    }

    /** {@code negative} for clean, else {@code positive}. */
    public String value() {
        return clean() ? "negative" : "positive";
    }

    /**
     * The {@code POST /api/v1/labels} body for {@code player} over the span ending at {@code now}; {@code labellerId}
     * is the labelling player's UUID.
     */
    public JsonObject body(UUID player, long now, String labeller, UUID labellerId) {
        JsonObject body = new JsonObject();
        body.addProperty("playerId", player.toString());
        body.addProperty("fromEpochMillis", now - span.millis);
        body.addProperty("toEpochMillis", now);
        body.addProperty("cheat", cheat);
        body.addProperty("value", value());
        body.addProperty("source", "staff");
        body.addProperty("confidence", confidence);
        body.addProperty("lookedAtBecause", "staff");
        body.addProperty("evidenceIndependent", false);
        body.addProperty("labeller", labeller);
        body.addProperty("labellerId", labellerId.toString());
        body.addProperty("evidence", note.isEmpty() ? "in-game label by " + labeller : note);
        return body;
    }
}
