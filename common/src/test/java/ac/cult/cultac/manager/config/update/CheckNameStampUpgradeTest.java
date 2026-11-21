package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.CheckNames;
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

/** Only the operator moves a file's check name version; see {@link CheckNames}. */
class CheckNameStampUpgradeTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "uk", "zh"})
    void aNewConfigIsWrittenInTheNewestNames(String language) throws Exception {
        assertEquals(CheckNames.LATEST, bundled("/config/" + language + ".yml").get(CheckNames.CONFIG_KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr", "uk", "zh"})
    @SuppressWarnings("unchecked")
    void newPunishmentsAreWrittenInTheNewestNames(String language) throws Exception {
        Map<String, Object> data = bundled("/punishments/" + language + ".yml");
        assertEquals(CheckNames.LATEST, data.get(CheckNames.PUNISHMENTS_KEY));
        Map<String, Object> groups = (Map<String, Object>) data.get("Punishments");
        Map<String, Object> combat = (Map<String, Object>) groups.get("Combat");
        List<String> checks = (List<String>) combat.get("checks");
        assertTrue(checks.contains("AttackEntityVisibility"), checks.toString());
        assertFalse(checks.contains("AttackMobVisibility"), checks.toString());
    }

    @Test
    void aConfigReplacedByTodaysDefaultsKeepsTheNamesItWasWrittenIn() throws Exception {
        assertEquals(CheckNames.FIRST, upgrade("").get(CheckNames.CONFIG_KEY));
        assertEquals(1, upgrade(CheckNames.CONFIG_KEY + ": 1\n").get(CheckNames.CONFIG_KEY));
        assertEquals(2, upgrade(CheckNames.CONFIG_KEY + ": 2\n").get(CheckNames.CONFIG_KEY));
    }

    @Test
    void punishmentsWithoutAStampAreNotGivenOne() throws Exception {
        Path file = directory.resolve("punishments.yml");
        Files.writeString(
                file,
                "Punishments:\n  Combat:\n    remove-violations-after: 300\n    checks:\n      - \"Interact\"\n"
                        + "      - \"AttackMobVisibility\"\n    commands:\n      - \"20:40 [alert]\"\n");
        new ConfigUpdater(CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.cultConfig("/punishments/"));
        String upgraded = Files.readString(file);
        assertFalse(upgraded.contains(CheckNames.PUNISHMENTS_KEY), upgraded);
        assertFalse(upgraded.contains("AttackEntityVisibility"), upgraded);
        assertTrue(upgraded.contains("AttackMobVisibility"), upgraded);
    }

    private Map<String, Object> upgrade(String content) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, content + "config-flavor: V2\nconfig-version: 19\n");
        assertTrue(new ConfigUpdater(CultConfigSpecs.class, Logger.getLogger(getClass().getName()))
                .update(file.toFile(), CultConfigSpecs.mainConfig())
                .migrated);
        return new Yaml().load(Files.readString(file));
    }

    private static Map<String, Object> bundled(String resource) throws Exception {
        try (var source = CultConfigSpecs.class.getResourceAsStream(resource)) {
            assertNotNull(source, resource);
            return new Yaml().load(source);
        }
    }
}
