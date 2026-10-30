package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PunishmentRulesTest {
    @Test
    void readsEachGroupsSelectorsCommandsAndExpiry() {
        PunishmentRules rules = PunishmentRules.parse(List.of(
                group(List.of("Alpha", "!AlphaB"), List.of("2:3 [alert]", "4:0 say %player%"), 60),
                group(List.of("Beta"), List.of(), null)));

        assertNull(rules.failure());
        assertEquals(List.of(), rules.unreadGroupSelectors());
        PunishmentRules.Group first = rules.groups().getFirst();
        assertEquals(List.of("alpha", "!alphab"), first.selectors());
        assertEquals(
                List.of(
                        new PunishmentRules.Command(2, 3, "[alert]"),
                        new PunishmentRules.Command(4, 0, "say %player%")),
                first.commands());
        assertEquals(60, first.removeViolationsAfter());
        assertEquals(300, rules.groups().get(1).removeViolationsAfter());
    }

    @Test
    void matchesByNameOrAlternativeNameMinusExclusions() {
        List<String> selectors = List.of("alpha", "!alphab");
        assertTrue(PunishmentRules.holds(selectors, "alphaa", null));
        assertFalse(PunishmentRules.holds(selectors, "alphab", null));
        assertTrue(PunishmentRules.holds(List.of("gamma"), "other", "gammaa"));
        assertFalse(PunishmentRules.holds(selectors, null, "alpha"));
    }

    @Test
    void anExclusionRemovesOneMatchAfterEachSelector() {
        assertFalse(PunishmentRules.holds(List.of("!alpha", "alpha"), "alpha", null));
        assertFalse(PunishmentRules.holds(List.of("!alpha", "alpha", "alph"), "alpha", null));
        assertTrue(PunishmentRules.holds(List.of("alpha", "alph", "!alpha"), "alpha", null));
    }

    @Test
    void keepsTheGroupsBeforeAnUnreadableOne() {
        PunishmentRules rules = PunishmentRules.parse(List.of(
                group(List.of("Alpha"), List.of("1:1 [log]"), null),
                group(List.of("Beta"), List.of("not a command"), null),
                group(List.of("Gamma"), List.of("1:1 [log]"), null)));

        assertNotNull(rules.failure());
        assertEquals(1, rules.groups().size());
        assertEquals(List.of("beta"), rules.unreadGroupSelectors());
    }

    private static LinkedHashMap<String, Object> group(List<String> checks, List<String> commands, Integer expiry) {
        LinkedHashMap<String, Object> group = new LinkedHashMap<>(Map.of("checks", checks, "commands", commands));
        if (expiry != null) group.put("remove-violations-after", expiry);
        return group;
    }
}
