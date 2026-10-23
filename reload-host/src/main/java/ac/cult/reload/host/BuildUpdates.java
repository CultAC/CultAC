package ac.cult.reload.host;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code /cult update <build>}, {@code latest} and {@code builds}: installs the numbered builds published as GitHub
 * releases {@code build-<N>}. Each release carries {@code manifest.json} and its Ed25519 signature; nothing is installed
 * unless the signature, the jar's size and sha256 and the loader version all hold. Every request has a deadline and a
 * size cap, and downloads only follow redirects to GitHub's release-asset hosts.
 */
public final class BuildUpdates {
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    static final int MAX_SIGNATURE_BYTES = 1024;
    static final int MAX_LISTING_BYTES = 4 * 1024 * 1024;
    static final long MAX_JAR_BYTES = 512L * 1024 * 1024;
    /** One request lists this many releases; the anonymous API allows 60 requests an hour. */
    static final int LISTED_RELEASES = 100;

    private static final int LISTED_LINES = 15;
    private static final int MAX_REDIRECTS = 3;
    private static final Pattern BUILD_NUMBER = Pattern.compile("-b([0-9]{1,18})-");
    private static final Pattern REPOSITORY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?!\\.\\.?$)[A-Za-z0-9_.-]{1,100}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,200}");
    private static final Pattern RELEASE_NAME =
            Pattern.compile("build-([0-9]{1,18}) \\(([A-Za-z0-9._/-]{1,200}) ([0-9a-f]{7,40})\\)");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    private static final Pattern JAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.jar");
    private static final Pattern DOWNLOAD = Pattern.compile("build-[0-9]{1,18}(-[0-9a-f]{12}\\.jar|\\.jar\\.part)");
    /** The update holding the one-at-a-time guard of this loader; null when none runs. */
    private static final AtomicReference<Object> RUNNING = new AtomicReference<>();
    /**
     * Past every bounded step of an install (requests, download, runtime load and start), an update gives the guard
     * up, so one hung in the runtime cannot block every later update.
     */
    static final Duration UPDATE_LIMIT = Duration.ofMinutes(10);

    /**
     * Where builds come from.
     *
     * @param downloads release downloads, {@code <downloads>/<owner>/<repo>/releases/download/<tag>/<file>}
     * @param api the REST API; the only origin the update token is sent to
     * @param downloadHosts the hosts a download may be served from, redirects included
     */
    record Endpoints(URI downloads, URI api, Set<String> downloadHosts) {
        /** github.com answers release downloads with a redirect to its release-asset host. */
        static final Set<String> GITHUB_DOWNLOAD_HOSTS = Set.of("github.com", "release-assets.githubusercontent.com");

        static final Endpoints GITHUB = new Endpoints(
                URI.create("https://github.com"), URI.create("https://api.github.com"), GITHUB_DOWNLOAD_HOSTS);
    }

    /** A signed build, as its manifest describes it. */
    record Build(
            long number,
            String branch,
            String version,
            String commit,
            String jar,
            String sha256,
            long size,
            String loaderVersion) {}

    /** A build release, as the release list names it. */
    record Listed(long number, String branch, String commit, String publishedAt) {}

    /** A refusal, with the one line an operator sees. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message, null, false, false);
        }
    }

    private final UpdateSettings settings;
    private final String bundledVersion;
    private final Endpoints endpoints;
    private final HttpClient http;
    private final Duration requestTimeout, downloadTimeout;
    private Duration updateLimit = UPDATE_LIMIT;

    /** @param bundledVersion the version of the runtime the installed plugin jar bundles */
    public BuildUpdates(UpdateSettings settings, String bundledVersion) {
        this(
                settings,
                bundledVersion,
                Endpoints.GITHUB,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofMinutes(2));
    }

    BuildUpdates(
            UpdateSettings settings,
            String bundledVersion,
            Endpoints endpoints,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration downloadTimeout) {
        this.settings = settings;
        this.bundledVersion = bundledVersion == null ? "" : bundledVersion;
        this.endpoints = endpoints;
        this.requestTimeout = requestTimeout;
        this.downloadTimeout = downloadTimeout;
        http = HttpClients.create(connectTimeout);
    }

    void updateLimit(Duration limit) {
        updateLimit = limit;
    }

    /** The build number a version names, {@code 0.1.0-b142-stable-abc1234}; 0 for an unnumbered version. */
    public static long buildNumber(String version) {
        if (version == null) return 0;
        var matcher = BUILD_NUMBER.matcher(version);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    /** The version a native plugin descriptor declares. */
    public static String descriptorVersion(InputStream descriptor, String platform) throws IOException {
        try (descriptor) {
            if (descriptor == null) throw new IOException("Missing native plugin descriptor");
            return PluginDescriptors.version(descriptor.readAllBytes(), platform);
        }
    }

    /**
     * Installs build {@code number} on {@code executor}. Completes with one line: the swap, {@code already on},
     * {@code needs a proxy restart} or why it was refused.
     */
    public CompletionStage<String> install(GenerationManager engine, long number, Executor executor) {
        return exclusive(() -> installBuild(engine, number, null), executor, updateLimit);
    }

    /** Installs the newest build of {@code branch}, as {@link #install} does. */
    public CompletionStage<String> latest(GenerationManager engine, String branch, Executor executor) {
        return exclusive(
                () -> {
                    try {
                        var newest = list(branch).stream().findFirst();
                        if (newest.isEmpty())
                            return "update refused: no build of " + branch + " among the newest " + LISTED_RELEASES
                                    + " releases of " + settings.repository();
                        return installBuild(engine, newest.get().number(), branch);
                    } catch (Refused refused) {
                        return "update refused: " + refused.getMessage();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return "update refused: interrupted";
                    }
                },
                executor,
                updateLimit);
    }

    /**
     * {@code /cult update <file.jar>}: activates a jar from the updates directory under the same one-at-a-time guard as
     * build installs. Completes with one line: the activation or why it was refused.
     *
     * @param stateFile the update state to clear once the jar is active, so the next boot keeps it; null to leave it
     */
    public static CompletionStage<String> installFile(
            GenerationManager engine, String filename, Path stateFile, Executor executor) {
        return installFile(engine, filename, stateFile, executor, UPDATE_LIMIT);
    }

    static CompletionStage<String> installFile(
            GenerationManager engine, String filename, Path stateFile, Executor executor, Duration limit) {
        return exclusive(
                () -> {
                    try {
                        return engine.update(filename, () -> {
                                    if (stateFile == null) return "";
                                    try {
                                        UpdateState.clear(stateFile);
                                        return "";
                                    } catch (IOException | RuntimeException failure) {
                                        return "; the next boot may start the last installed build instead: " + failure;
                                    }
                                })
                                .toCompletableFuture()
                                .join();
                    } catch (CompletionException | CancellationException failure) {
                        return "update refused: " + cause(failure).getMessage();
                    }
                },
                executor,
                limit);
    }

    /** The newest builds, of {@code branch} or of every branch when null, the running one marked. */
    public CompletionStage<List<String>> builds(String branch, String runningVersion, Executor executor) {
        try {
            var listing = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            var listed = list(branch);
                            if (listed.isEmpty())
                                return List.of(
                                        "No builds" + (branch == null ? "" : " of " + branch) + " among the newest "
                                                + LISTED_RELEASES + " releases of " + settings.repository());
                            long running = buildNumber(runningVersion);
                            var lines = new ArrayList<String>();
                            for (var build : listed.subList(0, Math.min(LISTED_LINES, listed.size())))
                                lines.add("b" + build.number() + "  " + build.branch() + "  "
                                        + build.commit().substring(0, 7) + "  " + build.publishedAt()
                                        + (build.number() == running ? "  (running)" : ""));
                            return List.copyOf(lines);
                        } catch (Refused refused) {
                            return List.of("Cannot list builds: " + refused.getMessage());
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return List.of("Cannot list builds: interrupted");
                        }
                    },
                    executor);
            return listing.exceptionally(failure -> List.of("Cannot list builds: " + cause(failure)));
        } catch (RuntimeException rejected) {
            return CompletableFuture.completedFuture(List.of("Cannot list builds: " + rejected));
        }
    }

    /**
     * Before the first runtime starts: removes leftover downloads, then downloads the build the state file names, else
     * {@link UpdateSettings#pinnedBuild}, unless it is the bundled build or needs another loader. A state file recorded
     * with another bundled jar is cleared, so deploying a new jar supersedes an earlier {@code /cult update}. Gives up
     * after {@code limit}, so a slow network only delays startup by that much; any failure is one warning.
     *
     * @return the verified jar for {@link GenerationManager#loadInitial(InputStream, Path)}; null to start the bundled
     *     runtime
     */
    public Path boot(String loaderVersion, Path updates, Duration limit, Consumer<String> info, Consumer<String> warn) {
        removeDownloads(updates, warn);
        var recorded = UpdateState.read(settings.stateFile());
        long number = recorded == null ? settings.pinnedBuild() : recorded.build();
        if (recorded != null
                && number != buildNumber(bundledVersion)
                && !recorded.bundled().isEmpty()
                && !recorded.bundled().equals(bundledVersion)) {
            warn.accept("bundled jar changed (" + label(recorded.bundled()) + " → " + label(bundledVersion)
                    + "); ignoring recorded b" + number);
            try {
                UpdateState.clear(settings.stateFile());
            } catch (IOException failure) {
                warn.accept("Cannot clear " + settings.stateFile() + ": " + failure);
            }
            return null;
        }
        if (number == 0 || number == buildNumber(bundledVersion)) return null;
        String fallback = "Starting the bundled build " + label(bundledVersion) + ": b" + number + " ";
        var attempt = new FutureTask<Path>(() -> {
            try {
                Build build = fetch(number);
                if (build.version().equals(bundledVersion)) return null;
                if (!build.loaderVersion().equals(loaderVersion)) {
                    warn.accept(fallback + "needs a proxy restart with its own jar (loader " + loaderVersion + " → "
                            + build.loaderVersion() + ")");
                    return null;
                }
                Files.createDirectories(updates);
                Path jar = download(build, updates);
                if (Thread.currentThread().isInterrupted()) {
                    Files.deleteIfExists(jar);
                    return null;
                }
                info.accept("Starting b" + number + " (" + build.branch() + " "
                        + build.commit().substring(0, 7) + ")");
                return jar;
            } catch (Refused refused) {
                warn.accept(fallback + "was refused: " + refused.getMessage());
                return null;
            }
        });
        Thread.ofPlatform().daemon().name("cult-update-boot").start(attempt);
        try {
            return attempt.get(limit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            attempt.cancel(true);
            warn.accept(fallback + "did not install within " + limit.toSeconds() + " s");
        } catch (InterruptedException interrupted) {
            attempt.cancel(true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            warn.accept(fallback + "failed: " + failure.getCause());
        }
        return null;
    }

    /** Deletes the {@code build-*} downloads an interrupted install left behind; nothing runs from them yet. */
    private static void removeDownloads(Path updates, Consumer<String> warn) {
        if (!Files.isDirectory(updates)) return;
        try (var files = Files.list(updates)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (DOWNLOAD.matcher(file.getFileName().toString()).matches()) Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException failure) {
            warn.accept("Cannot remove old downloads from " + updates + ": " + failure);
        }
    }

    /**
     * Runs {@code task} unless another update holds the guard. Past {@code limit} the update is answered as timed out
     * and gives the guard up. Boot selections are recorded by the manager at publication, including late activations.
     */
    private static CompletionStage<String> exclusive(Supplier<String> task, Executor executor, Duration limit) {
        var token = new Object();
        if (!RUNNING.compareAndSet(null, token))
            return CompletableFuture.completedFuture("update refused: another update is running");
        try {
            return CompletableFuture.supplyAsync(task, executor)
                    .orTimeout(limit.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(failure -> cause(failure) instanceof TimeoutException
                            ? "update timed out after " + limit.toSeconds() + " s; it may still activate later"
                            : "update refused: " + cause(failure))
                    .whenComplete((line, failure) -> RUNNING.compareAndSet(token, null));
        } catch (RuntimeException rejected) {
            RUNNING.compareAndSet(token, null);
            return CompletableFuture.completedFuture("update refused: " + rejected);
        }
    }

    /** @param branch the branch the build must be signed for; null for any */
    private String installBuild(GenerationManager engine, long number, String branch) {
        String running = engine.currentBuild();
        if (buildNumber(running) == number) {
            String note = engine.recordSelection(running, () -> recordBuild(number));
            if (note != null) return "already on b" + number + note;
        }
        Path jar = null;
        try {
            Build build = fetch(number);
            if (branch != null && !build.branch().equals(branch))
                throw new Refused("b" + number + " is signed for " + build.branch() + ", not " + branch);
            if (build.version().equals(running)) {
                String note = engine.recordSelection(running, () -> recordBuild(number));
                if (note != null) return "already on b" + number + note;
            }
            if (!build.loaderVersion().equals(engine.loaderVersion()))
                return "b" + number + " needs a proxy restart (loader " + engine.loaderVersion() + " → "
                        + build.loaderVersion() + ")";
            Files.createDirectories(engine.updates());
            jar = download(build, engine.updates());
            String result;
            var selectionNote = new AtomicReference<>("");
            try {
                result = engine.update(jar.getFileName().toString(), () -> {
                            String note = recordBuild(number);
                            selectionNote.set(note);
                            return note;
                        })
                        .toCompletableFuture()
                        .join();
            } catch (CompletionException | CancellationException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                return "update refused: the runtime did not activate: " + cause.getMessage();
            }
            String line = result.startsWith("Already running")
                    ? "already on b" + number
                    : label(running) + " → b" + number + " (" + build.branch() + " "
                            + build.commit().substring(0, 7) + "), " + engine.drainingSessions() + " players draining";
            return line + selectionNote.get();
        } catch (Refused refused) {
            return "update refused: " + refused.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "update refused: interrupted";
        } catch (IOException | RuntimeException failure) {
            return "update refused: " + failure;
        } finally {
            if (jar != null) {
                try {
                    Files.deleteIfExists(jar);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String recordBuild(long number) {
        try {
            UpdateState.write(settings.stateFile(), number, bundledVersion);
            return "";
        } catch (IOException | RuntimeException failure) {
            return "; the next boot does not know it: " + failure;
        }
    }

    private static Throwable cause(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static String label(String version) {
        long number = buildNumber(version);
        return number > 0 ? "b" + number : version == null ? "none" : version;
    }

    /** Fetches build {@code number}'s manifest and signature, and checks the signature before reading a field. */
    Build fetch(long number) throws Refused, InterruptedException {
        PublicKey key = publicKey(settings.publicKey());
        URI manifestUri = download(number, "manifest.json");
        byte[] manifest = fetch(manifestUri, MAX_MANIFEST_BYTES, "manifest");
        byte[] signature = fetch(download(number, "manifest.json.sig"), MAX_SIGNATURE_BYTES, "manifest signature");
        verify(key, manifest, signature);
        Build build = parse(manifest);
        if (build.number() != number)
            throw new Refused("the manifest of build-" + number + " is signed for b" + build.number());
        return build;
    }

    /**
     * Downloads {@code build}'s jar into {@code directory}, checking its size and sha256 as it arrives.
     *
     * @return the verified jar; the caller deletes it
     */
    Path download(Build build, Path directory) throws Refused, InterruptedException, IOException {
        Path partial = directory.resolve("build-" + build.number() + ".jar.part");
        Path jar = directory.resolve(
                "build-" + build.number() + "-" + build.sha256().substring(0, 12) + ".jar");
        try {
            MessageDigest digest = sha256();
            long received;
            try (var out = Files.newOutputStream(partial)) {
                received = get(
                        download(build.number(), build.jar()),
                        build.size(),
                        new DigestingStream(out, digest),
                        downloadTimeout,
                        "jar");
            }
            if (received != build.size())
                throw new Refused(
                        received > build.size()
                                ? "the jar is larger than the manifest's " + build.size() + " bytes"
                                : "the jar is " + received + " bytes, the manifest says " + build.size());
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(build.sha256()))
                throw new Refused("the jar's sha256 " + actual.substring(0, 12) + " does not match the manifest's "
                        + build.sha256().substring(0, 12));
            Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return jar;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** One request: the newest build releases, of {@code branch} or of every branch when null, newest first. */
    List<Listed> list(String branch) throws Refused, InterruptedException {
        URI uri = URI.create(endpoints.api() + "/repos/" + repository() + "/releases?per_page=" + LISTED_RELEASES);
        byte[] body = fetch(uri, MAX_LISTING_BYTES, "release list");
        var listed = new ArrayList<Listed>();
        try {
            for (JsonElement element : JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonArray()) {
                JsonObject release = element.getAsJsonObject();
                if (release.has("draft") && release.get("draft").getAsBoolean()) continue;
                String name = text(release, "name");
                var matcher = RELEASE_NAME.matcher(name);
                if (!matcher.matches() || !text(release, "tag_name").equals("build-" + matcher.group(1))) continue;
                if (branch != null && !matcher.group(2).equals(branch)) continue;
                String published = text(release, "published_at");
                listed.add(new Listed(
                        Long.parseLong(matcher.group(1)),
                        matcher.group(2),
                        matcher.group(3),
                        published.length() >= 16
                                ? published.substring(0, 10) + " " + published.substring(11, 16)
                                : ""));
            }
        } catch (RuntimeException invalid) {
            throw new Refused("the release list is not what the GitHub API returns");
        }
        listed.sort(Comparator.comparingLong(Listed::number).reversed());
        return listed;
    }

    private static String text(JsonObject fields, String key) {
        JsonElement value = fields.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private String repository() throws Refused {
        if (!REPOSITORY.matcher(settings.repository()).matches())
            throw new Refused("update.repository " + settings.repository() + " is not owner/repo");
        return settings.repository();
    }

    private URI download(long number, String file) throws Refused {
        return URI.create(
                endpoints.downloads() + "/" + repository() + "/releases/download/build-" + number + "/" + file);
    }

    static PublicKey publicKey(String base64) throws Refused {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (IllegalArgumentException | GeneralSecurityException invalid) {
            throw new Refused("the configured update public key is not an Ed25519 key");
        }
    }

    static void verify(PublicKey key, byte[] manifest, byte[] signatureText) throws Refused {
        byte[] signature;
        try {
            signature = Base64.getDecoder()
                    .decode(new String(signatureText, StandardCharsets.US_ASCII).replaceAll("\\s", ""));
        } catch (IllegalArgumentException invalid) {
            throw new Refused("the manifest signature is not base64");
        }
        boolean valid;
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifest);
            valid = signature.length == 64 && verifier.verify(signature);
        } catch (GeneralSecurityException invalid) {
            valid = false;
        }
        if (!valid) throw new Refused("the manifest signature does not verify");
    }

    static Build parse(byte[] manifest) throws Refused {
        try {
            JsonObject fields = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            long number = fields.get("build").getAsLong();
            String branch = required(fields, "branch");
            String version = required(fields, "version");
            String commit = required(fields, "commit").toLowerCase(Locale.ROOT);
            String jar = required(fields, "jar");
            String sha256 = required(fields, "sha256").toLowerCase(Locale.ROOT);
            long size = fields.get("size").getAsLong();
            int loader = fields.get("loader_version").getAsInt();
            if (number <= 0) throw new Refused("the manifest's build number is invalid");
            if (!BRANCH.matcher(branch).matches()) throw new Refused("the manifest's branch is invalid");
            if (!VERSION.matcher(version).matches()) throw new Refused("the manifest's version is invalid");
            if (!COMMIT.matcher(commit).matches()) throw new Refused("the manifest's commit is invalid");
            if (!JAR.matcher(jar).matches()) throw new Refused("the manifest's jar name is invalid");
            if (!SHA256.matcher(sha256).matches()) throw new Refused("the manifest's sha256 is invalid");
            if (size <= 0 || size > MAX_JAR_BYTES)
                throw new Refused("the manifest's size " + size + " is out of range");
            return new Build(number, branch, version, commit, jar, sha256, size, Integer.toString(loader));
        } catch (Refused refused) {
            throw refused;
        } catch (RuntimeException invalid) {
            throw new Refused("the manifest is incomplete or not JSON");
        }
    }

    private static String required(JsonObject fields, String key) {
        String text = text(fields, key);
        if (text.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return text;
    }

    private byte[] fetch(URI uri, int maxBytes, String what) throws Refused, InterruptedException {
        var out = new ByteArrayOutputStream();
        long received = get(uri, maxBytes, out, requestTimeout, what);
        if (received > maxBytes) throw new Refused("the " + what + " is larger than " + maxBytes + " bytes");
        return out.toByteArray();
    }

    /** Whether a download may be fetched from {@code uri}: a release-asset host, on the download scheme and port. */
    boolean downloadAllowed(URI uri) {
        return uri.getScheme() != null
                && uri.getScheme().equalsIgnoreCase(endpoints.downloads().getScheme())
                && uri.getHost() != null
                && endpoints.downloadHosts().contains(uri.getHost().toLowerCase(Locale.ROOT))
                && port(uri) == port(endpoints.downloads());
    }

    /**
     * GETs {@code uri} into {@code out} within {@code timeout}. An API request follows redirects on the API's origin
     * and carries the token; a download follows redirects among the release-asset hosts and carries none. Stops reading
     * past {@code maxBytes}.
     *
     * @return the bytes received, at most {@code maxBytes + 1}
     */
    private long get(URI uri, long maxBytes, OutputStream out, Duration timeout, String what)
            throws Refused, InterruptedException {
        boolean api = sameOrigin(uri, endpoints.api());
        if (!api && !downloadAllowed(uri)) throw new Refused("the " + what + " is not on a release-asset host");
        long deadline = System.nanoTime() + timeout.toNanos();
        URI at = uri;
        for (int hop = 0; ; hop++) {
            var request = HttpRequest.newBuilder(at).timeout(timeout).GET().header("User-Agent", "CultAC-updater");
            if (api) {
                request.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28");
                if (!settings.token().isEmpty()) request.header("Authorization", "Bearer " + settings.token());
            }
            var body = new CappedBody(out, maxBytes);
            var exchange = http.sendAsync(
                    request.build(),
                    info -> info.statusCode() == 200 ? body : HttpResponse.BodySubscribers.replacing(null));
            HttpResponse<?> response;
            try {
                response = exchange.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                exchange.cancel(true);
                throw interrupted;
            } catch (TimeoutException late) {
                exchange.cancel(true);
                throw new Refused(
                        "the " + what + " did not arrive within " + timeout.toSeconds() + " s from " + at.getHost());
            } catch (ExecutionException failure) {
                if (body.received > maxBytes) return body.received;
                throw new Refused(
                        "cannot fetch the " + what + " from " + at.getHost() + ": " + describe(failure.getCause()));
            }
            int status = response.statusCode();
            if (status == 200) return body.received;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                var location = response.headers().firstValue("location");
                if (location.isEmpty() || hop == MAX_REDIRECTS)
                    throw new Refused("the " + what + " redirects too often from " + at.getHost());
                URI next = at.resolve(location.get());
                if (api ? !sameOrigin(next, endpoints.api()) : !downloadAllowed(next))
                    throw new Refused(
                            "refused the " + what + "'s redirect from " + at.getHost() + " to " + next.getHost());
                at = next;
                continue;
            }
            if (status == 404) throw new Refused(at.getHost() + " has no " + what + " at " + uri.getPath());
            throw new Refused(at.getHost() + " answered HTTP " + status + " for the " + what);
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : " " + message);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Past its cap a body fails with this, and the request is cancelled. */
    private static final class Overflow extends IOException {
        Overflow() {
            super(null, null);
        }
    }

    /** Writes a body to a stream, failing once it passes {@code maxBytes}. */
    private static final class CappedBody implements HttpResponse.BodySubscriber<Void> {
        private final OutputStream out;
        private final long maxBytes;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private Flow.Subscription subscription;
        volatile long received;

        CappedBody(OutputStream out, long maxBytes) {
            this.out = out;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<Void> getBody() {
            return done;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int length = buffer.remaining();
                    if (received + length > maxBytes) {
                        received = maxBytes + 1;
                        subscription.cancel();
                        done.completeExceptionally(new Overflow());
                        return;
                    }
                    byte[] bytes = new byte[length];
                    buffer.get(bytes);
                    out.write(bytes);
                    received += length;
                }
                subscription.request(1);
            } catch (IOException failure) {
                subscription.cancel();
                done.completeExceptionally(failure);
            }
        }

        @Override
        public void onError(Throwable failure) {
            done.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            done.complete(null);
        }
    }

    private static final class DigestingStream extends OutputStream {
        private final OutputStream out;
        private final MessageDigest digest;

        DigestingStream(OutputStream out, MessageDigest digest) {
            this.out = out;
            this.digest = digest;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            digest.update(bytes, offset, length);
        }
    }
}
