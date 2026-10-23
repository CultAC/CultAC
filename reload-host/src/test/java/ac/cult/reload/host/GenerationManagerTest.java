package ac.cult.reload.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.fixture.FixtureEntrypoint;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.jar.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationManagerTest {
    private static final String LOADER_VERSION = "2";

    @TempDir
    Path directory;

    final Map<String, Object> controls = new ConcurrentHashMap<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final List<String> reports = new CopyOnWriteArrayList<>();

    private GenerationManager start() throws Exception {
        return start(artifact("initial.jar", "1.0", "initial", "paper"));
    }

    private GenerationManager start(Path bundled) throws Exception {
        controls.put("events", events);
        var manager = new GenerationManager(
                directory.resolve("CultAC"),
                "paper",
                this,
                controls,
                null,
                getClass().getClassLoader(),
                reports::add);
        manager.connectionResolver(connection -> controls.get("upstream:" + connection));
        manager.loadInitial(Files.newInputStream(bundled));
        manager.startInitial();
        return manager;
    }

    private Path artifact(String filename, String version, String variant, String platform) throws Exception {
        Path path = directory.resolve("CultAC/updates").resolve(filename);
        Files.createDirectories(path.getParent());
        var manifest = new Manifest();
        var fields = manifest.getMainAttributes();
        fields.putValue("Manifest-Version", "1.0");
        fields.putValue("Cult-Runtime-SPI", "1");
        fields.putValue("Cult-Runtime-Platform", platform);
        fields.putValue("Cult-Runtime-Java", "21");
        fields.putValue("Cult-Runtime-Entrypoint", FixtureEntrypoint.class.getName());
        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            String type = FixtureEntrypoint.class.getName().replace('.', '/') + ".class";
            out.putNextEntry(new JarEntry(type));
            try (var input = getClass().getClassLoader().getResourceAsStream(type)) {
                input.transferTo(out);
            }
            out.closeEntry();
            put(
                    out,
                    PluginDescriptors.name(platform),
                    platform.equals("paper")
                            ? "name: CultAC\nversion: '" + version + "'\ncult-loader-version: '2'\n"
                            : "{\"version\":\"" + version + "\",\"cult-loader-version\":\"2\"}");
            put(out, "fixture.properties", "variant=" + variant);
        }
        return path;
    }

    private static void put(JarOutputStream jar, String path, String text) throws Exception {
        jar.putNextEntry(new JarEntry(path));
        jar.write(text.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void eventually(BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean());
    }

    @Test
    void pinnedSessionsSurviveUnlimitedGenerationsAndPendingDisconnect() throws Exception {
        var manager = start();
        try {
            manager.attach("old", null);
            var pending = new CompletableFuture<Void>();
            controls.put("disconnect:old", pending);
            for (int i = 0; i < 5; i++) {
                artifact("v" + i + ".jar", "2." + i, "v" + i, "paper");
                await(manager.update("v" + i + ".jar"));
                manager.attach("player" + i, null);
            }
            assertEquals(6, manager.status().size());
            assertTrue(events.contains("initial:attach:old"));
            assertTrue(events.contains("v4:attach:player4"));
            manager.disconnect("old");
            assertFalse(events.contains("initial:close"));
            pending.complete(null);
            eventually(() -> manager.status().size() == 5);
            assertTrue(events.indexOf("initial:disconnect:old") < events.indexOf("initial:close"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void observeOnlyFollowsTheCurrentGeneration() throws Exception {
        var manager = start();
        try {
            assertFalse(manager.isObserveOnly());
            controls.put("observe-only:v2", true);
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            assertTrue(manager.isObserveOnly());
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void failingOpenFollowsTheCurrentGenerationAndHoldsWithoutOne() throws Exception {
        try (var empty = new GenerationManager(
                directory.resolve("CultAC"),
                "paper",
                this,
                controls,
                null,
                getClass().getClassLoader(),
                reports::add)) {
            assertFalse(empty.isObserveOnly());
            assertTrue(empty.failsOpen());
        }
        var manager = start();
        try {
            assertFalse(manager.failsOpen());
            controls.put("observe-only:v2", true);
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            assertTrue(manager.failsOpen());
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void handoffMovesDrainingConnectionToCurrentGeneration() throws Exception {
        var manager = start();
        try {
            var uuid = UUID.randomUUID();
            manager.attach("player", uuid);
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            assertTrue(manager.handoff("player", uuid));
            assertTrue(events.indexOf("initial:release:player") < events.indexOf("v2:adopt:player:initial"));
            assertEquals(manager.currentId(), manager.ownerOf("player"));
            assertEquals(manager.currentId(), manager.user(uuid).owner());
            eventually(() -> events.contains("initial:close"));
            eventually(() -> manager.status().size() == 1);
            assertTrue(manager.status().getFirst().contains("sessions=1"));
            assertFalse(manager.handoff("player", uuid));
            await(manager.disconnect("player"));
            assertTrue(events.contains("v2:disconnect:player"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void runtimesAreSelectedByNumberOrIdAndListTheirPlayers() throws Exception {
        var manager = start();
        try {
            var first = UUID.randomUUID();
            var second = UUID.randomUUID();
            manager.attach("first", first);
            String initial = manager.ownerOf("first");
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            manager.attach("second", second);
            var lines = manager.status();
            assertTrue(lines.get(0).startsWith("#1 ") && lines.get(0).contains(" created "));
            assertTrue(lines.get(1).startsWith("#2 ") && lines.get(1).contains("CURRENT"));
            assertEquals(List.of(first), manager.players("1"));
            assertEquals(List.of(first), manager.players(initial));
            assertEquals(List.of(second), manager.players("#2"));
            assertEquals(lines.get(1), manager.status("2"));
            assertThrows(IllegalArgumentException.class, () -> manager.players("3"));
            assertEquals(
                    List.of("tell Alex Proxy update in 15 minutes", "tell Sam Proxy update in 15 minutes"),
                    CommandRouting.perPlayer(
                            "runtimes 1 /tell %player% Proxy update in 15 minutes".split(" "), List.of("Alex", "Sam")));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void refusedReleaseKeepsConnectionOnDrainingGeneration() throws Exception {
        var manager = start();
        try {
            manager.attach("player", null);
            String initial = manager.ownerOf("player");
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            controls.put("keep:player", true);
            assertFalse(manager.handoff("player", null));
            assertEquals(initial, manager.ownerOf("player"));
            assertFalse(events.stream().anyMatch(event -> event.contains(":adopt:")));
            assertFalse(events.contains("initial:close"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void translatedHandoffMovesBothPinnedTransportsAndUserTogether() throws Exception {
        var manager = start();
        try {
            var uuid = UUID.randomUUID();
            manager.attach("bedrock", null);
            controls.put("upstream:java", "bedrock");
            manager.attach("java", uuid);
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            // Resolution is an admission fact, not a fresh UUID/channel lookup during transfer.
            controls.remove("upstream:java");
            assertTrue(manager.handoff("java", uuid));
            assertEquals(manager.currentId(), manager.ownerOf("java"));
            assertEquals(manager.currentId(), manager.ownerOf("bedrock"));
            assertEquals(manager.currentId(), manager.user(uuid).owner());
            eventually(() -> events.contains("initial:close"));
            eventually(() -> manager.status().size() == 1);
            assertTrue(manager.status().getFirst().contains("sessions=2"));
            await(manager.disconnect("java"));
            assertEquals(manager.currentId(), manager.ownerOf("bedrock"));
            await(manager.disconnect("bedrock"));
            assertTrue(events.contains("v2:disconnect:bedrock"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void pendingUpstreamDisconnectPreventsTranslatedHandoff() throws Exception {
        var manager = start();
        var pending = new CompletableFuture<Void>();
        try {
            manager.attach("bedrock", null);
            controls.put("upstream:java", "bedrock");
            manager.attach("java", null);
            String initial = manager.ownerOf("java");
            controls.put("disconnect:bedrock", pending);
            manager.disconnect("bedrock");
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            assertFalse(manager.handoff("java", null));
            assertEquals(initial, manager.ownerOf("java"));
            assertFalse(events.contains("initial:release:java"));
        } finally {
            pending.complete(null);
            await(manager.stop());
        }
    }

    @Test
    void failedTranslatedAdoptionCleansBothTransports() throws Exception {
        var manager = start();
        try {
            manager.attach("bedrock", null);
            controls.put("upstream:java", "bedrock");
            manager.attach("java", UUID.randomUUID());
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            controls.put("fail-adopt:java", true);
            assertThrows(RuntimeException.class, () -> manager.handoff("java", null));
            eventually(() -> manager.ownerOf("java") == null && manager.ownerOf("bedrock") == null);
            assertTrue(events.contains("v2:disconnect:java"));
            assertTrue(events.contains("v2:disconnect:bedrock"));
            eventually(() -> manager.status().size() == 1);
            assertTrue(manager.status().getFirst().contains("sessions=0"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void publicationDuringReleaseKeepsTheSelectedTargetOpenForBothTransports() throws Exception {
        var manager = start();
        var barrier = new CompletableFuture<Void>();
        var entered = new CompletableFuture<Void>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            manager.attach("bedrock", null);
            controls.put("upstream:java", "bedrock");
            manager.attach("java", null);
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            String selected = manager.currentId();
            controls.put("release:java", barrier);
            controls.put("entered:release:java", entered);
            var transfer = executor.submit(() -> manager.handoff("java", null));
            entered.get(10, TimeUnit.SECONDS);
            artifact("v3.jar", "3", "v3", "paper");
            await(manager.update("v3.jar"));
            assertFalse(events.contains("v2:close"));
            assertTrue(manager.status(selected).contains("sessions=0"));
            barrier.complete(null);
            assertTrue(transfer.get(10, TimeUnit.SECONDS));
            assertEquals(selected, manager.ownerOf("java"));
            assertEquals(selected, manager.ownerOf("bedrock"));
            await(manager.disconnect("java"));
            await(manager.disconnect("bedrock"));
            eventually(() -> events.contains("v2:close"));
        } finally {
            barrier.complete(null);
            executor.shutdownNow();
            await(manager.stop());
        }
    }

    @Test
    void hungStartIsFencedWithoutBlockingLaterUpdates() throws Exception {
        var manager = start();
        try {
            manager.lifecycleTimeout(java.time.Duration.ofMillis(300));
            var hung = new CompletableFuture<Void>();
            controls.put("start:hung", hung);
            artifact("hung.jar", "2", "hung", "paper");
            var error = assertThrows(ExecutionException.class, () -> await(manager.update("hung.jar")));
            assertTrue(error.getCause().getMessage().contains("did not finish start"));
            artifact("next.jar", "3", "next", "paper");
            await(manager.update("next.jar"));
            manager.attach("player", null);
            assertTrue(events.contains("next:attach:player"));
            assertFalse(events.contains("hung:close"));
            hung.complete(null);
            eventually(() -> events.contains("hung:close"));
            eventually(() -> manager.status().size() == 1);
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void hungSupersededDoesNotBlockLaterDeploymentsOrCloseUnderItsCallback() throws Exception {
        var manager = start();
        var release = new CompletableFuture<Void>();
        var entered = new CompletableFuture<Void>();
        try {
            manager.lifecycleTimeout(java.time.Duration.ofMillis(300));
            controls.put("superseded:initial", release);
            controls.put("entered:superseded:initial", entered);
            artifact("next.jar", "2", "next", "paper");
            var updating = manager.update("next.jar");
            entered.get(10, TimeUnit.SECONDS);
            assertTrue(updating.toCompletableFuture().get(2, TimeUnit.SECONDS).startsWith("Activated"));
            artifact("later.jar", "3", "later", "paper");
            await(manager.update("later.jar"));
            manager.attach("player", null);
            assertTrue(events.contains("later:attach:player"));
            assertFalse(events.contains("initial:close"));
            assertTrue(reports.stream().anyMatch(line -> line.contains("did not finish supersede")));
            release.complete(null);
            eventually(() -> events.contains("initial:close"));
            eventually(() -> manager.status().size() == 1);
        } finally {
            release.complete(null);
            await(manager.stop());
        }
    }

    @Test
    void hungStopKeepsLoaderOpenUntilItFinishes() throws Exception {
        var manager = start();
        try {
            manager.lifecycleTimeout(java.time.Duration.ofMillis(300));
            var stopping = new CompletableFuture<Void>();
            controls.put("close:initial", stopping);
            artifact("v2.jar", "2", "v2", "paper");
            await(manager.update("v2.jar"));
            eventually(() -> reports.stream().anyMatch(report -> report.contains("did not stop within")));
            assertTrue(manager.status().stream().anyMatch(line -> line.contains("CLEANUP_FAILED")));
            assertFalse(reports.stream().anyMatch(report -> report.startsWith("Unloaded")));
            stopping.complete(null);
            eventually(() -> manager.status().size() == 1);
            assertTrue(reports.stream().anyMatch(report -> report.startsWith("Unloaded")));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void descriptorVersionAndHashAreBothPartOfIdentity() throws Exception {
        var manager = start();
        try {
            Path first = artifact("same-version.jar", "1.0", "different", "paper");
            String identity =
                    RuntimeArtifact.inspect(first, "paper", LOADER_VERSION).display();
            assertTrue(await(manager.update("same-version.jar")).contains(identity));
            int starts = (int) events.stream()
                    .filter(event -> event.equals("different:start"))
                    .count();
            Files.copy(first, first.resolveSibling("duplicate.jar"));
            assertTrue(await(manager.update("duplicate.jar")).startsWith("Already running"));
            assertEquals(
                    starts,
                    events.stream()
                            .filter(event -> event.equals("different:start"))
                            .count());
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void externalBuildSymlinkIsSnapshottedAndSurvivesCleanup() throws Exception {
        var manager = start();
        try {
            Path build = directory.resolve("build/CultAC-dev.jar");
            Files.createDirectories(build.getParent());
            Files.move(artifact("first.jar", "2", "first", "paper"), build);
            Path link = directory.resolve("CultAC/updates/dev.jar");
            Files.createSymbolicLink(link, build);
            byte[] first = Files.readAllBytes(build);
            await(manager.update("dev.jar"));
            manager.attach("old", null);
            var marker = new Properties();
            try (var input = Files.newInputStream(directory.resolve("CultAC/runtime/current.properties"))) {
                marker.load(input);
            }
            Path snapshot = directory.resolve("CultAC/runtime").resolve(marker.getProperty("artifact"));

            Files.move(artifact("second.jar", "2", "second", "paper"), build, StandardCopyOption.REPLACE_EXISTING);
            assertArrayEquals(first, Files.readAllBytes(snapshot));
            await(manager.update("dev.jar"));
            manager.attach("new", null);
            assertTrue(events.contains("first:attach:old"));
            assertTrue(events.contains("second:attach:new"));
            assertFalse(events.contains("first:close"));
            await(manager.disconnect("old"));
            eventually(() -> events.contains("first:close"));

            byte[] rebuilt = Files.readAllBytes(build);
            PluginInstaller.cleanUpdates(link.getParent());
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(build, link.toRealPath());
            assertArrayEquals(rebuilt, Files.readAllBytes(build));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void explicitLoaderRevisionRejectsOnlyIncompatibleReleases() throws Exception {
        var manager = start();
        try {
            Path incompatible = artifact("incompatible.jar", "2", "new", "paper");
            String incompatibleVersion = Integer.toString(Integer.parseInt(LOADER_VERSION) + 1);
            rewriteDescriptor(
                    incompatible, "name: CultAC\nversion: '2'\ncult-loader-version: '" + incompatibleVersion + "'\n");
            var error = assertThrows(ExecutionException.class, () -> await(manager.update("incompatible.jar")));
            assertTrue(error.getCause().getMessage().contains("cult-loader-version " + incompatibleVersion));
            manager.attach("original", null);
            assertTrue(events.contains("initial:attach:original"));
            artifact("compatible.jar", "2", "compatible-fix", "paper");
            await(manager.update("compatible.jar"));
            manager.attach("new", null);
            assertTrue(events.contains("compatible-fix:attach:new"));
        } finally {
            await(manager.stop());
        }
    }

    private void rewriteDescriptor(Path artifact, String descriptor) throws Exception {
        Path temporary = artifact.resolveSibling("rewrite.tmp");
        try (var source = new JarFile(artifact.toFile());
                var output = new JarOutputStream(Files.newOutputStream(temporary))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                output.putNextEntry(new JarEntry(entry.getName()));
                if (entry.getName().equals("plugin.yml")) output.write(descriptor.getBytes(StandardCharsets.UTF_8));
                else
                    try (var input = source.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                output.closeEntry();
            }
        }
        Files.move(temporary, artifact, StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void fullReleaseIsPreservedAndInstalledEvenAfterStagedSourceIsRemoved() throws Exception {
        var manager = start();
        Path runtime = artifact("engine.jar", "2", "new", "paper");
        Path release = runtime.resolveSibling("release.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(release))) {
            put(output, "plugin.yml", "name: CultAC\nversion: '2'\ncult-loader-version: '2'\nmain: loader.Main\n");
            put(output, "loader/Main.class", "compatible-loader-bugfix");
            output.putNextEntry(new JarEntry(PluginInstaller.ENGINE));
            Files.copy(runtime, output);
            output.closeEntry();
        }
        byte[] expected = Files.readAllBytes(release);
        Path installed = directory.resolve("plugins/CultAC.jar");
        Files.createDirectories(installed.getParent());
        Files.copy(release, installed);
        await(manager.update("release.jar"));
        Files.delete(release);
        await(manager.shutdown(installed));
        assertArrayEquals(expected, Files.readAllBytes(installed));
        assertTrue(events.contains("new:close"));
    }

    @Test
    void failedUpdateAndReloadValidationPreservePublishedState() throws Exception {
        var manager = start();
        try {
            byte[] marker = Files.readAllBytes(directory.resolve("CultAC/runtime/current.properties"));
            byte[] config = Files.readAllBytes(directory.resolve("CultAC/config.yml"));
            artifact("broken.jar", "3", "broken", "paper");
            assertThrows(ExecutionException.class, () -> await(manager.update("broken.jar")));
            artifact("wrong.jar", "3", "wrong", "velocity");
            assertThrows(ExecutionException.class, () -> await(manager.update("wrong.jar")));
            assertThrows(ExecutionException.class, () -> await(manager.update("../initial.jar")));
            assertArrayEquals(marker, Files.readAllBytes(directory.resolve("CultAC/runtime/current.properties")));
            assertArrayEquals(config, Files.readAllBytes(directory.resolve("CultAC/config.yml")));
            controls.put("reject:initial", true);
            assertFalse(await(manager.reload(null)));
            assertFalse(events.contains("initial:apply:original"));
            assertFalse(events.contains("initial:apply:prepared"));
            manager.attach("survivor", null);
            assertTrue(events.contains("initial:attach:survivor"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void aReplacedGenerationIsToldOnceBeforeItDrains() throws Exception {
        var manager = start();
        try {
            manager.attach("pinned", null);
            artifact("next.jar", "2", "next", "paper");
            await(manager.update("next.jar"));
            assertEquals(1, events.stream().filter("initial:superseded"::equals).count());
            assertFalse(events.contains("initial:close"), "a pinned connection keeps the generation open");
            assertTrue(events.indexOf("next:start") < events.indexOf("initial:superseded"));
            await(manager.disconnect("pinned"));
            eventually(() -> events.contains("initial:close"));
            assertTrue(events.indexOf("initial:superseded") < events.indexOf("initial:close"));
            assertFalse(events.contains("next:superseded"));
        } finally {
            await(manager.stop());
        }
        assertFalse(events.contains("next:superseded"), "stopping the host closes without superseding");
    }

    @Test
    void aGenerationThatFailsToStepAsideStillDrains() throws Exception {
        var manager = start();
        try {
            controls.put("superseded-fail:initial", true);
            artifact("next.jar", "2", "next", "paper");
            assertTrue(await(manager.update("next.jar")).startsWith("Activated"));
            eventually(() -> events.contains("initial:close"));
            assertTrue(reports.stream().anyMatch(line -> line.contains("failed to step aside")));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void aDuplicateUpdateSupersedesNothing() throws Exception {
        var manager = start();
        try {
            Path first = artifact("same.jar", "2", "same", "paper");
            await(manager.update("same.jar"));
            Files.copy(first, first.resolveSibling("again.jar"));
            assertTrue(await(manager.update("again.jar")).startsWith("Already running"));
            assertFalse(events.contains("same:superseded"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void downstreamUsesPinnedUpstreamAfterUpdate() throws Exception {
        var manager = start();
        try {
            manager.attach("dummy", null, true);
            assertFalse(events.contains("initial:attach:dummy"));
            manager.attach("upstream", null);
            controls.put("upstream:downstream", "upstream");
            artifact("new.jar", "2", "new", "paper");
            await(manager.update("new.jar"));
            manager.attach("downstream", null, true);
            assertTrue(events.contains("initial:attach:downstream"));
            await(manager.disconnect("upstream"));
            assertFalse(events.contains("initial:close"));
            await(manager.disconnect("downstream"));
            eventually(() -> events.contains("initial:close"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void shutdownInstallsLatestAndCleansUpdatesBeforeUnload() throws Exception {
        var manager = start();
        Path installed = directory.resolve("plugins/CultAC.jar");
        Files.createDirectories(installed.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(installed))) {
            put(output, "plugin.yml", "name: CultAC\nversion: '1.0'\nmain: loader.Main\n");
            put(output, "loader/Main.class", "bootstrap-stays");
            output.putNextEntry(new JarEntry(PluginInstaller.ENGINE));
            Files.copy(directory.resolve("CultAC/updates/initial.jar"), output);
            output.closeEntry();
        }
        Path latest = artifact("new.jar", "2.7", "new", "paper");
        byte[] bytes = Files.readAllBytes(latest);
        await(manager.update("new.jar"));
        manager.attach("current", null);
        var pending = new CompletableFuture<Void>();
        controls.put("disconnect:current", pending);
        CompletionStage<Void> shutdown = manager.shutdown(installed);
        eventually(() -> !Files.exists(latest));
        assertFalse(events.contains("new:close"));
        try (var archive = new ZipFile(installed.toFile())) {
            assertEquals(
                    "2.7",
                    PluginDescriptors.version(
                            archive.getInputStream(archive.getEntry("plugin.yml"))
                                    .readAllBytes(),
                            "paper"));
            assertArrayEquals(
                    bytes,
                    archive.getInputStream(archive.getEntry(PluginInstaller.ENGINE))
                            .readAllBytes());
            assertEquals(
                    "bootstrap-stays",
                    new String(
                            archive.getInputStream(archive.getEntry("loader/Main.class"))
                                    .readAllBytes(),
                            StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(directory.resolve("CultAC/runtime/current.properties")));
        pending.complete(null);
        await(shutdown);
        assertTrue(events.contains("new:close"));
        try (var archive = new ZipFile(installed.toFile());
                var restarted = new GenerationManager(
                        directory.resolve("CultAC"),
                        "paper",
                        this,
                        controls,
                        null,
                        getClass().getClassLoader(),
                        reports::add)) {
            restarted.loadInitial(archive.getInputStream(archive.getEntry(PluginInstaller.ENGINE)));
            restarted.startInitial();
            assertTrue(restarted.status().getFirst().startsWith("#1 2.7@"));
        }
    }

    @Test
    void failedPromotionKeepsRecoveryArtifactAndUpdateFiles() throws Exception {
        var manager = start();
        Path update = artifact("new.jar", "2", "new", "paper");
        await(manager.update("new.jar"));
        assertThrows(ExecutionException.class, () -> await(manager.shutdown(directory.resolve("missing.jar"))));
        assertTrue(Files.exists(update));
        var marker = new Properties();
        try (var input = Files.newInputStream(directory.resolve("CultAC/runtime/current.properties"))) {
            marker.load(input);
        }
        assertTrue(Files.exists(directory.resolve("CultAC/runtime").resolve(marker.getProperty("artifact"))));
    }

    @Test
    void leftoverRuntimeIsIgnoredWhenThePluginBundlesAnotherRuntime() throws Exception {
        stopAfterUpdateToV2();
        Path generations = directory.resolve("CultAC/runtime/generations");
        Files.createDirectories(generations.resolve("leftover"));
        var restarted = start(artifact("installed.jar", "3", "installed", "paper"));
        try {
            restarted.attach("player", null);
            assertTrue(events.contains("installed:attach:player"));
            try (var homes = Files.list(generations)) {
                assertEquals(1, homes.count());
            }
        } finally {
            await(restarted.stop());
        }
    }

    @Test
    void leftoverRuntimeIsKeptWhileThePluginBundlesTheSameRuntime() throws Exception {
        stopAfterUpdateToV2();
        var restarted = start(directory.resolve("CultAC/updates/initial.jar"));
        try {
            restarted.attach("player", null);
            assertTrue(events.contains("v2:attach:player"));
        } finally {
            await(restarted.stop());
        }
    }

    @Test
    void leftoverRuntimeThatCannotBeDeletedDoesNotFailStartup() throws Exception {
        await(start().stop());
        Path orphan = Files.createDirectories(directory.resolve("CultAC/runtime/generations/orphan"));
        Path stuck = Files.writeString(orphan.resolve("runtime.jar"), "x");
        assertTrue(stuck.toFile().setReadOnly());
        orphan.toFile().setWritable(false);
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        assumeTrue(windows || !Files.isWritable(orphan), "deletion cannot be blocked for this user");
        try {
            var restarted = start();
            try {
                restarted.attach("player", null);
                assertTrue(events.contains("initial:attach:player"));
                assertTrue(reports.stream().anyMatch(line -> line.contains("orphan")));
            } finally {
                await(restarted.stop());
            }
        } finally {
            orphan.toFile().setWritable(true);
            stuck.toFile().setWritable(true);
        }
    }

    @Test
    void leftoverRuntimeThatCannotBeListedDoesNotFailStartup() throws Exception {
        await(start().stop());
        Path locked = Files.createDirectories(directory.resolve("CultAC/runtime/generations/orphan/locked"));
        Files.writeString(locked.resolve("runtime.jar"), "x");
        var acl = Files.getFileAttributeView(locked, AclFileAttributeView.class);
        List<AclEntry> original = acl == null ? null : acl.getAcl();
        if (acl != null) {
            var denied = new ArrayList<AclEntry>();
            denied.add(AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(AclEntryPermission.READ_DATA)
                    .build());
            denied.addAll(original);
            acl.setAcl(denied);
        } else {
            locked.toFile().setReadable(false);
        }
        try {
            assumeTrue(cannotList(locked), "listing cannot be blocked for this user");
            var restarted = start();
            try {
                restarted.attach("player", null);
                assertTrue(events.contains("initial:attach:player"));
                assertTrue(reports.stream().anyMatch(line -> line.contains("cleanup failed for orphan")));
            } finally {
                await(restarted.stop());
            }
        } finally {
            if (acl != null) acl.setAcl(original);
            else locked.toFile().setReadable(true);
        }
    }

    private static boolean cannotList(Path directory) {
        try (var entries = Files.list(directory)) {
            entries.toList();
            return false;
        } catch (IOException | UncheckedIOException failure) {
            return true;
        }
    }

    @Test
    void incompatibleLeftoverRuntimeFallsBackToTheBundledRuntime() throws Exception {
        stopAfterUpdateToV2();
        var marker = new Properties();
        try (var input = Files.newInputStream(directory.resolve("CultAC/runtime/current.properties"))) {
            marker.load(input);
        }
        rewriteDescriptor(
                directory.resolve("CultAC/runtime").resolve(marker.getProperty("artifact")),
                "name: CultAC\nversion: '2'\ncult-loader-version: '3'\n");
        var restarted = start(directory.resolve("CultAC/updates/initial.jar"));
        try {
            restarted.attach("player", null);
            assertTrue(events.contains("initial:attach:player"));
            assertTrue(reports.stream().anyMatch(line -> line.contains("cult-loader-version 3")));
        } finally {
            await(restarted.stop());
        }
    }

    @Test
    void unlinkableLeftoverRuntimeFallsBackToTheBundledRuntime() throws Exception {
        stopAfterUpdateToV2();
        controls.put("unlinked:v2", true);
        var restarted = start(directory.resolve("CultAC/updates/initial.jar"));
        try {
            restarted.attach("player", null);
            assertTrue(events.contains("initial:attach:player"));
            assertTrue(reports.stream().anyMatch(line -> line.contains("NoClassDefFoundError")));
        } finally {
            await(restarted.stop());
        }
    }

    private void stopAfterUpdateToV2() throws Exception {
        var manager = start();
        artifact("v2.jar", "2", "v2", "paper");
        await(manager.update("v2.jar"));
        await(manager.stop());
    }

    @Test
    void cachedApiHandlesReleasePrivateLoaderAfterDisconnect() throws Exception {
        var manager = start();
        GrimAbstractAPI api = (GrimAbstractAPI) manager.publicApi();
        UUID uuid = UUID.randomUUID();
        manager.attach("old", uuid);
        GrimUser cached = api.getGrimUser(uuid);
        var function = api.getFunction("fixture");
        assertEquals("initial", cached.getName());
        assertEquals("initial", function.apply(null));
        @SuppressWarnings("unchecked")
        var loader = (WeakReference<ClassLoader>) controls.get("loader:initial");
        artifact("new.jar", "2", "new", "paper");
        await(manager.update("new.jar"));
        await(manager.disconnect("old"));
        eventually(() -> manager.status().size() == 1);
        assertThrows(RuntimeException.class, cached::getName);
        assertThrows(RuntimeException.class, () -> function.apply(null));
        eventually(() -> {
            System.gc();
            return loader.get() == null;
        });
        assertTrue(api.getGrimVersion().startsWith("2@"));
        await(manager.stop());
    }

    @Test
    void admittedConnectionKeepsItsOwnerWhenPublicationRacesWithAttach() throws Exception {
        var manager = start();
        var release = new CompletableFuture<Void>();
        var entered = new CompletableFuture<Void>();
        controls.put("attach:racing", release);
        controls.put("entered:attach:racing", entered);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var attach = threads.submit(() -> {
                manager.attach("racing", UUID.randomUUID());
                return null;
            });
            try {
                await(entered);
                artifact("new.jar", "2", "new", "paper");
                await(manager.update("new.jar"));
                assertFalse(events.contains("initial:close"));
                release.complete(null);
                attach.get(5, TimeUnit.SECONDS);
                await(manager.disconnect("racing"));
                eventually(() -> events.contains("initial:close"));
            } finally {
                release.complete(null);
                await(manager.stop());
            }
        }
    }

    @Test
    void retiredGenerationWaitsForApiCallAndAsynchronousCommand() throws Exception {
        var manager = start();
        var api = (GrimAbstractAPI) manager.publicApi();
        var function = api.getFunction("fixture");
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var command = new CompletableFuture<Void>();
        controls.put("function:initial", release);
        controls.put("entered:function:initial", entered);
        controls.put("command:pending", command);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = threads.submit(() -> function.apply(null));
            try {
                await(entered);
                var executed = manager.execute(null, "pending", null, false);
                artifact("new.jar", "2", "new", "paper");
                await(manager.update("new.jar"));
                eventually(() -> manager.status().stream().anyMatch(line -> line.contains("STOPPING")));
                assertFalse(events.contains("initial:close"));
                release.complete(null);
                assertEquals("initial", call.get(5, TimeUnit.SECONDS));
                assertFalse(events.contains("initial:close"));
                command.complete(null);
                await(executed);
                eventually(() -> events.contains("initial:close"));
                assertThrows(RuntimeException.class, () -> function.apply(null));
            } finally {
                release.complete(null);
                command.complete(null);
                await(manager.stop());
            }
        }
    }

    @Test
    void reconnectBindingAndRepeatedDisconnectCannotRemoveReplacement() throws Exception {
        var manager = start();
        UUID uuid = UUID.randomUUID();
        try {
            var api = (GrimAbstractAPI) manager.publicApi();
            manager.attach("old", uuid);
            var old = api.getGrimUser(uuid);
            artifact("new.jar", "2", "new", "paper");
            await(manager.update("new.jar"));
            manager.attach("replacement", uuid);
            var replacement = api.getGrimUser(uuid);
            assertEquals("new", replacement.getName());
            var pending = new CompletableFuture<Void>();
            controls.put("disconnect:old", pending);
            var first = manager.disconnect("old");
            var second = manager.disconnect("old");
            assertThrows(RuntimeException.class, old::getName);
            assertSame(replacement, api.getGrimUser(uuid));
            pending.complete(null);
            await(first);
            await(second);
            assertEquals(
                    1, events.stream().filter("initial:disconnect:old"::equals).count());
            assertEquals("new", replacement.getName());
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void stopFailureRetainsGenerationAndDoesNotCloseItsLoader() throws Exception {
        var manager = start();
        controls.put("close-fail:initial", true);
        artifact("new.jar", "2", "new", "paper");
        await(manager.update("new.jar"));
        eventually(() -> manager.status().stream().anyMatch(line -> line.contains("CLEANUP_FAILED")));
        assertFalse(reports.stream().anyMatch(line -> line.startsWith("Unloaded")));
        assertThrows(ExecutionException.class, () -> await(manager.stop()));
    }

    @Test
    void stagingCleanupPreservesDirectoriesAndSymlinks() throws Exception {
        Path updates = Files.createDirectory(directory.resolve("updates"));
        Path target = Files.writeString(directory.resolve("target.jar"), "keep");
        Files.createSymbolicLink(updates.resolve("linked.jar"), target);
        Files.createDirectory(updates.resolve("nested"));
        Files.writeString(updates.resolve("staged.jar"), "remove");
        assertEquals(1, PluginInstaller.cleanUpdates(updates));
        assertTrue(Files.isSymbolicLink(updates.resolve("linked.jar")));
        assertTrue(Files.isDirectory(updates.resolve("nested")));
        assertEquals("keep", Files.readString(target));
    }

    @Test
    void failedConnectionCleanupStillReleasesTheSessionAndTheDrainingGenerationRetires() throws Exception {
        var manager = start();
        try {
            manager.attach("failed", null);
            artifact("new.jar", "2", "new", "paper");
            await(manager.update("new.jar"));
            controls.put("disconnect:failed", CompletableFuture.failedFuture(new IllegalStateException("fixture")));
            assertThrows(ExecutionException.class, () -> await(manager.disconnect("failed")));
            eventually(() -> manager.status().size() == 1);
            assertTrue(events.contains("initial:close"));
            assertTrue(reports.stream().anyMatch(line -> line.contains("session cleanup failed")));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void failedConnectionCleanupFailsShutdownWithoutUnsafeUnloadOrHanging() throws Exception {
        var manager = start();
        manager.attach("failed", null);
        var pending = new CompletableFuture<Void>();
        controls.put("disconnect:failed", pending);
        var stopped = manager.stop();
        eventually(() -> events.contains("initial:disconnect:failed"));
        pending.completeExceptionally(new IllegalStateException("fixture"));
        assertThrows(ExecutionException.class, () -> await(stopped));
        assertTrue(manager.status().getFirst().contains("CLEANUP_FAILED"));
        assertFalse(events.contains("initial:close"));
    }

    @Test
    void callerCanRetainRejectedRegistrationExceptionWithoutRetainingLoader() throws Exception {
        var manager = start();
        try {
            var api = (GrimAbstractAPI) manager.publicApi();
            var cachedFailure =
                    assertThrows(IllegalArgumentException.class, () -> api.registerFunction("fail", value -> value));
            @SuppressWarnings("unchecked")
            var loader = (WeakReference<ClassLoader>) controls.get("loader:initial");
            artifact("new.jar", "2", "new", "paper");
            await(manager.update("new.jar"));
            eventually(() -> manager.status().size() == 1);
            eventually(() -> {
                System.gc();
                return loader.get() == null;
            });
            assertTrue(cachedFailure.getMessage().contains("Fixture rejected function"));
        } finally {
            await(manager.stop());
        }
    }
}
