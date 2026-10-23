package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.command.commands.LabelWizard.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class LabelLinesTest {
    private static final long DAY = LabelWizard.DAY_MS;
    private static final long TO = Instant.parse("2026-10-15T14:02:00Z").toEpochMilli();
    private static final long FROM = TO - 7 * DAY;
    private static final LabelClient.Reply REPLY = new LabelClient.Reply(200, "");

    @Test
    void flagCountsAreSingularAndPlural() {
        Map<String, Integer> flags = Map.of("killaura", 14, "triggerbot", 1, "other", 9);
        assertEquals("KillAura · 14 flags in the last 7 days", plain(LabelLines.check("killaura", flags, 7)));
        assertEquals("Triggerbot · 1 flag in the last 1 day", plain(LabelLines.check("triggerbot", flags, 1)));
        assertEquals("Other", plain(LabelLines.check("other", flags, 7)));
        assertEquals("Auto Mace", plain(LabelLines.check("automace", flags, 7)));
        assertEquals(
                "Chest Stealer · 2 flags in the last 7 days",
                plain(LabelLines.check("chest-stealer", Map.of("chest-stealer", 2), 7)));
        assertEquals("KillAura", plain(LabelLines.check("killaura", Map.of(), 7)));
        assertEquals("KillAura", plain(LabelLines.check("killaura", null, 7)));
    }

    @Test
    void theCardSaysWhetherTheTargetIsOnline() {
        Component online = LabelLines.presence("Notch", 42 * 60_000L);
        assertEquals("Notch · online 42m", plain(online));
        assertEquals(NamedTextColor.GREEN, online.color());
        Component offline = LabelLines.presence("Notch", null);
        assertEquals("Notch · offline", plain(offline));
        assertEquals(NamedTextColor.GRAY, offline.color());
        assertEquals("Online 3h 5m", LabelLines.status(185 * 60_000L));
        assertEquals("Online 2d 4h", LabelLines.status(52 * 3_600_000L));
        assertEquals("Offline", LabelLines.status(null));
        assertEquals(
                "First joined: Sep 02, 2026",
                LabelLines.firstJoined(Instant.parse("2026-09-02T10:00:00Z").toEpochMilli()));
        assertNull(LabelLines.firstJoined(0));
    }

    @Test
    void theSummaryNamesTheConfidenceUnlessSuspected() {
        assertEquals(
                "Cheating · High confidence · last 7 days",
                plain(LabelLines.summary(Verdict.CHEATING, true, "high", 7)));
        assertEquals("Suspected · last 1 day", plain(LabelLines.summary(Verdict.SUSPECTED, false, "high", 1)));
        assertEquals("Oct 8 14:02 → Oct 15 14:02 UTC", LabelLines.window(FROM, TO));
        assertEquals("Review Notch", plain(LabelLines.title("Review ", "Notch")));
    }

    @Test
    void aCleanLabelThatMeetsTheWindowClashesWithACheatLabel() {
        JsonObject clean = label("all", "negative", FROM - 3 * DAY, FROM + DAY, null);
        assertSame(clean, LabelLines.conflict(Verdict.CHEATING, List.of(clean), FROM, TO));
        assertSame(clean, LabelLines.conflict(Verdict.SUSPECTED, List.of(clean), FROM, TO));
        assertNull(LabelLines.conflict(Verdict.CLEAN, List.of(clean), FROM, TO));
    }

    @Test
    void cleanClashesWithAnyActiveCheatLabelWhenever() {
        JsonObject old = label("killaura", "positive", FROM - 300 * DAY, FROM - 299 * DAY, null);
        assertSame(old, LabelLines.conflict(Verdict.CLEAN, List.of(old), FROM, TO));
        JsonObject oldClean = label("all", "negative", FROM - 300 * DAY, FROM - 299 * DAY, null);
        assertNull(LabelLines.conflict(Verdict.CHEATING, List.of(oldClean), FROM, TO));
        JsonObject bySession = JsonParser.parseString(
                        "{\"cheat\":\"killaura\",\"value\":\"positive\",\"sessionId\":\"x\",\"tickFrom\":1,\"tickTo\":9}")
                .getAsJsonObject();
        assertNull(LabelLines.conflict(Verdict.CLEAN, List.of(bySession), FROM, TO));
    }

    @Test
    void cleanShowsNoSpanAndEveryNextIsPlain() {
        assertEquals("Clean · High confidence", plain(LabelLines.summary(Verdict.CLEAN, true, "high", 7)));
        assertEquals(
                "Labelled Notch clean across all checks, extreme",
                LabelLines.echo("Notch", Verdict.CLEAN, List.of(), true, "extreme", 7));
        assertEquals("Next ▶", plain(LabelLines.next()));
        assertFalse(LabelLines.next().hasDecoration(TextDecoration.BOLD));
    }

    @Test
    void aSpanThatTouchesTheWindowMeetsIt() {
        JsonObject before = label("all", "negative", FROM - 3 * DAY, FROM, null);
        assertSame(before, LabelLines.conflict(Verdict.CHEATING, List.of(before), FROM, TO));
        JsonObject earlier = label("all", "negative", FROM - 3 * DAY, FROM - 1, null);
        assertNull(LabelLines.conflict(Verdict.CHEATING, List.of(earlier), FROM, TO));
    }

    @Test
    void aRevokedLabelOrAnotherCheatDoesNotClash() {
        JsonObject revoked = label("all", "negative", FROM, TO, "2026-10-14T00:00:00Z");
        assertNull(LabelLines.conflict(Verdict.CHEATING, List.of(revoked), FROM, TO));
        JsonObject cheat = label("killaura", "positive", FROM, TO, null);
        assertNull(LabelLines.conflict(Verdict.CHEATING, List.of(cheat), FROM, TO));
        assertSame(cheat, LabelLines.conflict(Verdict.CLEAN, List.of(revoked, cheat), FROM, TO));
        JsonObject suspected = label("triggerbot", "suspected", FROM, TO, null);
        assertNull(LabelLines.conflict(Verdict.CLEAN, List.of(suspected), FROM, TO));
        JsonObject armed = label("triggerbot", "armed", FROM, TO, null);
        assertSame(armed, LabelLines.conflict(Verdict.CLEAN, List.of(suspected, armed), FROM, TO));
        JsonObject bySession = JsonParser.parseString(
                        "{\"cheat\":\"all\",\"value\":\"negative\",\"sessionId\":\"x\",\"tickFrom\":1,\"tickTo\":9}")
                .getAsJsonObject();
        assertNull(LabelLines.conflict(Verdict.CHEATING, List.of(bySession), FROM, TO));
    }

    @Test
    void aForwardCleanLabelRunsOn() {
        JsonObject forward = JsonParser.parseString("{\"cheat\":\"all\",\"value\":\"negative\",\"forwardClean\":true,"
                        + "\"createdAt\":\"2026-10-01T00:00:00Z\",\"labeller\":\"Alex\"}")
                .getAsJsonObject();
        assertSame(forward, LabelLines.conflict(Verdict.CHEATING, List.of(forward), FROM, TO));
        assertEquals(
                List.of(
                        "⚠ Active Clean label · Alex · Oct 1 → ongoing",
                        "This will be refused; revoke the Clean first."),
                LabelLines.conflictLines(Verdict.CHEATING, forward, REPLY).stream()
                        .map(LabelLinesTest::plain)
                        .toList());
    }

    @Test
    void theConflictNamesWhoAndWhen() {
        JsonObject clean = label("all", "negative", FROM - 5 * DAY, FROM + DAY, null);
        clean.addProperty("labeller", "Alex");
        assertEquals(
                List.of("⚠ Active Clean label · Alex · Oct 3 → 9", "This will be refused; revoke the Clean first."),
                LabelLines.conflictLines(Verdict.CHEATING, clean, REPLY).stream()
                        .map(LabelLinesTest::plain)
                        .toList());
        JsonObject cheat = label("killaura", "positive", FROM - 10 * DAY, TO + 20 * DAY, null);
        cheat.addProperty("labeller", "Alex");
        assertEquals(
                List.of("⚠ Active Cheating label · Alex · Sep 28 → Nov 4", "This will be refused; revoke it first."),
                LabelLines.conflictLines(Verdict.CLEAN, cheat, REPLY).stream()
                        .map(LabelLinesTest::plain)
                        .toList());
    }

    @Test
    void aSuspectedLabelOverACleanOneIsStillAccepted() {
        JsonObject clean = label("all", "negative", FROM - 5 * DAY, FROM + DAY, null);
        clean.addProperty("labeller", "Alex");
        assertEquals(
                List.of("⚠ Active Clean label · Alex · Oct 3 → 9", "Suspected labels are still accepted."),
                LabelLines.conflictLines(Verdict.SUSPECTED, clean, REPLY).stream()
                        .map(LabelLinesTest::plain)
                        .toList());
        assertEquals(
                NamedTextColor.GRAY,
                LabelLines.conflictLines(Verdict.SUSPECTED, clean, REPLY).get(1).color());
    }

    @Test
    void theResultSaysHowManySaved() {
        assertEquals(NamedTextColor.GREEN, LabelLines.resultColor(2, 2));
        assertEquals(NamedTextColor.GOLD, LabelLines.resultColor(1, 2));
        assertEquals(NamedTextColor.RED, LabelLines.resultColor(0, 2));
        assertEquals("Label failed for Notch: HTTP 409", LabelLines.failed("Notch", "HTTP 409"));
        assertEquals(
                "Labelled Notch cheating (KillAura, Triggerbot), high (last 7 days)",
                LabelLines.echo("Notch", Verdict.CHEATING, List.of("killaura", "triggerbot"), true, "high", 7));
        assertEquals(
                "Labelled Notch suspected (Other) (last 1 day)",
                LabelLines.echo("Notch", Verdict.SUSPECTED, List.of("other"), false, "high", 1));
    }

    private static JsonObject label(String cheat, String value, long from, long to, String revokedAt) {
        JsonObject label = new JsonObject();
        label.addProperty("id", "11111111-1111-1111-1111-111111111111");
        label.addProperty("cheat", cheat);
        label.addProperty("value", value);
        label.addProperty("fromEpochMillis", from);
        label.addProperty("toEpochMillis", to);
        label.addProperty("forwardClean", false);
        if (revokedAt != null) label.addProperty("revokedAt", revokedAt);
        return label;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
