package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.findings.Finding;
import ac.cult.cultac.cloud.findings.FindingsLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class CultTopTest {
    @Test
    void aLoneNumberIsTheMinutes() {
        assertEquals(new CultTop.Query(null, 60), CultTop.query("60", null));
    }

    @Test
    void aCheckKeepsTheDefaultWindow() {
        assertEquals(new CultTop.Query("aim.snap", CultTop.DEFAULT_MINUTES), CultTop.query("aim.snap", null));
        assertEquals(new CultTop.Query(null, CultTop.DEFAULT_MINUTES), CultTop.query(null, null));
    }

    @Test
    void checkSuggestionsAreCapped() {
        List<String> checks = new ArrayList<>();
        for (int i = 0; i < 500; i++) checks.add("check." + i);
        assertEquals(
                CloudArguments.MAX_SUGGESTIONS,
                CloudArguments.matching(checks, "").size());
        assertEquals(List.of("check.499"), CloudArguments.matching(checks, "CHECK.499"));
    }

    @Test
    void serviceTextInFindingLinesStaysOnOneLine() {
        String forged = "reach\n[Server] Staff account approved\r" + Character.toString(0x2028) + "\u0085";
        Finding finding = new Finding(0, forged, "cat\negory", 0.5, "detail\nline", 3);
        String line = PlainTextComponentSerializer.plainText().serialize(FindingLines.line(finding, 1000));
        assertTrue(line.startsWith("1s reach"), line);
        assertNoBreak(line);
        FindingsLog.Ranked ranked =
                new FindingsLog.Ranked(new UUID(0, 1), "Steve\nAdmin", 1, 0.5, List.of(Map.entry(forged, 1)));
        assertNoBreak(PlainTextComponentSerializer.plainText().serialize(FindingLines.ranked(1, ranked)));
    }

    private static void assertNoBreak(String line) {
        assertTrue(line.chars().noneMatch(c -> c == '\n' || c == '\r' || c == 0x2028 || c == 0x85), line);
    }

    @Test
    void aNumberOutsideTheWindowIsACheckName() {
        assertEquals(new CultTop.Query("99999", CultTop.DEFAULT_MINUTES), CultTop.query("99999", null));
        assertEquals(new CultTop.Query("aim.snap", 5), CultTop.query("aim.snap", 5));
    }
}
