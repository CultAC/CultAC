package ac.cult.reload.host;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.fixture.FixtureEntrypoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildUpdatesTest {
    static final String REPOSITORY = "owner/repo";
    static final String BUNDLED = "0.1.0-b141-main-aaaaaaa";

    @TempDir
    Path directory;

    final Map<String, Object> controls = new ConcurrentHashMap<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final Map<String, Consumer<HttpExchange>> routes = new ConcurrentHashMap<>();
    final List<String> authorized = new CopyOnWriteArrayList<>();
    final JsonArray releases = new JsonArray();

    HttpServer server;
    KeyPair signer;
    GenerationManager engine;

    @BeforeEach
    void setUp() throws Exception {
        signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestHeaders().getFirst("Host") + path);
            if (exchange.getRequestHeaders().containsKey("Authorization"))
                authorized.add(exchange.getRequestHeaders().getFirst("Host") + path);
            var route = routes.get(path);
            if (route == null) respond(exchange, 404, new byte[0]);
            else route.accept(exchange);
        });
        routes.put(
                "/api/repos/" + REPOSITORY + "/releases",
                exchange -> respond(exchange, 200, releases.toString().getBytes(StandardCharsets.UTF_8)));
        server.start();
        controls.put("events", events);
        engine = new GenerationManager(
                directory.resolve("CultAC"),
                "paper",
                this,
                controls,
                null,
                getClass().getClassLoader(),
                ignored -> {});
        engine.loadInitial(Files.newInputStream(jar("initial.jar", "0.1.0-b141-main-aaaaaaa", "initial")));
        engine.startInitial();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        engine.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Downloads on 127.0.0.1, the API on localhost: two origins on one server. */
    BuildUpdates.Endpoints endpoints() {
        return new BuildUpdates.Endpoints(
                URI.create("http://127.0.0.1:" + port()),
                URI.create("http://localhost:" + port() + "/api"),
                Set.of("127.0.0.1"));
    }

    UpdateSettings settings() {
        return settings(Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));
    }

    UpdateSettings settings(String publicKey) {
        return new UpdateSettings(REPOSITORY, "api-secret", publicKey, "", directory.resolve("state.json"), 0);
    }

    BuildUpdates updates() {
        return updates(Duration.ofSeconds(5));
    }

    BuildUpdates updates(Duration timeout) {
        return updates(BUNDLED, timeout);
    }

    BuildUpdates updates(String bundled, Duration timeout) {
        return new BuildUpdates(settings(), bundled, endpoints(), Duration.ofSeconds(2), timeout, timeout);
    }

    Path boot(BuildUpdates updates, Duration limit, List<String> info, List<String> warnings) {
        return updates.boot("2", engine.updates(), limit, info::add, warnings::add);
    }

    String install(BuildUpdates updates, long build) throws Exception {
        return updates.install(engine, build, Runnable::run)
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
    }

    static String releasePath(long build, String file) {
        return "/" + REPOSITORY + "/releases/download/build-" + build + "/" + file;
    }

    /** Serves {@code jar} as build {@code build}, with a manifest {@code edit} may change before it is signed. */
    void publish(long build, String branch, Path jar, String version, Consumer<JsonObject> edit, KeyPair key)
            throws Exception {
        byte[] bytes = Files.readAllBytes(jar);
        var manifest = new JsonObject();
        manifest.addProperty("build", build);
        manifest.addProperty("branch", branch);
        manifest.addProperty("version", version);
        manifest.addProperty("commit", "b".repeat(40));
        manifest.addProperty("jar", "cultac-velocity.jar");
        manifest.addProperty(
                "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        manifest.addProperty("size", bytes.length);
        manifest.addProperty("loader_version", 2);
        manifest.addProperty("companion_sha256", "c".repeat(64));
        manifest.addProperty("published_at", "2026-10-17T20:00:00Z");
        edit.accept(manifest);
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(manifestBytes);
        byte[] signed = Base64.getEncoder().encodeToString(signature.sign()).getBytes(StandardCharsets.US_ASCII);
        routes.put(releasePath(build, "manifest.json"), exchange -> respond(exchange, 200, manifestBytes));
        routes.put(releasePath(build, "manifest.json.sig"), exchange -> respond(exchange, 200, signed));
        routes.put(releasePath(build, "cultac-velocity.jar"), exchange -> respond(exchange, 200, bytes));
        var release = new JsonObject();
        release.addProperty("tag_name", "build-" + build);
        release.addProperty("name", "build-" + build + " (" + branch + " bbbbbbb)");
        release.addProperty("draft", false);
        release.addProperty("published_at", "2026-10-17T20:0" + (build % 10) + ":00Z");
        releases.add(release);
    }

    void publish(long build, Consumer<JsonObject> edit) throws Exception {
        String version = "0.1.0-b" + build + "-main-bbbbbbb";
        publish(build, "main", jar("b" + build + ".jar", version, "next"), version, edit, signer);
    }

    boolean jarRequested() {
        return requests.stream().anyMatch(path -> path.endsWith(".jar"));
    }

    void assertUnchanged() throws IOException {
        assertEquals("0.1.0-b141-main-aaaaaaa", engine.currentBuild());
        assertFalse(events.contains("next:start"));
        assertNull(UpdateState.read(directory.resolve("state.json")));
        try (var left = Files.list(engine.updates())) {
            assertEquals(
                    List.of(),
                    left.filter(path -> path.getFileName().toString().startsWith("build-"))
                            .toList());
        }
    }

    @Test
    void installsASignedBuildAndRecordsIt() throws Exception {
        engine.attach("player", null);
        publish(142, manifest -> {});
        assertEquals("b141 → b142 (main bbbbbbb), 1 players draining", install(updates(), 142));
        assertEquals("0.1.0-b142-main-bbbbbbb", engine.currentBuild());
        assertTrue(events.contains("next:start"));
        assertEquals(new UpdateState.Entry(142, BUNDLED), UpdateState.read(directory.resolve("state.json")));
        assertEquals(List.of(), authorized);
        try (var left = Files.list(engine.updates())) {
            assertEquals(
                    0,
                    left.filter(path -> path.getFileName().toString().startsWith("build-"))
                            .count());
        }
    }

    @Test
    void refusesABadSignatureBeforeDownloading() throws Exception {
        String version = "0.1.0-b142-main-bbbbbbb";
        publish(
                142,
                "main",
                jar("b142.jar", version, "next"),
                version,
                manifest -> {},
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertEquals("update refused: the manifest signature does not verify", install(updates(), 142));
        assertFalse(jarRequested());
        assertUnchanged();
    }

    @Test
    void refusesAManifestOfAnotherBuild() throws Exception {
        publish(143, manifest -> {});
        routes.put(releasePath(142, "manifest.json"), routes.get(releasePath(143, "manifest.json")));
        routes.put(releasePath(142, "manifest.json.sig"), routes.get(releasePath(143, "manifest.json.sig")));
        assertEquals("update refused: the manifest of build-142 is signed for b143", install(updates(), 142));
        assertUnchanged();
    }

    @Test
    void refusesABadHash() throws Exception {
        publish(142, manifest -> manifest.addProperty("sha256", "0".repeat(64)));
        String line = install(updates(), 142);
        assertTrue(line.startsWith("update refused: the jar's sha256 "), line);
        assertUnchanged();
    }

    @Test
    void refusesAWrongSize() throws Exception {
        publish(
                142,
                manifest -> manifest.addProperty("size", manifest.get("size").getAsLong() + 1));
        String line = install(updates(), 142);
        assertTrue(line.startsWith("update refused: the jar is "), line);
        assertUnchanged();
        publish(
                143,
                manifest -> manifest.addProperty("size", manifest.get("size").getAsLong() - 1));
        line = install(updates(), 143);
        assertTrue(line.startsWith("update refused: the jar is larger than the manifest's "), line);
        assertUnchanged();
    }

    @Test
    void aLoaderBumpNeedsARestart() throws Exception {
        publish(143, manifest -> manifest.addProperty("loader_version", 3));
        assertEquals("b143 needs a proxy restart (loader 2 → 3)", install(updates(), 143));
        assertFalse(jarRequested());
        assertUnchanged();
    }

    @Test
    void theRunningBuildIsAlreadyInstalled() throws Exception {
        assertEquals("already on b141", install(updates(), 141));
        assertTrue(requests.isEmpty());
        publish(142, manifest -> {});
        assertEquals("b141 → b142 (main bbbbbbb), 0 players draining", install(updates(), 142));
        assertEquals("already on b142", install(updates(), 142));
    }

    @Test
    void anAlreadyRunningRetryRepairsTheRecordedBuild() throws Exception {
        publish(142, manifest -> {});
        install(updates(), 142);
        Path state = directory.resolve("state.json");
        UpdateState.write(state, 141, BUNDLED);
        requests.clear();
        assertEquals("already on b142", install(updates(), 142));
        assertEquals(new UpdateState.Entry(142, BUNDLED), UpdateState.read(state));
        assertTrue(requests.isEmpty());
    }

    @Test
    void reportsAMissingBuild() throws Exception {
        assertEquals(
                "update refused: 127.0.0.1 has no manifest at " + releasePath(150, "manifest.json"),
                install(updates(), 150));
        assertUnchanged();
    }

    @Test
    void aSlowServerTimesOut() throws Exception {
        publish(142, manifest -> {});
        var manifest = routes.get(releasePath(142, "manifest.json"));
        routes.put(releasePath(142, "manifest.json"), exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            manifest.accept(exchange);
        });
        long start = System.nanoTime();
        assertEquals(
                "update refused: the manifest did not arrive within 0 s from 127.0.0.1",
                install(updates(Duration.ofMillis(300)), 142));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        assertUnchanged();
    }

    @Test
    void downloadsFollowRedirectsToReleaseAssetHostsOnly() throws Exception {
        publish(142, manifest -> {});
        byte[] jar = Files.readAllBytes(directory.resolve("jars/b142.jar"));
        routes.put("/assets/b142", exchange -> respond(exchange, 200, jar));
        routes.put(releasePath(142, "cultac-velocity.jar"), exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port() + "/assets/b142");
            respond(exchange, 302, new byte[0]);
        });
        assertEquals("b141 → b142 (main bbbbbbb), 0 players draining", install(updates(), 142));

        publish(143, manifest -> {});
        routes.put(releasePath(143, "cultac-velocity.jar"), exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port() + "/assets/b142");
            respond(exchange, 302, new byte[0]);
        });
        assertEquals("update refused: refused the jar's redirect from 127.0.0.1 to localhost", install(updates(), 143));
        assertEquals("0.1.0-b142-main-bbbbbbb", engine.currentBuild());
    }

    @Test
    void githubDownloadsStayOnItsReleaseAssetHosts() {
        var updates = new BuildUpdates(settings(), BUNDLED);
        assertTrue(updates.downloadAllowed(URI.create("https://github.com/o/r/releases/download/build-1/x.jar")));
        assertTrue(updates.downloadAllowed(
                URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/1/x")));
        assertFalse(updates.downloadAllowed(URI.create("http://github.com/o/r/releases/download/build-1/x.jar")));
        assertFalse(updates.downloadAllowed(URI.create("https://github.com:8443/o/r/releases/download/build-1/x")));
        assertFalse(updates.downloadAllowed(URI.create("https://example.com/x.jar")));
        assertFalse(updates.downloadAllowed(URI.create("https://api.github.com/repos/o/r/releases")));
        assertFalse(updates.downloadAllowed(URI.create("https://github.com.example.com/x.jar")));
    }

    @Test
    void capsTheManifest() throws Exception {
        publish(142, manifest -> manifest.addProperty("padding", "x".repeat(BuildUpdates.MAX_MANIFEST_BYTES)));
        assertEquals(
                "update refused: the manifest is larger than " + BuildUpdates.MAX_MANIFEST_BYTES + " bytes",
                install(updates(), 142));
        assertUnchanged();
    }

    @Test
    void refusesAnInvalidKeyOrRepository() throws Exception {
        var badKey = new BuildUpdates(
                settings("bm90IGEga2V5"),
                BUNDLED,
                endpoints(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        assertEquals("update refused: the configured update public key is not an Ed25519 key", install(badKey, 142));
        var badRepository = new BuildUpdates(
                new UpdateSettings("../x", "", settings().publicKey(), "", directory.resolve("state.json"), 0),
                BUNDLED,
                endpoints(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        assertEquals("update refused: update.repository ../x is not owner/repo", install(badRepository, 142));
        assertTrue(requests.isEmpty());
    }

    @Test
    void latestInstallsTheNewestBuildOfABranch() throws Exception {
        publish(142, manifest -> {});
        String version = "0.1.0-b144-experimental-bbbbbbb";
        publish(144, "experimental", jar("b144.jar", version, "next"), version, manifest -> {}, signer);
        publish(143, manifest -> {});
        assertEquals(
                "b141 → b143 (main bbbbbbb), 0 players draining",
                updates()
                        .latest(engine, "main", Runnable::run)
                        .toCompletableFuture()
                        .get(20, TimeUnit.SECONDS));
        assertEquals(
                "update refused: no build of release/1 among the newest 100 releases of owner/repo",
                updates()
                        .latest(engine, "release/1", Runnable::run)
                        .toCompletableFuture()
                        .get(20, TimeUnit.SECONDS));
        assertEquals(
                List.of("localhost/api/repos/owner/repo/releases", "localhost/api/repos/owner/repo/releases"),
                authorized.stream()
                        .map(path -> path.replaceFirst(":[0-9]+", ""))
                        .toList());
    }

    @Test
    void latestRefusesABuildSignedForAnotherBranch() throws Exception {
        String version = "0.1.0-b142-experimental-bbbbbbb";
        publish(142, "experimental", jar("b142.jar", version, "next"), version, manifest -> {}, signer);
        releases.get(0).getAsJsonObject().addProperty("name", "build-142 (main bbbbbbb)");
        assertEquals(
                "update refused: b142 is signed for experimental, not main",
                updates()
                        .latest(engine, "main", Runnable::run)
                        .toCompletableFuture()
                        .get(20, TimeUnit.SECONDS));
        assertUnchanged();
    }

    @Test
    void listsBuildsAndMarksTheRunningOne() throws Exception {
        publish(141, manifest -> {});
        publish(142, manifest -> {});
        var other = new JsonObject();
        other.addProperty("tag_name", "v1.0");
        other.addProperty("name", "Release 1.0");
        releases.add(other);
        var lines = updates()
                .builds(null, engine.currentBuild(), Runnable::run)
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
        assertEquals(
                List.of("b142  main  bbbbbbb  2026-10-17 20:02", "b141  main  bbbbbbb  2026-10-17 20:01  (running)"),
                lines);
        assertEquals(
                List.of("No builds of dev among the newest 100 releases of owner/repo"),
                updates()
                        .builds("dev", engine.currentBuild(), Runnable::run)
                        .toCompletableFuture()
                        .get(20, TimeUnit.SECONDS));
    }

    @Test
    void theStateFileRoundTrips() throws Exception {
        Path file = directory.resolve("nested/update-state.json");
        assertNull(UpdateState.read(file));
        UpdateState.write(file, 142, BUNDLED);
        assertEquals("{\"build\":142,\"bundled\":\"" + BUNDLED + "\"}\n", Files.readString(file));
        assertEquals(new UpdateState.Entry(142, BUNDLED), UpdateState.read(file));
        UpdateState.write(file, 7, "");
        assertEquals(new UpdateState.Entry(7, ""), UpdateState.read(file));
        Files.writeString(file, "{\"build\":9}");
        assertEquals(new UpdateState.Entry(9, ""), UpdateState.read(file));
        Files.writeString(file, "{\"build\":0,\"bundled\":\"x\"}");
        assertNull(UpdateState.read(file));
        Files.writeString(file, "not json");
        assertNull(UpdateState.read(file));
        UpdateState.clear(file);
        assertFalse(Files.exists(file));
        UpdateState.clear(file);
        assertFalse(Files.exists(directory.resolve("nested/update-state.json.tmp")));
    }

    @Test
    void bootStartsTheRecordedBuild() throws Exception {
        publish(142, manifest -> {});
        UpdateState.write(directory.resolve("state.json"), 142, BUNDLED);
        var info = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        Path jar = boot(updates(), Duration.ofSeconds(10), info, warnings);
        assertNotNull(jar);
        assertEquals(List.of("Starting b142 (main bbbbbbb)"), info);
        assertEquals(List.of(), warnings);
        assertEquals(Files.size(directory.resolve("jars/b142.jar")), Files.size(jar));
    }

    @Test
    void bootFallsBackToTheBundledBuild() throws Exception {
        var info = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        Path state = directory.resolve("state.json");
        assertNull(boot(updates(), Duration.ofSeconds(5), info, warnings));
        assertEquals(List.of(), warnings);
        assertTrue(requests.isEmpty());

        UpdateState.write(state, 141, BUNDLED);
        assertNull(boot(updates(), Duration.ofSeconds(5), info, warnings));
        assertEquals(List.of(), warnings);

        UpdateState.write(state, 142, BUNDLED);
        String version = "0.1.0-b142-main-bbbbbbb";
        publish(
                142,
                "main",
                jar("b142.jar", version, "next"),
                version,
                manifest -> {},
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertNull(boot(updates(), Duration.ofSeconds(5), info, warnings));
        assertEquals(
                List.of("Starting the bundled build b141: b142 was refused: the manifest signature does not verify"),
                warnings);

        warnings.clear();
        publish(143, manifest -> manifest.addProperty("loader_version", 3));
        UpdateState.write(state, 143, BUNDLED);
        assertNull(boot(updates(), Duration.ofSeconds(5), info, warnings));
        assertEquals(
                List.of("Starting the bundled build b141: b143 needs a proxy restart with its own jar (loader 2 → 3)"),
                warnings);

        warnings.clear();
        var manifest = routes.get(releasePath(143, "manifest.json"));
        routes.put(releasePath(143, "manifest.json"), exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            manifest.accept(exchange);
        });
        assertNull(boot(updates(), Duration.ofMillis(300), info, warnings));
        assertEquals(List.of("Starting the bundled build b141: b143 did not install within 0 s"), warnings);
        assertEquals(List.of(), info);
    }

    @Test
    void aRollbackPersistsAcrossARestartWithTheSameBundledJar() throws Exception {
        publish(140, manifest -> {});
        engine.attach("player", null);
        assertEquals("b141 → b140 (main bbbbbbb), 1 players draining", install(updates(), 140));
        assertEquals(new UpdateState.Entry(140, BUNDLED), UpdateState.read(directory.resolve("state.json")));
        var info = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        assertNotNull(boot(updates(), Duration.ofSeconds(10), info, warnings));
        assertEquals(List.of("Starting b140 (main bbbbbbb)"), info);
        assertEquals(List.of(), warnings);
    }

    @Test
    void aNewBundledJarSupersedesTheRecordedBuild() throws Exception {
        publish(140, manifest -> {});
        Path state = directory.resolve("state.json");
        UpdateState.write(state, 140, BUNDLED);
        var info = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        assertNull(
                boot(updates("0.1.0-b150-main-ccccccc", Duration.ofSeconds(5)), Duration.ofSeconds(5), info, warnings));
        assertEquals(List.of("bundled jar changed (b141 → b150); ignoring recorded b140"), warnings);
        assertEquals(List.of(), info);
        assertFalse(Files.exists(state));
        assertTrue(requests.isEmpty());

        warnings.clear();
        UpdateState.write(state, 150, BUNDLED);
        assertNull(
                boot(updates("0.1.0-b150-main-ccccccc", Duration.ofSeconds(5)), Duration.ofSeconds(5), info, warnings));
        assertEquals(List.of(), warnings);
        assertTrue(requests.isEmpty());
    }

    @Test
    void bootRemovesLeftoverDownloads() throws Exception {
        Path updates = engine.updates();
        Files.createDirectories(updates);
        Path part = Files.writeString(updates.resolve("build-142.jar.part"), "partial");
        Path download = Files.writeString(updates.resolve("build-142-0123456789ab.jar"), "stale");
        Path staged = Files.writeString(updates.resolve("CultAC-velocity.jar"), "staged");
        Path other = Files.writeString(updates.resolve("build-notes.jar"), "kept");
        var warnings = new ArrayList<String>();
        assertNull(boot(updates(), Duration.ofSeconds(5), new ArrayList<>(), warnings));
        assertFalse(Files.exists(part));
        assertFalse(Files.exists(download));
        assertTrue(Files.exists(staged));
        assertTrue(Files.exists(other));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anUnexpectedFailureIsStillARefusal() throws Exception {
        String line = updates()
                .install(null, 142, Runnable::run)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(line.startsWith("update refused: java.lang.NullPointerException"), line);
        publish(142, manifest -> {});
        assertEquals("b141 → b142 (main bbbbbbb), 0 players draining", install(updates(), 142));
    }

    @Test
    void aLocalJarWaitsForTheRunningBuildInstall() throws Exception {
        publish(142, manifest -> {});
        var started = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        controls.put("entered:start:next", started);
        controls.put("start:next", release);
        var numbered =
                updates().install(engine, 142, task -> Thread.ofPlatform().start(task));
        started.get(10, TimeUnit.SECONDS);
        Files.copy(
                jar("local.jar", "0.1.0-b140-main-ccccccc", "local"),
                engine.updates().resolve("local.jar"));
        Path state = directory.resolve("state.json");
        assertEquals(
                "update refused: another update is running",
                BuildUpdates.installFile(engine, "local.jar", state, Runnable::run)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
        release.complete(null);
        assertTrue(numbered.toCompletableFuture().get(20, TimeUnit.SECONDS).contains(" → b142"));
        assertEquals(new UpdateState.Entry(142, BUNDLED), UpdateState.read(state));
        String line = BuildUpdates.installFile(engine, "local.jar", state, Runnable::run)
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);
        assertTrue(line.startsWith("Activated 0.1.0-b140-main-ccccccc"), line);
        assertNull(UpdateState.read(state));
    }

    @Test
    void aLateActivationRecordsTheBuildDespiteTheOuterTimeout() throws Exception {
        publish(142, manifest -> {});
        var release = new CompletableFuture<Void>();
        controls.put("start:next", release);
        var workers = new CopyOnWriteArrayList<Thread>();
        Executor executor = task -> workers.add(Thread.ofPlatform().start(task));
        var updates = updates();
        updates.updateLimit(Duration.ofSeconds(2));
        String line =
                updates.install(engine, 142, executor).toCompletableFuture().get(20, TimeUnit.SECONDS);
        assertEquals("update timed out after 2 s; it may still activate later", line);
        assertEquals("already on b141", install(updates, 141));
        release.complete(null);
        for (var worker : workers) assertTrue(worker.join(Duration.ofSeconds(20)));
        assertEquals("0.1.0-b142-main-bbbbbbb", engine.currentBuild());
        assertEquals(new UpdateState.Entry(142, BUNDLED), UpdateState.read(directory.resolve("state.json")));
    }

    @Test
    void thePublishedBuildIsRecordedBeforeItsPredecessorStepsAside() throws Exception {
        publish(142, manifest -> {});
        var release = new CompletableFuture<Void>();
        var entered = new CompletableFuture<Void>();
        controls.put("superseded:initial", release);
        controls.put("entered:superseded:initial", entered);
        var worker = new CompletableFuture<Thread>();
        var updates = updates();
        updates.updateLimit(Duration.ofSeconds(1));
        engine.lifecycleTimeout(Duration.ofSeconds(2));
        try {
            var result = updates.install(
                    engine, 142, task -> worker.complete(Thread.ofPlatform().start(task)));
            entered.get(10, TimeUnit.SECONDS);
            assertEquals("0.1.0-b142-main-bbbbbbb", engine.currentBuild());
            assertEquals(new UpdateState.Entry(142, BUNDLED), UpdateState.read(directory.resolve("state.json")));
            assertEquals(
                    "update timed out after 1 s; it may still activate later",
                    result.toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            release.complete(null);
            assertTrue(worker.get(10, TimeUnit.SECONDS).join(Duration.ofSeconds(10)));
        }
    }

    @Test
    void aLocalJarThatActivatesAfterItsTimeoutClearsTheOldBuildSelection() throws Exception {
        publish(142, manifest -> {});
        install(updates(), 142);
        Files.copy(
                jar("local.jar", "0.1.0-b140-main-ccccccc", "local"),
                engine.updates().resolve("local.jar"));
        var release = new CompletableFuture<Void>();
        var entered = new CompletableFuture<Void>();
        var worker = new CompletableFuture<Thread>();
        controls.put("start:local", release);
        controls.put("entered:start:local", entered);
        Path state = directory.resolve("state.json");
        try {
            var result = BuildUpdates.installFile(
                    engine,
                    "local.jar",
                    state,
                    task -> worker.complete(Thread.ofPlatform().start(task)),
                    Duration.ofMillis(300));
            entered.get(10, TimeUnit.SECONDS);
            assertTrue(result.toCompletableFuture().get(5, TimeUnit.SECONDS).startsWith("update timed out"));
            assertEquals(new UpdateState.Entry(142, BUNDLED), UpdateState.read(state));
            release.complete(null);
            assertTrue(worker.get(10, TimeUnit.SECONDS).join(Duration.ofSeconds(10)));
            assertEquals("0.1.0-b140-main-ccccccc", engine.currentBuild());
            assertNull(UpdateState.read(state));
        } finally {
            release.complete(null);
        }
    }

    @Test
    void aBootBuildThatFailsToLoadFallsBackToTheBundledRuntime() throws Exception {
        engine.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var reports = new CopyOnWriteArrayList<String>();
        var restarted = new GenerationManager(
                directory.resolve("Restarted"),
                "paper",
                this,
                controls,
                null,
                getClass().getClassLoader(),
                reports::add);
        Path broken = Files.writeString(directory.resolve("broken.jar"), "not a jar");
        restarted.loadInitial(Files.newInputStream(jar("bundled.jar", "0.1.0-b141-main-aaaaaaa", "initial")), broken);
        restarted.startInitial();
        assertEquals("0.1.0-b141-main-aaaaaaa", restarted.currentBuild());
        assertFalse(Files.exists(broken));
        assertTrue(
                reports.stream().anyMatch(line -> line.startsWith("Starting the bundled CultAC runtime;")),
                reports.toString());
        restarted.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        engine = restarted;
    }

    @Test
    void aBootBuildThatFailsToStartFallsBackToTheBundledRuntime() throws Exception {
        engine.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var reports = new CopyOnWriteArrayList<String>();
        var restarted = new GenerationManager(
                directory.resolve("Restarted"),
                "paper",
                this,
                controls,
                null,
                getClass().getClassLoader(),
                reports::add);
        Path bootBuild = Files.copy(
                jar("b142.jar", "0.1.0-b142-main-bbbbbbb", "broken"), directory.resolve("build-142-0123456789ab.jar"));
        restarted.loadInitial(Files.newInputStream(jar("bundled.jar", BUNDLED, "initial")), bootBuild);
        restarted.startInitial();
        assertEquals(BUNDLED, restarted.currentBuild());
        assertFalse(Files.exists(bootBuild));
        assertTrue(
                reports.stream().anyMatch(line -> line.startsWith("Starting the bundled CultAC runtime; 0.1.0-b142")),
                reports.toString());
        restarted.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(events.contains("broken:close"), events.toString());
        engine = restarted;
    }

    @Test
    void aBootBuildStartsInPlaceOfTheBundledRuntime() throws Exception {
        engine.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var reports = new CopyOnWriteArrayList<String>();
        var restarted = new GenerationManager(
                directory.resolve("Restarted"),
                "paper",
                this,
                controls,
                null,
                getClass().getClassLoader(),
                reports::add);
        Path bootBuild = Files.copy(
                jar("b142.jar", "0.1.0-b142-main-bbbbbbb", "next"), directory.resolve("build-142-0123456789ab.jar"));
        restarted.loadInitial(Files.newInputStream(jar("bundled.jar", BUNDLED, "initial")), bootBuild);
        restarted.startInitial();
        assertEquals("0.1.0-b142-main-bbbbbbb", restarted.currentBuild());
        assertFalse(Files.exists(bootBuild));
        assertTrue(
                reports.stream().noneMatch(line -> line.startsWith("Starting the bundled CultAC runtime;")),
                reports.toString());
        restarted.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        engine = restarted;
    }

    @Test
    void anUnusableStateFileFallsBackToTheDefault() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "update:\n  state-file: \"bad\\0name\"\n");
        assertEquals(
                directory.resolve("update-state.json"),
                UpdateSettings.read(directory, "CultAC/CultAC", name -> null).stateFile());
        Files.writeString(directory.resolve("config.yml"), "update: [not, a, section]\n");
        assertEquals(
                new UpdateSettings(
                        "CultAC/CultAC",
                        "",
                        UpdateSettings.OFFICIAL_PUBLIC_KEY,
                        "",
                        directory.resolve("update-state.json"),
                        0),
                UpdateSettings.read(directory, "CultAC/CultAC", name -> null));
    }

    @Test
    void theEnvironmentWinsOverEachConfigKey() throws Exception {
        Files.writeString(
                directory.resolve("config.yml"),
                "update:\n  repository: someone/fork\n  public-key: config-key\n  state-file: config.json\n"
                        + "  build: 120\n  token: config-token\n  webhook: https://config\n");
        var env = Map.of(
                "CULTAC_UPDATE_REPOSITORY", "env/repo",
                "CULTAC_UPDATE_PUBKEY", "env-key",
                "CULTAC_UPDATE_STATE", "env.json",
                "CULTAC_UPDATE_BUILD", "130",
                "CULTAC_UPDATE_TOKEN", "env-token",
                "CULTAC_UPDATE_WEBHOOK", "https://env");
        assertEquals(
                new UpdateSettings(
                        "env/repo", "env-token", "env-key", "https://env", directory.resolve("env.json"), 130),
                UpdateSettings.read(directory, "CultAC/CultAC", env::get));
        assertEquals(
                new UpdateSettings(
                        "someone/fork",
                        "config-token",
                        "config-key",
                        "https://config",
                        directory.resolve("config.json"),
                        120),
                UpdateSettings.read(directory, "CultAC/CultAC", name -> " "));
    }

    @Test
    void readsSettingsWithEnvironmentOverrides() throws Exception {
        Files.writeString(
                directory.resolve("config.yml"),
                "update:\n  repository: someone/fork\n  build: 120\n  state-file: state/x.json\n  webhook: https://w\n");
        var env = Map.of("CULTAC_UPDATE_REPOSITORY", "env/repo", "CULTAC_UPDATE_TOKEN", "t");
        assertEquals(
                new UpdateSettings(
                        "env/repo",
                        "t",
                        UpdateSettings.OFFICIAL_PUBLIC_KEY,
                        "https://w",
                        directory.resolve("state/x.json"),
                        120),
                UpdateSettings.read(directory, "CultAC/CultAC", env::get));
        assertEquals(
                new UpdateSettings(
                        "CultAC/CultAC",
                        "",
                        UpdateSettings.OFFICIAL_PUBLIC_KEY,
                        "",
                        directory.resolve("missing/update-state.json"),
                        0),
                UpdateSettings.read(directory.resolve("missing"), "CultAC/CultAC", name -> null));
        assertNotNull(BuildUpdates.publicKey(UpdateSettings.OFFICIAL_PUBLIC_KEY));
    }

    @Test
    void readsTheBuildNumberOfAVersion() {
        assertEquals(142, BuildUpdates.buildNumber("0.1.0-b142-main-abc1234"));
        assertEquals(7, BuildUpdates.buildNumber("0.1.0-b7-release_1.2-abc1234"));
        assertEquals(0, BuildUpdates.buildNumber("0.1.0-abc1234"));
        assertEquals(0, BuildUpdates.buildNumber("0.1.0-feature-b-abc1234"));
        assertEquals(0, BuildUpdates.buildNumber(null));
    }

    static void respond(HttpExchange exchange, int status, byte[] body) {
        try (exchange) {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
        }
    }

    Path jar(String filename, String version, String variant) throws Exception {
        Path path = directory.resolve("jars").resolve(filename);
        Files.createDirectories(path.getParent());
        var manifest = new Manifest();
        var fields = manifest.getMainAttributes();
        fields.putValue("Manifest-Version", "1.0");
        fields.putValue("Cult-Runtime-SPI", "1");
        fields.putValue("Cult-Runtime-Platform", "paper");
        fields.putValue("Cult-Runtime-Java", "21");
        fields.putValue("Cult-Runtime-Entrypoint", FixtureEntrypoint.class.getName());
        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            String type = FixtureEntrypoint.class.getName().replace('.', '/') + ".class";
            out.putNextEntry(new JarEntry(type));
            try (var input = getClass().getClassLoader().getResourceAsStream(type)) {
                input.transferTo(out);
            }
            out.closeEntry();
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: CultAC\nversion: '" + version + "'\ncult-loader-version: '2'\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("fixture.properties"));
            out.write(("variant=" + variant).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }
}
