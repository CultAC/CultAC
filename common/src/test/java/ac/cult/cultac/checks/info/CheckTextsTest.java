package ac.cult.cultac.checks.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ac.cult.cultac.checks.CheckNames;
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

    /** A check merged into another keeps its explanation under each older name of its own. */
    @Test
    void theTextsCoverExactlyTheCatalog() {
        Set<String> catalog = new TreeSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            catalog.add(entry.name());
            boolean merged = CheckCatalog.ENTRIES.stream()
                    .anyMatch(other -> other != entry && other.name().equals(entry.name()));
            if (!merged) continue;
            for (int version = CheckNames.FIRST; version < CheckNames.LATEST; version++) {
                catalog.add(entry.name(version));
            }
        }
        assertEquals(catalog, new TreeSet<>(TEXTS.names()));

        CheckCatalog.Entry use = CheckCatalog.find("MultiActionsJ", 2);
        assertEquals("Used an item with an inventory open", TEXTS.text(use, 2).summary());
        assertEquals(TEXTS.text("InventoryD"), TEXTS.text(use, 3));
        assertEquals(TEXTS.text("InventoryA"), TEXTS.text(CheckCatalog.find("MultiActionsC", 2), 2));
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
