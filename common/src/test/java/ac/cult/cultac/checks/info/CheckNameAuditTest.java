package ac.cult.cultac.checks.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.checks.StampedConfig;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckNameAuditTest {
    @Test
    void namesOfTheFilesOwnVersionRaiseNothing() {
        assertEquals(
                List.of(),
                CheckNameAudit.warnings(config(
                        1,
                        1,
                        List.of("Interact", "AttackMobVisibility", "!bAttackMob", "Baritone"),
                        "AttackMobVisibility")));
        assertEquals(
                List.of(),
                CheckNameAudit.warnings(config(
                        2,
                        2,
                        List.of("InteractPlayerVisibility", "AttackEntityVisibility", "Baritone"),
                        "AttackEntityVisibility")));
    }

    @Test
    void aSelectorOfAnotherVersionIsNamedWithTheCheckItMisses() {
        List<String> newer = CheckNameAudit.warnings(config(1, 1, List.of("AttackEntityVisibility"), null));
        assertEquals(1, newer.size(), newer.toString());
        assertTrue(
                newer.get(0).startsWith("punishments.yml: \"attackentityvisibility\" selects no check."),
                newer.get(0));
        assertTrue(newer.get(0).endsWith("where that check is AttackMobVisibility"), newer.get(0));

        List<String> older = CheckNameAudit.warnings(config(2, 2, List.of("!AttackMobVisibility"), null));
        assertEquals(1, older.size(), older.toString());
        assertTrue(older.get(0).endsWith("where that check is AttackEntityVisibility"), older.get(0));

        // The half split off in version 2 has no name of its own in version 1.
        List<String> split = CheckNameAudit.warnings(config(1, 1, List.of("InteractPlayerVisibility"), null));
        assertEquals(1, split.size(), split.toString());
        assertTrue(split.get(0).endsWith("where that check is InteractEntityVisibility"), split.get(0));
    }

    @Test
    void aSelectorOfACategoryNamesEveryCheckItSelectsOrNoLongerSelects() {
        List<String> newer = CheckNameAudit.warnings(config(2, 2, List.of("Inventory"), null));
        assertEquals(1, newer.size(), newer.toString());
        assertTrue(
                newer.get(0)
                        .endsWith("where those checks are MultiActionsC, MultiActionsD, MultiActionsH, MultiActionsI,"
                                + " MultiActionsJ, MultiActionsK, MultiActionsL, MultiActionsM, MultiActionsN,"
                                + " MultiActionsO, MultiActionsP, MultiActionsQ, MultiActionsR, MultiActionsS,"
                                + " MultiActionsT, MultiActionsU"),
                newer.get(0));

        // Version 3 moves these out of MultiActions, so a file of it selects them with "Inventory".
        assertEquals(
                List.of("punishments.yml: \"multiactions\" selects InventoryA, InventoryB, InventoryC, InventoryD,"
                        + " InventoryE, InventoryF, InventoryG, InventoryH, InventoryI, InventoryJ, InventoryK,"
                        + " InventoryL as name version 2 names them, and punishment-check-name-version is 3, where no"
                        + " group selects them"),
                CheckNameAudit.warnings(config(3, 3, List.of("MultiActions"), null)));
        assertEquals(List.of(), CheckNameAudit.warnings(config(3, 3, List.of("MultiActions", "Inventory"), null)));
        assertEquals(List.of(), CheckNameAudit.warnings(config(3, 3, List.of("MultiActions", "!Inventory"), null)));
    }

    @Test
    void settingsUnderANameOfAnotherVersionAreNamed() {
        List<String> newer = CheckNameAudit.warnings(config(1, 1, List.of(), "AttackEntityVisibility"));
        assertEquals(1, newer.size(), newer.toString());
        assertTrue(newer.get(0).startsWith("config.yml: the settings under AttackEntityVisibility are not read."));
        assertTrue(newer.get(0).endsWith("where that check is AttackMobVisibility"), newer.get(0));

        List<String> older = CheckNameAudit.warnings(config(2, 2, List.of(), "bAttackMobVisibility"));
        assertEquals(1, older.size(), older.toString());
        assertTrue(older.get(0).endsWith("where that check is bAttackEntityVisibility"), older.get(0));

        // Version 3 sets five checks under InventoryD.
        List<String> merged = CheckNameAudit.warnings(config(2, 2, List.of("InventoryD"), "InventoryD"));
        assertEquals(2, merged.size(), merged.toString());
        for (String warning : merged) {
            assertTrue(
                    warning.endsWith("where those checks are MultiActionsI, MultiActionsJ, MultiActionsK,"
                            + " MultiActionsL, MultiActionsS"),
                    warning);
        }
    }

    @Test
    void eachFileIsJudgedByItsOwnStamp() {
        assertEquals(
                List.of(),
                CheckNameAudit.warnings(config(1, 2, List.of("AttackEntityVisibility"), "AttackMobVisibility")));
        assertEquals(
                2,
                CheckNameAudit.warnings(config(2, 1, List.of("AttackEntityVisibility"), "AttackMobVisibility"))
                        .size());
    }

    private static ConfigManager config(
            int configVersion, int punishmentsVersion, List<String> selectors, String settingsKey) {
        Map<String, Object> values = new HashMap<>();
        values.put(CheckNames.CONFIG_KEY, configVersion);
        values.put(CheckNames.PUNISHMENTS_KEY, punishmentsVersion);
        values.put(
                "Punishments",
                List.of(new LinkedHashMap<>(Map.of("checks", selectors, "commands", List.of("5:5 [alert]")))));
        if (settingsKey != null) values.put(settingsKey, Map.of("setbackvl", 3));
        return StampedConfig.of(values);
    }
}
