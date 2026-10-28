package ac.cult.cultac.manager.config.update;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CultConfigSpecsTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"MYSQL, mysql, 3307", "POSTGRESQL, postgres, 5440"})
    void legacyRemoteDatabaseCredentialsFollowTheirMigratedRoutes(String legacyType, String backendId, int port)
            throws IOException {
        Path main = directory.resolve("config.yml");
        String original = """
                config-flavor: V2
                config-version: 9
                history:
                  server-name: legacy-lobby
                  entries-per-page: 23
                  database:
                    type: %s
                    host: db.ops.example
                    port: %d
                    database: old_history
                    username: audit_user
                    password: 's3cret:# with spaces'
                """.formatted(legacyType, port);
        Files.writeString(main, original);
        Path database = copyDefault("/database/en.yml", directory.resolve("database.yml"));
        Path backend = copyDefault("/databases/" + backendId + "/en.yml",
                directory.resolve("databases/" + backendId + ".yml"));

        Map<File, ConfigUpdater.Spec> batch = new LinkedHashMap<>();
        batch.put(main.toFile(), CultConfigSpecs.mainConfig());
        batch.put(database.toFile(), CultConfigSpecs.database());
        batch.put(backend.toFile(), CultConfigSpecs.backend(backendId));
        ConfigUpdater updater = new ConfigUpdater(CultConfigSpecs.class, Logger.getLogger("cult-config-test"));
        updater.updateAll(batch, "en");

        Map<String, Object> databaseSettings = section(read(database), "database");
        Map<String, Object> routes = section(databaseSettings, "routing");
        for (String category : List.of("violation", "session", "player-identity", "setting")) {
            assertEquals(backendId, routes.get(category), category);
        }
        Map<String, Object> connection = section(read(backend), backendId);
        assertAll(
                () -> assertEquals("none", routes.get("blob")),
                () -> assertEquals("legacy-lobby", databaseSettings.get("server-name")),
                () -> assertEquals(23, section(databaseSettings, "history").get("entries-per-page")),
                () -> assertEquals("db.ops.example", connection.get("host")),
                () -> assertEquals(port, connection.get("port")),
                () -> assertEquals("old_history", connection.get("database")),
                () -> assertEquals("audit_user", connection.get("user")),
                () -> assertEquals("s3cret:# with spaces", connection.get("password")),
                () -> assertEquals(original, Files.readString(directory.resolve("config.yml.v9.bak")))
        );

        Map<Path, String> migrated = new LinkedHashMap<>();
        for (Path file : List.of(main, database, backend)) migrated.put(file, Files.readString(file));
        for (ConfigUpdater.Result result : updater.updateAll(batch, "en").values()) {
            assertFalse(result.migrated, "current configurations must not migrate again");
        }
        for (Map.Entry<Path, String> snapshot : migrated.entrySet()) {
            assertEquals(snapshot.getValue(), Files.readString(snapshot.getKey()), snapshot.getKey().toString());
        }
    }

    private static Path copyDefault(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream stream = Objects.requireNonNull(CultConfigSpecsTest.class.getResourceAsStream(resource))) {
            Files.copy(stream, target);
        }
        return target;
    }

    private static Map<String, Object> read(Path file) throws IOException {
        return new Yaml().load(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> settings, String key) {
        return (Map<String, Object>) settings.get(key);
    }
}
