package ac.cult.cultac.manager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class LegacyCultFileTest {
    private static final String CONFIG = "alerts:\n  print-to-console: false\nconfig-flavor: V2\nconfig-version: 21\n";
    private static final String LEGACY = "cult:\n  exempt-java-players: true\nconfig-version: 3\n";

    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void aFoldThatFailsLeavesTheUpdatesRunningAndCultYmlRead() throws Exception {
        ByteArrayOutputStream config = new ByteArrayOutputStream();
        config.writeBytes("# ÿ".getBytes(StandardCharsets.ISO_8859_1));
        config.writeBytes(("\n" + CONFIG).getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("config.yml"), config.toByteArray());
        Files.writeString(directory.resolve("cult.yml"), LEGACY);
        Files.writeString(directory.resolve("discord.yml"), "config-flavor: V2\nconfig-version: 4\n");

        File folder = directory.toFile();
        ConfigManagerFileImpl manager = new ConfigManagerFileImpl(null) {
            @Override
            File getDataFolder() {
                return folder;
            }
        };
        manager.reload();

        assertEquals(LEGACY, Files.readString(directory.resolve("cult.yml")));
        assertTrue(manager.getBooleanElse("cult.exempt-java-players", false));
        Map<String, Object> discord = new Yaml().load(Files.readString(directory.resolve("discord.yml")));
        assertEquals(5, discord.get("config-version"));
    }

    @Test
    void theReloadHostFoldsIntoItsSnapshotAndKeepsTheCanonicalCultYml() throws Exception {
        Path canonical = Files.createDirectories(directory.resolve("canonical"));
        Files.writeString(canonical.resolve("config.yml"), CONFIG);
        Files.writeString(canonical.resolve("cult.yml"), LEGACY);

        ConfigManagerFileImpl first = new ConfigManagerFileImpl(directory.resolve("first").toFile(), canonical);
        first.reload();
        assertTrue(first.getBooleanElse("cult.exempt-java-players", false));
        assertTrue(Files.exists(canonical.resolve("config.yml.pre-cult-merge.bak")));
        for (var file : first.publication().entrySet()) {
            Path target = canonical.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }

        ConfigManagerFileImpl second = new ConfigManagerFileImpl(directory.resolve("second").toFile(), canonical);
        second.reload();
        assertTrue(second.getBooleanElse("cult.exempt-java-players", false));
        assertFalse(Files.exists(directory.resolve("second/cult.yml")));
        assertEquals(LEGACY, Files.readString(canonical.resolve("cult.yml")));
    }
}
