package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.command.commands.CultObserve.Outcome;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.yaml.snakeyaml.Yaml;

class CultObserveTest {
    private static final String CONFIG = """
            # Top comment stays.
            prefix: "&bCult &8»"

            # Flag and alert without acting.
            observe-only: false

            Simulation:
                # Nested comment stays.
                setback-decay-multiplier: 0.999
            """;

    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void aBooleanEnvironmentRefusesAndLeavesTheFileAndModeAlone() throws Exception {
        File config = config(CONFIG);
        AtomicInteger reloads = new AtomicInteger();
        for (String pinned : List.of("true", "false", " YES ", "0")) {
            Outcome outcome = CultObserve.switchTo(true, config, env(pinned), () -> {
                        reloads.incrementAndGet();
                        return CompletableFuture.completedFuture(true);
                    })
                    .join();
            assertEquals(Outcome.PINNED, outcome, pinned);
        }
        assertEquals(0, reloads.get());
        assertEquals(CONFIG, Files.readString(config.toPath()));
    }

    @Test
    void eachFlipWritesTheKeyAndSwitchesTheLiveModeThroughTheReload() throws Exception {
        File config = config(CONFIG);
        BaseConfigManager manager = new BaseConfigManager();
        manager.load(fileBacked(config));
        assertFalse(manager.isObserveOnly());
        Supplier<CompletableFuture<Boolean>> reload = () -> {
            manager.load(fileBacked(config));
            return CompletableFuture.completedFuture(true);
        };

        List<String> logged = capture(() -> {
            assertEquals(
                    Outcome.APPLIED,
                    CultObserve.switchTo(true, config, env("config"), reload).join());
            assertTrue(manager.isObserveOnly());
            assertEquals(
                    Outcome.APPLIED,
                    CultObserve.switchTo(false, config, env(null), reload).join());
            assertFalse(manager.isObserveOnly());
        });

        assertTrue(logged.contains("Switched to observe-only; pending setbacks are released"), logged.toString());
        assertTrue(logged.contains("Switched to enforcing; check violations start from zero"), logged.toString());
        assertEquals(CONFIG, Files.readString(config.toPath()).replace("\r\n", "\n"));
    }

    @Test
    void theKeyIsWrittenInPlaceKeepingCommentsAndOtherKeys() throws Exception {
        File config = config(CONFIG);
        CultObserve.write(config, true);
        assertEquals(
                CONFIG.replace("observe-only: false", "observe-only: true"),
                Files.readString(config.toPath()).replace("\r\n", "\n"));
        assertEquals(true, yaml(config).get("observe-only"));
    }

    @Test
    void aMissingKeyIsAddedAtTheTopLevel() throws Exception {
        File config = config("prefix: x\nSimulation:\n    setback-decay-multiplier: 0.999\n");
        CultObserve.write(config, true);
        Map<String, Object> written = yaml(config);
        assertEquals(true, written.get("observe-only"));
        assertEquals("x", written.get("prefix"));
        assertEquals(Map.of("setback-decay-multiplier", 0.999), written.get("Simulation"));
    }

    @Test
    void aFailedReloadRestoresTheOriginalBytes() throws Exception {
        File config = config(CONFIG.replace("\n", "\r\n"));
        byte[] original = Files.readAllBytes(config.toPath());
        List<Supplier<CompletableFuture<Boolean>>> failures = List.of(
                () -> CompletableFuture.completedFuture(false),
                () -> CompletableFuture.failedFuture(new IOException("rejected")),
                () -> {
                    throw new IllegalStateException("no host");
                });
        for (Supplier<CompletableFuture<Boolean>> failure : failures) {
            List<Boolean> written = new ArrayList<>();
            Supplier<CompletableFuture<Boolean>> reload = () -> {
                written.add((Boolean) yaml(config).get("observe-only"));
                return failure.get();
            };
            assertEquals(
                    Outcome.FAILED,
                    CultObserve.switchTo(true, config, env(null), reload).join());
            assertEquals(List.of(true), written, "the reload sees the new key");
            assertArrayEquals(original, Files.readAllBytes(config.toPath()));
        }
    }

    private File config(String content) throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }

    private static Map<String, Object> yaml(File config) {
        try (Reader reader = Files.newBufferedReader(config.toPath(), StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ConfigManager fileBacked(File config) {
        Map<String, Object> values = yaml(config);
        ConfigManager manager = mock(ConfigManager.class, Mockito.withSettings().defaultAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            return arguments.length > 1 ? arguments[1] : null;
        }));
        when(manager.getBooleanElse(eq("observe-only"), anyBoolean()))
                .thenAnswer(invocation -> values.getOrDefault("observe-only", invocation.getArgument(1)));
        return manager;
    }

    private static Function<String, String> env(String value) {
        return name -> name.equals(BaseConfigManager.OBSERVE_ONLY_ENV) ? value : null;
    }

    private static List<String> capture(Runnable action) {
        Logger logger = LogUtil.getLogger();
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }
}
