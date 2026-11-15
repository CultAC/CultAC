package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class ConfigPatcherTest {
    @TempDir
    Path directory;

    @Test
    void aMissingKeyTakesItsSiblingsIndent() throws Exception {
        Map<String, Object> patched = insert("alerts:\n    send: false\n", "alerts.default-scope", "global");
        assertEquals(Map.of("send", false, "default-scope", "global"), patched.get("alerts"));
    }

    @Test
    void aDottedTopLevelKeyIsNotTakenForAChild() throws Exception {
        Map<String, Object> patched = insert("a:\na.b: old\n", "a.c", "new");
        assertEquals(Map.of("c", "new"), patched.get("a"));
        assertEquals("old", patched.get("a.b"));
    }

    @Test
    void aReplacedValueKeepsItsLineBreaksTabsQuotesAndBackslashes() throws Exception {
        String value = "a\nb\r\tc \"d\" \\e";
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "format: \"old\"\nother: kept\n");
        ConfigPatcher patcher = new ConfigPatcher(file.toFile());
        patcher.applyChange(new ConfigPatcher.PendingChange(patcher.getNodePosition(java.util.List.of("format")), value));
        patcher.save();
        Map<String, Object> patched = new Yaml().load(Files.readString(file));
        assertEquals(value, patched.get("format"));
        assertEquals("kept", patched.get("other"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReplacedListKeepsTheCommentsAfterIt() throws Exception {
        String yaml = """
                group:
                    checks:
                      - "Simulation"
                      - "NoFall"
                    # Threshold:Interval Command
                    #
                    commands:
                      - "1:1 [log]"
                """;
        String patched =
                replaceList(yaml, List.of("group", "checks"), List.of("Simulation", "NoFall", "Phase"));
        assertTrue(patched.contains("    # Threshold:Interval Command\n    #\n    commands:"), patched);
        Map<String, Object> group = (Map<String, Object>)
                new Yaml().<Map<String, Object>>load(patched).get("group");
        assertEquals(List.of("Simulation", "NoFall", "Phase"), group.get("checks"));
        assertEquals(List.of("1:1 [log]"), group.get("commands"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aListAtItsKeysIndentIsReplaced() throws Exception {
        String yaml = "group:\n  checks:\n  - Simulation\n  - NoFall\n  commands:\n  - \"1:1 [log]\"\n";
        String patched = replaceList(yaml, List.of("group", "checks"), List.of("Simulation", "Phase"));
        Map<String, Object> group = (Map<String, Object>)
                new Yaml().<Map<String, Object>>load(patched).get("group");
        assertEquals(List.of("Simulation", "Phase"), group.get("checks"));
        assertEquals(List.of("1:1 [log]"), group.get("commands"));
    }

    private String replaceList(String yaml, List<String> path, List<String> value) throws Exception {
        Path file = directory.resolve("punishments.yml");
        Files.writeString(file, yaml);
        ConfigPatcher patcher = new ConfigPatcher(file.toFile());
        patcher.applyChange(new ConfigPatcher.PendingChange(patcher.getNodePosition(path), value));
        patcher.save();
        return Files.readString(file);
    }

    private Map<String, Object> insert(String yaml, String path, Object value) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, yaml);
        ConfigPatcher patcher = new ConfigPatcher(file.toFile());
        patcher.addMissingValue(java.util.List.of(path.split("\\.")), value);
        patcher.save();
        return new Yaml().load(Files.readString(file));
    }
}
