package ac.cult.cultac.manager.config.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
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
        patcher.applyChange(new ConfigPatcher.PendingChange(patcher.getNodePosition("format"), value));
        patcher.save();
        Map<String, Object> patched = new Yaml().load(Files.readString(file));
        assertEquals(value, patched.get("format"));
        assertEquals("kept", patched.get("other"));
    }

    private Map<String, Object> insert(String yaml, String path, Object value) throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, yaml);
        ConfigPatcher patcher = new ConfigPatcher(file.toFile());
        patcher.addMissingValue(path, value);
        patcher.save();
        return new Yaml().load(Files.readString(file));
    }
}
