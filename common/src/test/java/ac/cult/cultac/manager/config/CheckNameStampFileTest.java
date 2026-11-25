package ac.cult.cultac.manager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckPolicy;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bundled defaults carry the newest check name version; an operator's older file must not read as stamped so. */
class CheckNameStampFileTest {
    private static final String WRITTEN_BEFORE_THE_STAMP = "Punishments:\n"
            + "  Combat:\n"
            + "    remove-violations-after: 300\n"
            + "    checks:\n"
            + "      - \"AttackMobVisibility\"\n"
            + "      - \"InteractEntityVisibility\"\n"
            + "    commands:\n"
            + "      - \"20:40 [alert]\"\n"
            + "\n"
            + "cult-config-version: 6\n";

    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void punishmentsWrittenBeforeTheStampKeepTheirNamesAndTheirFile() throws Exception {
        Path punishments = directory.resolve("punishments.yml");
        Files.writeString(punishments, WRITTEN_BEFORE_THE_STAMP);
        ConfigManagerFileImpl config = load();

        assertNull(config.written(CheckNames.PUNISHMENTS_KEY));
        assertEquals(CheckNames.FIRST, CheckNames.version(config, CheckNames.PUNISHMENTS_KEY));
        // config.yml did not exist, so it is today's default.
        assertEquals(CheckNames.LATEST, CheckNames.version(config, CheckNames.CONFIG_KEY));
        CheckNames.validate(config);
        assertEquals(WRITTEN_BEFORE_THE_STAMP, Files.readString(punishments));

        CheckPolicy policy = CheckPolicy.of(config);
        for (String check : List.of("AttackEntityVisibility", "InteractPlayerVisibility", "InteractEntityVisibility")) {
            assertFalse(policy.status(CheckCatalog.find(check)).groups().isEmpty(), check);
        }
    }

    @Test
    void theOperatorsStampMovesTheNamesAndOneThisBuildCannotReadIsRefused() throws Exception {
        Path punishments = directory.resolve("punishments.yml");
        Files.writeString(punishments, WRITTEN_BEFORE_THE_STAMP + CheckNames.PUNISHMENTS_KEY + ": 2\n");
        ConfigManagerFileImpl config = load();
        assertEquals(2, CheckNames.version(config, CheckNames.PUNISHMENTS_KEY));
        CheckPolicy policy = CheckPolicy.of(config);
        assertTrue(policy.status(CheckCatalog.find("AttackEntityVisibility")).groups().isEmpty());
        assertTrue(policy.status(CheckCatalog.find("InteractPlayerVisibility")).groups().isEmpty());
        assertFalse(policy.status(CheckCatalog.find("InteractEntityVisibility")).groups().isEmpty());

        Files.writeString(
                punishments,
                WRITTEN_BEFORE_THE_STAMP + CheckNames.PUNISHMENTS_KEY + ": " + (CheckNames.LATEST + 1) + "\n");
        config.reload();
        assertThrows(IllegalStateException.class, () -> CheckNames.validate(config));
        assertThrows(IllegalStateException.class, () -> new BaseConfigManager().load(config));
    }

    private ConfigManagerFileImpl load() {
        File folder = directory.toFile();
        ConfigManagerFileImpl config = new ConfigManagerFileImpl(null) {
            @Override
            File getDataFolder() {
                return folder;
            }
        };
        config.reload();
        return config;
    }
}
