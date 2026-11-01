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
    private static final CheckTexts TEXTS = CheckTexts.load();

    @Test
    void theTextsCoverExactlyTheCatalog() {
        Set<String> catalog = new TreeSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) catalog.add(entry.name());
        assertEquals(catalog, new TreeSet<>(TEXTS.names()));
    }

    @Test
    void everyCategoryHasALabel() {
        for (CheckCategory category : CheckCategory.values()) {
            assertNotEquals(category.id(), TEXTS.label(category), category.id());
        }
    }

    /** The texts say what a check catches, never a number it decides by; a cheat could tune to it. */
    @Test
    void everyTextIsFilledInShortAndHasNoNumbers() {
        List<String> failures = new ArrayList<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            CheckTexts.Text text = TEXTS.text(entry.name());
            if (text == null) continue;
            if (text.summary().isBlank() || text.details().isBlank()) failures.add(entry.name() + " is blank");
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
