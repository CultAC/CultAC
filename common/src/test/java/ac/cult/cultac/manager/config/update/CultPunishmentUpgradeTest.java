package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

class CultPunishmentUpgradeTest {
    @TempDir
    Path directory;

    private static final String ORIGINAL = """
            # Operator comment
            cult-config-version: 1
            custom-setting: keep-me
            Punishments:
              Knockback:
                remove-violations-after: 77
                checks: [Knockback, Explosion]
                commands: ['17:4 custom-knockback %player%']
              Combat:
                checks: [Interact, Aim]
                commands: ['9:2 custom-combat %player%']
              Misc:
                checks: [NoSlow, Sprint, Place, Break]
                commands: ['3:1 custom-misc %player%']
            """;

    @Test
    void addsAllMissingEntriesAndPreservesOperatorSettingsAndBackup() throws Exception {
        Path file = write(ORIGINAL);
        var result = upgrade(file);
        var data = read(file);
        assertTrue(result.migrated);
        assertEquals(1, result.oldVersion);
        assertEquals(5, result.newVersion);
        assertEquals(5, data.get("cult-config-version"));
        assertEquals(List.of("Knockback", "Explosion", "KeepSprint"), checks(data, "Knockback"));
        assertEquals(
                List.of("Interact", "Aim", "AttackPlayerVisibility", "AttackMobVisibility"), checks(data, "Combat"));
        assertEquals(List.of("NoSlow", "NoSneakSlow", "Sprint", "Place", "Break", "SkinBlinker"), checks(data, "Misc"));
        assertEquals(List.of("Cloud"), checks(data, "Cloud"));
        assertEquals(1800, group(data, "Cloud").get("remove-violations-after"));
        assertEquals(77, group(data, "Knockback").get("remove-violations-after"));
        assertEquals(
                List.of("17:4 custom-knockback %player%"),
                group(data, "Knockback").get("commands"));
        assertEquals(
                List.of("9:2 custom-combat %player%"), group(data, "Combat").get("commands"));
        assertEquals("keep-me", data.get("custom-setting"));
        assertTrue(Files.readString(file).startsWith("# Operator comment\n"));
        assertEquals(ORIGINAL, Files.readString(directory.resolve("punishments.yml.cult-v1.bak")));
    }

    @Test
    void runsOnlyOnceAndDoesNotDuplicateManuallyAddedEntries() throws Exception {
        Path file = write(ORIGINAL.replace("[Interact, Aim]", "[Interact, Aim, AttackMobVisibility]"));
        upgrade(file);
        String migrated = Files.readString(file);
        assertFalse(upgrade(file).migrated);
        assertEquals(migrated, Files.readString(file));
        assertEquals(
                List.of("Interact", "Aim", "AttackMobVisibility", "AttackPlayerVisibility"),
                checks(read(file), "Combat"));
        assertFalse(Files.exists(directory.resolve("punishments.yml.cult-v2.bak")));
    }

    @Test
    void respectsExplicitExclusionsAcrossGroupsIncludingBroadSelectors() throws Exception {
        Path file = write(ORIGINAL.replace(
                "[NoSlow, Sprint, Place, Break]", "[NoSlow, Sprint, Place, Break, '!AttackMob', '!KeepSprint']"));
        upgrade(file);
        var data = read(file);
        assertEquals(List.of("Knockback", "Explosion"), checks(data, "Knockback"));
        assertEquals(List.of("Interact", "Aim", "AttackPlayerVisibility"), checks(data, "Combat"));
        assertEquals(
                List.of(
                        "NoSlow",
                        "NoSneakSlow",
                        "Sprint",
                        "Place",
                        "Break",
                        "!AttackMob",
                        "!KeepSprint",
                        "SkinBlinker"),
                checks(data, "Misc"));
    }

    @Test
    void existingBroadSelectorsCoverNewChecksWithoutExtraEntries() throws Exception {
        Path file = write(ORIGINAL.replace("[Interact, Aim]", "[Interact, Aim, aTtAcK]")
                .replace("[Knockback, Explosion]", "[Knockback, Explosion, sPrInT]"));
        upgrade(file);
        assertEquals(List.of("Interact", "Aim", "aTtAcK"), checks(read(file), "Combat"));
        assertEquals(List.of("Knockback", "Explosion", "sPrInT"), checks(read(file), "Knockback"));
    }

    @Test
    void extendsRenamedGroupsWithTheirExistingCommands() throws Exception {
        Path file = write(ORIGINAL.replace("  Knockback:", "  VelocityRules:").replace("  Combat:", "  FightRules:"));
        upgrade(file);
        var data = read(file);
        assertEquals(List.of("Knockback", "Explosion", "KeepSprint"), checks(data, "VelocityRules"));
        assertEquals(
                List.of("Interact", "Aim", "AttackPlayerVisibility", "AttackMobVisibility"),
                checks(data, "FightRules"));
        assertEquals(
                List.of("9:2 custom-combat %player%"), group(data, "FightRules").get("commands"));
        assertNull(group(data, "Combat"));
        assertNull(group(data, "Knockback"));
    }

    @Test
    void createsMissingGroupsWithDefaultPunishmentCommands() throws Exception {
        Path file = write(
                "cult-config-version: 1\nPunishments:\n  Custom:\n    checks: [Reach]\n    commands: ['1:1 custom']\n");
        upgrade(file);
        var data = read(file);
        assertEquals(List.of("KeepSprint"), checks(data, "Knockback"));
        assertEquals(List.of("AttackPlayerVisibility", "AttackMobVisibility"), checks(data, "Combat"));
        assertEquals(
                List.of("20:40 [alert]", "1:1 [log]"), group(data, "Combat").get("commands"));
        assertEquals(List.of("1:1 custom"), group(data, "Custom").get("commands"));
    }

    @Test
    void legacyBedrockUpgradeAndNewEntriesStackInOneUpdate() throws Exception {
        Path file = write(ORIGINAL.replace("cult-config-version: 1", "cult-config-version: 0")
                + "  Simulation:\n    checks: [Simulation, Timer]\n    commands: ['1:1 [log]']\n");
        upgrade(file);
        var data = read(file);
        assertEquals(List.of("Simulation", "Timer", "BedrockMovement"), checks(data, "Simulation"));
        assertEquals(List.of("Knockback", "Explosion", "KeepSprint"), checks(data, "Knockback"));
        assertEquals(
                List.of("Interact", "Aim", "AttackPlayerVisibility", "AttackMobVisibility"), checks(data, "Combat"));
    }

    private static final String REVISION_2 = """
            cult-config-version: 2
            Punishments:
              Misc:
                remove-violations-after: 300
                checks:
                  - "Vehicle"
                  - "NoSlow"
                  - "Sprint"
                commands:
                  - "10:5 [alert]"
            """;

    @Test
    void insertsNoSneakSlowDirectlyBelowNoSlow() throws Exception {
        Path file = write(REVISION_2);
        var result = upgrade(file);
        assertTrue(result.migrated);
        assertEquals(2, result.oldVersion);
        assertEquals(5, result.newVersion);
        assertEquals(List.of("Vehicle", "NoSlow", "NoSneakSlow", "Sprint", "SkinBlinker"), checks(read(file), "Misc"));
    }

    @Test
    void doesNotDuplicateOrOverrideAnExistingNoSneakSlowEntry() throws Exception {
        Path present = write(REVISION_2.replace("      - \"Sprint\"", "      - \"Sprint\"\n      - \"NoSneakSlow\""));
        upgrade(present);
        assertEquals(
                List.of("Vehicle", "NoSlow", "Sprint", "NoSneakSlow", "SkinBlinker"), checks(read(present), "Misc"));

        Path excluded = write(REVISION_2.replace("      - \"Sprint\"", "      - \"Sprint\"\n      - \"!NoSneakSlow\""));
        upgrade(excluded);
        assertEquals(
                List.of("Vehicle", "NoSlow", "Sprint", "!NoSneakSlow", "SkinBlinker"), checks(read(excluded), "Misc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "zh"})
    void bundledLanguagesAreAlreadyAtTheNewCultRevision(String language) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream("/punishments/" + language + ".yml")) {
            assertNotNull(source);
            Map<String, Object> data = new Yaml().load(source);
            assertEquals(5, data.get("cult-config-version"));
            assertTrue(checks(data, "Knockback").contains("KeepSprint"));
            assertTrue(checks(data, "Combat").containsAll(List.of("AttackPlayerVisibility", "AttackMobVisibility")));
            List<?> misc = checks(data, "Misc");
            assertEquals("NoSneakSlow", misc.get(misc.indexOf("NoSlow") + 1));
            assertEquals("SkinBlinker", misc.get(misc.indexOf("Exploit") + 1));
            assertTrue(misc.contains("!Cloud"));
            assertEquals(List.of("Cloud"), checks(data, "Cloud"));
        }
    }

    private static final String REVISION_3 = """
            cult-config-version: 3
            Punishments:
              Misc:
                checks:
                  - "Chat"
                  - "Exploit"
                  - "Vehicle"
                commands:
                  - "10:5 [alert]"
            """;

    @Test
    void insertsSkinBlinkerBelowExploitAndAddsTheCloudGroup() throws Exception {
        Path file = write(REVISION_3);
        var result = upgrade(file);
        assertTrue(result.migrated);
        assertEquals(3, result.oldVersion);
        var data = read(file);
        assertEquals(List.of("Chat", "Exploit", "SkinBlinker", "Vehicle"), checks(data, "Misc"));
        assertEquals(List.of("Cloud"), checks(data, "Cloud"));
        assertEquals(
                List.of("1:1 [alert]", "1:1 [log]", "3:3 [webhook]", "3:3 [proxy]"),
                group(data, "Cloud").get("commands"));
        assertEquals(1800, group(data, "Cloud").get("remove-violations-after"));
    }

    @Test
    void keepsAnOperatorsCloudGroupAsItIs() throws Exception {
        Path file = write(
                REVISION_3
                        + "  Bans:\n    remove-violations-after: 600\n    checks: [Cloud]\n    commands: ['2:0 ban %player%']\n");
        upgrade(file);
        var data = read(file);
        assertNull(group(data, "Cloud"));
        assertEquals(List.of("Cloud"), checks(data, "Bans"));
        assertEquals(600, group(data, "Bans").get("remove-violations-after"));
        assertEquals(List.of("2:0 ban %player%"), group(data, "Bans").get("commands"));
    }

    private static final String REVISION_4 = """
            cult-config-version: 4
            Punishments:
              Misc:
                remove-violations-after: 300
                checks:
                  - "Vehicle"
                  - "NoSlow"
                  - "NoSneakSlow"
                  - "Sprint"
                  - "MultiActions"
                  - "Place"
                  - "Baritone"
                  - "Break"
                  - "TransactionOrder"
                  - "Elytra"
                  - "Chat"
                  - "Exploit"
                  - "SkinBlinker"
                commands:
                  - "10:5 [alert]"
                  - "1:1 [log]"
                  - "20:10 [webhook]"
                  - "20:10 [proxy]"
              Cloud:
                remove-violations-after: 1800
                checks:
                  - "Cloud"
                commands:
                  - "1:1 [alert]"
            """;

    @Test
    void excludesTheCloudChecksFromAnUnmodifiedDefaultMisc() throws Exception {
        Path file = write(REVISION_4);
        var result = upgrade(file);
        assertTrue(result.migrated);
        assertEquals(4, result.oldVersion);
        var data = read(file);
        List<?> misc = checks(data, "Misc");
        assertEquals("!Cloud", misc.get(misc.size() - 1));
        assertEquals(14, misc.size());
        assertEquals(List.of("Cloud"), checks(data, "Cloud"));
    }

    @Test
    void leavesAnEditedMiscAsItIs() throws Exception {
        Path selectors = write(REVISION_4.replace("      - \"Chat\"\n", ""));
        upgrade(selectors);
        assertFalse(checks(read(selectors), "Misc").contains("!Cloud"));

        Path commands = write(REVISION_4.replace("\"20:10 [proxy]\"", "\"20:10 kick %player%\""));
        upgrade(commands);
        assertFalse(checks(read(commands), "Misc").contains("!Cloud"));
    }

    private Path write(String content) throws Exception {
        Path file = directory.resolve("punishments.yml");
        Files.writeString(file, content);
        return file;
    }

    private ConfigUpdater.Result upgrade(Path file) throws Exception {
        return new ConfigUpdater(
                        CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.cultConfig("/punishments/"));
    }

    private Map<String, Object> read(Path file) throws Exception {
        return new Yaml().load(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> group(Map<String, Object> data, String name) {
        return (Map<String, Object>) ((Map<?, ?>) data.get("Punishments")).get(name);
    }

    private static List<?> checks(Map<String, Object> data, String name) {
        return (List<?>) group(data, name).get("checks");
    }
}
