package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The config hash a socket announces: it follows the settings and never the secrets. */
class ConfigHashTest {
    @TempDir
    Path folder;

    private String hash(String bridgeSecret, String apiKey, String threshold) throws IOException {
        Files.writeString(
                folder.resolve("config.yml"),
                "bridge:\n  secret: " + bridgeSecret + "\n"
                        + "cloud:\n  url: \"https://intake.example\"\n  api-key: " + apiKey + "\n"
                        + "  proxy-id: \"edge-1\"\n");
        Files.writeString(folder.resolve("cult.yml"), "Reach:\n  threshold: " + threshold + "\n");
        Files.writeString(
                folder.resolve("punishments.yml"), "Punishments:\n  Reach:\n    remove-violations-after: 300\n");
        return CloudConnection.configHash(folder.toFile(), "en");
    }

    @Test
    void changingOnlyASecretLeavesTheHashAlone() throws IOException {
        String base = hash("\"\"", "\"\"", "0.0005");
        assertNotNull(base);
        assertEquals(base, hash("\"123456\"", "\"\"", "0.0005"));
        assertEquals(base, hash("123457", "\"\"", "0.0005"));
        assertEquals(base, hash("\"" + "s".repeat(64) + "\"", "\"issued-token-0123456789\"", "0.0005"));
        assertEquals(base, hash("\"\"", "'" + "k".repeat(200) + "'", "0.0005"));
    }

    @Test
    void theBundledDefaultsHash() throws IOException {
        Path empty = Files.createDirectory(folder.resolve("empty"));
        for (String locale : List.of("de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "zh")) {
            assertNotNull(CloudConnection.configHash(empty.toFile(), locale), locale);
        }
    }

    @Test
    void changingASettingChangesTheHash() throws IOException {
        String base = hash("\"123456\"", "\"key\"", "0.0005");
        assertNotEquals(base, hash("\"123456\"", "\"key\"", "0.0006"));
    }
}
