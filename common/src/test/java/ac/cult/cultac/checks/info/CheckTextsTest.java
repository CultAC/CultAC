package ac.cult.cultac.checks.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CheckTextsTest {
    private static final int MAX_SUMMARY = 70;
    private static final int MAX_DETAILS = 300;
    private static final Pattern DIGIT = Pattern.compile("\\d");

    private final CheckTexts texts = CheckTexts.load();

    @Test
    void explainsEveryCheckInTheCatalog() {
        List<String> missing = new ArrayList<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            CheckTexts.Text text = texts.text(entry.name());
            if (text == null || text.summary().isBlank() || text.details().isBlank()) missing.add(entry.name());
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void explainsNoCheckOutsideTheCatalog() {
        Set<String> extra = new TreeSet<>(texts.names());
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) extra.remove(entry.name());
        assertEquals(Set.of(), extra);
    }

    @Test
    void labelsEveryCategory() {
        for (CheckCategory category : CheckCategory.values()) {
            assertNotEquals(category.id(), texts.label(category), category.id());
        }
    }

    /** Explanations are public: a number in one could tell a cheat how far it can go. */
    @Test
    void keepsTextsShortAndFreeOfNumbers() {
        List<String> failures = new ArrayList<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            CheckTexts.Text text = texts.text(entry.name());
            if (text == null) continue;
            if (text.summary().length() > MAX_SUMMARY) failures.add(entry.name() + " summary is too long");
            if (text.details().length() > MAX_DETAILS) failures.add(entry.name() + " details are too long");
            if (DIGIT.matcher(text.summary() + text.details()).find()) failures.add(entry.name() + " has a number");
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void parsesChecksAndCategoryLabels() {
        CheckTexts parsed = CheckTexts.parse("""
                categories:
                  combat: "Fighting"
                checks:
                  SprintD:
                    summary: "Sprinted while blinded"
                    details: "Blind players cannot sprint."
                """);
        assertEquals(
                new CheckTexts.Text("Sprinted while blinded", "Blind players cannot sprint."), parsed.text("SprintD"));
        assertEquals("Fighting", parsed.label(CheckCategory.COMBAT));
        assertEquals("movement", parsed.label(CheckCategory.MOVEMENT));
    }
}
