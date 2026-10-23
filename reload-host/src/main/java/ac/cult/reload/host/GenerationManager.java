package ac.cult.reload.host;

import ac.cult.reload.api.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Admissions and publication share one lock; engine callbacks never execute under that lock. */
public final class GenerationManager implements AutoCloseable {
    private enum State {
        PREPARING,
        CURRENT,
        DRAINING,
        STOPPING,
        CLEANUP_FAILED,
        CLOSED
    }

    private final Path directory;
    private final String platform;
    private final String hostVersion;
    private final String loaderVersion;
    private final Object plugin, server, logger;
    private final ClassLoader parent;
    private final Consumer<String> report;
    private final ExecutorService management;
    private final Map<String, Generation> generations = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Session> sessions = new IdentityHashMap<>();
    private final IdentityHashMap<Object, UserBinding> users = new IdentityHashMap<>();
    private final Map<UUID, List<UserBinding>> usersByUuid = new HashMap<>();
    private final IdentityHashMap<Object, UserBinding> nativeUsers = new IdentityHashMap<>();
    private java.util.function.Function<Object, Object> upstream = ignored -> null;
    private volatile Runnable activated = () -> {};
    private long generationOrder, bindingOrder;
    private final Map<String, Object> shared = new LinkedHashMap<>();
    private final HostApiRouter api = new HostApiRouter(this);
    private Generation current;
    private String bundledDigest;
    private boolean initialBundled;
    private boolean closing;
    private final CompletableFuture<Void> shutdown = new CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.time.Duration lifecycleTimeout = java.time.Duration.ofMinutes(2);

    public void tick() {
        ticks.incrementAndGet();
    }

    public int currentTick() {
        return ticks.get();
    }

    public GenerationManager(
            Path directory,
            String platform,
            Object plugin,
            Object server,
            Object logger,
            ClassLoader parent,
            Consumer<String> report) {
        this(directory, platform, plugin, server, logger, parent, report, null);
    }

    public GenerationManager(
            Path directory,
            String platform,
            Object plugin,
            Object server,
            Object logger,
            ClassLoader parent,
            Consumer<String> report,
            String hostVersion) {
        this(directory, platform, plugin, server, logger, parent, report, hostVersion, "2");
    }

    public GenerationManager(
            Path directory,
            String platform,
            Object plugin,
            Object server,
            Object logger,
            ClassLoader parent,
            Consumer<String> report,
            String hostVersion,
            String loaderVersion) {
        this.loaderVersion = loaderVersion;
        this.hostVersion = hostVersion;
        this.directory = directory.toAbsolutePath().normalize();
        this.platform = platform;
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.parent = parent;
        this.report = report;
        management = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "cult-runtime-manager");
            thread.setDaemon(true);
            thread.setContextClassLoader(parent);
            return thread;
        });
    }

    // Late completions of timed-out lifecycle calls can arrive after shutdown stopped the manager.
    private void serial(Runnable task) {
        try {
            management.execute(task);
        } catch (RejectedExecutionException stopped) {
            task.run();
        }
    }

    /** Bounds hot load, start, supersede and stop calls; a call past it is fenced, never interrupted. */
    void lifecycleTimeout(java.time.Duration timeout) {
        lifecycleTimeout = Objects.requireNonNull(timeout);
    }

    /** Paper calls this during load, before Geyser snapshots its connection initializer. */
    public void loadInitial(InputStream embedded) throws Exception {
        loadInitial(embedded, null);
    }

    /**
     * @param bootBuild a verified build to start in place of the persisted or bundled runtime; deleted once read, and
     *     the others take over when it fails to load
     */
    public void loadInitial(InputStream embedded, Path bootBuild) throws Exception {
        Files.createDirectories(directory.resolve("updates"));
        Files.createDirectories(directory.resolve("runtime"));
        Path bundled = directory.resolve("runtime/default.jar");
        try (embedded) {
            if (embedded == null) throw new IOException("Bootstrap contains no default runtime");
            Files.copy(embedded, bundled, StandardCopyOption.REPLACE_EXISTING);
        }
        bundledDigest =
                RuntimeArtifact.inspect(bundled, platform, loaderVersion).digest();
        Generation generation = null;
        if (bootBuild != null) {
            try {
                generation = prepare(bootBuild, false);
            } catch (Exception failure) {
                report.accept("Starting the bundled CultAC runtime; the boot build failed to load: " + failure);
            } finally {
                try {
                    Files.deleteIfExists(bootBuild);
                } catch (IOException | RuntimeException cleanup) {
                    report.accept("Cannot delete the boot build " + bootBuild + ": " + cleanup);
                }
            }
        }
        Path marker = directory.resolve("runtime/current.properties");
        if (generation == null && Files.exists(marker)) {
            try {
                generation = loadPersisted(marker);
            } catch (Exception failure) {
                report.accept("Starting the bundled CultAC runtime; the persisted runtime failed to load: " + failure);
            }
        }
        if (generation == null) {
            generation = prepare(bundled, false);
            initialBundled = true;
        }
        synchronized (this) {
            current = generation;
        }
    }

    /** @return null when the marker belongs to another bundled runtime */
    private Generation loadPersisted(Path marker) throws Exception {
        var properties = new Properties();
        try (var input = Files.newInputStream(marker)) {
            properties.load(input);
        }
        if (!bundledDigest.equals(properties.getProperty("bundled"))) return null;
        Path selected = directory
                .resolve("runtime")
                .resolve(properties.getProperty("artifact"))
                .normalize();
        if (!selected.startsWith(directory.resolve("runtime"))) throw new IOException("Invalid active runtime path");
        var descriptor = RuntimeArtifact.inspect(selected, platform, loaderVersion);
        if (!descriptor.digest().equals(properties.getProperty("sha256")))
            throw new IOException("Active runtime digest changed");
        return prepare(selected, false);
    }

    public void startInitial() throws Exception {
        Generation generation;
        synchronized (this) {
            generation = Objects.requireNonNull(current, "Runtime not loaded");
        }
        try {
            startGeneration(generation);
        } catch (Throwable failure) {
            if (initialBundled) throw failure;
            // A boot build or persisted runtime that cannot start gives way to the bundled runtime.
            report.accept("Starting the bundled CultAC runtime; " + generation.artifact.display() + " failed to start: "
                    + failure);
            synchronized (this) {
                generation.state = State.DRAINING;
            }
            retire(generation);
            try {
                generation = prepare(directory.resolve("runtime/default.jar"), false);
                initialBundled = true;
                synchronized (this) {
                    current = generation;
                }
                startGeneration(generation);
            } catch (Throwable fallback) {
                fallback.addSuppressed(failure);
                throw fallback;
            }
        }
        synchronized (this) {
            generation.state = State.CURRENT;
            try (var homes = Files.list(directory.resolve("runtime/generations"))) {
                for (var home : homes.toList()) {
                    if (generations.containsKey(home.getFileName().toString())) continue;
                    try {
                        deleteHome(home);
                    } catch (IOException failure) {
                        report.accept(
                                "CultAC leftover runtime cleanup failed for " + home.getFileName() + ": " + failure);
                    }
                }
            } catch (IOException | UncheckedIOException failure) {
                report.accept("CultAC leftover runtime cleanup failed: " + failure);
            }
        }
    }

    private void startGeneration(Generation generation) throws Exception {
        // Platform startup runs on its own thread, which the engine may need; nothing older can take over anyway.
        invoke(generation, () -> {
            generation.runtime.start();
            return null;
        });
        try (var settings = new ConfigFiles(directory, generation.runtime.configurationFiles())) {
            persist(generation);
            settings.commit();
        }
    }

    public CompletionStage<String> update(String filename) {
        return update(filename, () -> "");
    }

    /** Records the boot selection with publication, even if the operator's request has already timed out. */
    CompletionStage<String> update(String filename, Supplier<String> recordSelection) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Path name = Path.of(filename);
                        if (name.isAbsolute() || name.getNameCount() != 1 || !filename.endsWith(".jar")) {
                            throw new IOException("Specify a jar filename in the updates directory");
                        }
                        Path root = directory.resolve("updates").toRealPath();
                        // A named staging entry may link to a development build outside this directory.
                        // prepare() snapshots it before publication; cleanup never follows symlinks.
                        Path input = root.resolve(name).toRealPath();
                        if (!Files.isRegularFile(input)) throw new IOException("Invalid update artifact");
                        synchronized (this) {
                            if (closing) throw new IllegalStateException("Host is stopping");
                        }
                        var next = prepare(input, true);
                        boolean duplicate;
                        synchronized (this) {
                            duplicate = current != null
                                    && current.artifact.identity().equals(next.artifact.identity());
                        }
                        if (duplicate) {
                            if (Files.exists(next.home.resolve("distribution.jar"))) {
                                Files.copy(
                                        next.home.resolve("distribution.jar"),
                                        current.home.resolve("distribution.jar"),
                                        StandardCopyOption.REPLACE_EXISTING);
                            }
                            String selectionNote;
                            synchronized (this) {
                                next.state = State.DRAINING;
                                selectionNote = selectionNote(recordSelection);
                            }
                            retire(next);
                            return "Already running " + next.artifact.display() + selectionNote;
                        }
                        try {
                            lifecycle(next, "start", () -> {
                                next.runtime.start();
                                return null;
                            });
                        } catch (Throwable failure) {
                            synchronized (this) {
                                next.state = State.DRAINING;
                            }
                            retire(next);
                            throw failure;
                        }
                        Generation previous;
                        String selectionNote;
                        try {
                            try (var settings = new ConfigFiles(directory, next.runtime.configurationFiles())) {
                                synchronized (this) {
                                    if (closing) throw new IllegalStateException("Host is stopping");
                                    persist(next);
                                    selectionNote = selectionNote(recordSelection);
                                    previous = current;
                                    current = next;
                                    next.state = State.CURRENT;
                                    if (previous != null) previous.state = State.DRAINING;
                                }
                                settings.commit();
                            }
                        } catch (Throwable failure) {
                            synchronized (this) {
                                next.state = State.DRAINING;
                            }
                            retire(next);
                            throw failure;
                        }
                        report.accept("Activated CultAC runtime " + next.artifact.display() + " (" + next.id + ")");
                        try {
                            activated.run();
                        } catch (RuntimeException failure) {
                            report.accept("Activation listener failed: " + failure);
                        }
                        if (previous != null) {
                            superseded(previous);
                            retire(previous);
                        }
                        return "Activated " + next.artifact.display() + selectionNote;
                    } catch (Throwable failure) {
                        throw new CompletionException(RuntimeFailures.detached(failure));
                    }
                },
                management);
    }

    /** An already-running retry may repair its selection only while that build is still current. */
    synchronized String recordSelection(String expectedBuild, Supplier<String> recordSelection) {
        if (closing || current == null || !current.artifact.build().equals(expectedBuild)) return null;
        return selectionNote(recordSelection);
    }

    private static String selectionNote(Supplier<String> recordSelection) {
        try {
            return recordSelection.get();
        } catch (RuntimeException failure) {
            return "; the next boot may start the last installed build instead: " + failure;
        }
    }

    /** @param hot an older generation keeps running, so a hung load is abandoned after the lifecycle timeout */
    private Generation prepare(Path source, boolean hot) throws Exception {
        String id = UUID.randomUUID().toString();
        Path home = directory.resolve("runtime/generations").resolve(id);
        Files.createDirectories(home);
        Path artifactPath = home.resolve("runtime.jar");
        RuntimeArtifact descriptor;
        try {
            descriptor = RuntimeBundle.stage(source, home, platform, loaderVersion);
            RuntimeArtifact.validateHost(artifactPath, hostVersion);
        } catch (Exception failure) {
            deleteHome(home);
            throw failure;
        }
        var loader = new RuntimeClassLoader(artifactPath.toUri().toURL(), parent);
        var generation = new Generation(id, home, descriptor, loader, ++generationOrder);
        synchronized (this) {
            generations.put(id, generation);
        }
        try {
            CheckedSupplier<Void> load = () -> {
                var entry = (RuntimeEntrypoint) loader.loadClass(descriptor.entrypoint())
                        .getConstructor()
                        .newInstance();
                generation.runtime = entry.create(new Context(generation));
                generation.runtime.load();
                api.initialize(generation.id);
                return null;
            };
            if (hot) lifecycle(generation, "load", load);
            else invoke(generation, load);
            return generation;
        } catch (Throwable failure) {
            synchronized (this) {
                generation.state = State.DRAINING;
            }
            retire(generation);
            if (failure instanceof Exception error) throw error;
            throw (Error) failure;
        }
    }

    public void onActivated(Runnable listener) {
        activated = Objects.requireNonNull(listener);
    }

    /** Native integrations resolve the physical upstream once, before downstream admission. */
    public synchronized void connectionResolver(java.util.function.Function<Object, Object> resolver) {
        upstream = Objects.requireNonNull(resolver);
    }

    public void attach(Object connection, Object nativePlayer) throws Exception {
        attach(connection, nativePlayer, false);
    }

    public void attach(Object connection, Object nativePlayer, boolean requireUpstream) throws Exception {
        synchronized (this) {
            if (sessions.containsKey(connection)) return;
        }
        Object physicalUpstream = upstream.apply(connection);
        Generation owner;
        synchronized (this) {
            if (closing) throw new IllegalStateException("Host is stopping");
            if (sessions.containsKey(connection)) return;
            Session pinned = physicalUpstream == null ? null : sessions.get(physicalUpstream);
            if (physicalUpstream != null && pinned == null) throw new IllegalStateException("Unpinned Bedrock session");
            if (pinned == null && requireUpstream) return;
            owner = pinned == null ? Objects.requireNonNull(current, "No active runtime") : pinned.owner;
            if (owner.state != State.CURRENT && owner.state != State.DRAINING)
                throw new IllegalStateException("Runtime cannot accept a session");
            sessions.put(connection, new Session(owner, physicalUpstream));
            owner.leases++;
        }
        try {
            invoke(owner, () -> {
                owner.runtime.attach(connection, nativePlayer);
                return null;
            });
        } catch (Throwable failure) {
            disconnect(connection);
            throw RuntimeFailures.detached(failure);
        }
    }

    /**
     * Moves a connection from a draining generation to the current one. Call on the connection's own thread at a
     * protocol boundary where the client resends nothing the old owner observed; both steps run in that task.
     *
     * @return whether a newer generation adopted the connection
     * @throws Exception when the move failed partway; the caller must close the connection
     */
    public boolean handoff(Object connection, Object nativePlayer) throws Exception {
        Generation from, to;
        Object physicalUpstream;
        synchronized (this) {
            var session = sessions.get(connection);
            if (closing || session == null || session.disconnect != null) return false;
            physicalUpstream = session.upstream;
            from = session.owner;
            to = current;
            if (to == null || to == from || from.state != State.DRAINING || to.state != State.CURRENT) return false;
            if (physicalUpstream != null) {
                var physical = sessions.get(physicalUpstream);
                if (physical == null || physical.owner != from || physical.disconnect != null) return false;
            }
            // A concurrent publication may make this target draining before adoption completes.
            // Keep its session lifetime open across both engine calls.
            to.handoffs++;
        }
        try {
            var state = invoke(from, () -> from.runtime.release(connection));
            if (state == null) return false;
            synchronized (this) {
                var session = sessions.get(connection);
                if (session == null || session.owner != from || session.disconnect != null)
                    throw new IllegalStateException("Connection ended during hand-off");
                if (physicalUpstream != null) {
                    var physical = sessions.get(physicalUpstream);
                    if (physical == null || physical.owner != from || physical.disconnect != null)
                        throw new IllegalStateException("Bedrock session ended during hand-off");
                    sessions.put(physicalUpstream, new Session(to));
                    from.leases--;
                    to.leases++;
                }
                sessions.put(connection, new Session(to, physicalUpstream));
                from.leases--;
                to.leases++;
            }
            management.execute(() -> retire(from));
            try {
                invoke(to, () -> {
                    to.runtime.adopt(connection, nativePlayer, Map.copyOf(state));
                    return null;
                });
            } catch (Throwable failure) {
                disconnect(connection);
                if (physicalUpstream != null) disconnect(physicalUpstream);
                throw RuntimeFailures.detached(failure);
            }
            return true;
        } finally {
            synchronized (this) {
                to.handoffs--;
            }
            serial(() -> retire(to));
        }
    }

    public CompletionStage<Void> disconnect(Object connection) {
        Session session;
        synchronized (this) {
            session = sessions.get(connection);
            if (session == null) return CompletableFuture.completedFuture(null);
            if (session.disconnect != null) return session.disconnect.minimalCompletionStage();
            session.disconnect = new CompletableFuture<>();
        }
        var owner = session.owner;
        release(connection, owner, operation(owner, () -> owner.runtime.disconnect(connection)));
        return session.disconnect.minimalCompletionStage();
    }

    private void release(Object connection, Generation owner, CompletionStage<Void> cleanup) {
        cleanup.whenComplete((ignored, failure) -> {
            Session session;
            boolean failedWhileClosing;
            synchronized (this) {
                session = sessions.get(connection);
                if (session == null || session.owner != owner) return;
                sessions.remove(connection);
                owner.leases--;
                failedWhileClosing = failure != null && closing;
                // Shutdown retires a generation at zero leases, so its failure is recorded under the same lock.
                if (failedWhileClosing) owner.state = State.CLEANUP_FAILED;
            }
            unbindUser(connection);
            if (failure != null) {
                report.accept("CultAC session cleanup failed for " + owner.id + ": " + failure);
                if (session.disconnect != null)
                    session.disconnect.completeExceptionally(RuntimeFailures.detached(failure));
                if (failedWhileClosing) failedCleanup(owner, failure);
            } else if (session.disconnect != null) session.disconnect.complete(null);
            management.execute(() -> retire(owner));
        });
    }

    private void failedCleanup(Generation owner, Throwable failure) {
        synchronized (this) {
            owner.state = State.CLEANUP_FAILED;
        }
        owner.closed.completeExceptionally(RuntimeFailures.detached(failure));
    }

    /** Tells a generation that has just been replaced; what it throws is reported, and it drains regardless. */
    private void superseded(Generation generation) {
        try {
            lifecycle(generation, "supersede", () -> {
                generation.runtime.superseded();
                return null;
            });
        } catch (Throwable failure) {
            report.accept("CultAC runtime " + generation.id + " failed to step aside: " + failure);
        }
    }

    private void retire(Generation generation) {
        synchronized (this) {
            if (generation.state != State.DRAINING || generation.leases != 0 || generation.handoffs != 0) return;
            generation.state = State.STOPPING;
        }
        // A timed-out call still holds its lease, so the runtime never stops under it.
        var stopped = generation
                .resources
                .close()
                .toCompletableFuture()
                .thenComposeAsync(ignored -> stopRuntime(generation), management);
        stopped.copy()
                .orTimeout(lifecycleTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenCompleteAsync(
                        (ignored, failure) -> {
                            if (!(failure instanceof TimeoutException)) {
                                unload(generation, failure);
                                return;
                            }
                            synchronized (this) {
                                generation.state = State.CLEANUP_FAILED;
                            }
                            report.accept("CultAC runtime " + generation.id + " did not stop within "
                                    + lifecycleTimeout.toSeconds()
                                    + "s; its class loader stays open until its work finishes");
                            generation.closed.completeExceptionally(RuntimeFailures.detached(failure));
                            stopped.whenCompleteAsync((late, error) -> unload(generation, error), this::serial);
                        },
                        this::serial);
    }

    private CompletableFuture<Void> stopRuntime(Generation generation) {
        if (generation.runtime == null) return CompletableFuture.completedFuture(null);
        var stopped = new CompletableFuture<Void>();
        lifecycleThread(generation, "stop", () -> {
            try {
                finishInvoke(generation, () -> generation.runtime.close()).whenComplete((ignored, failure) -> {
                    if (failure == null) stopped.complete(null);
                    else stopped.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                stopped.completeExceptionally(failure);
            }
        });
        return stopped;
    }

    private void unload(Generation generation, Throwable failure) {
        try {
            if (failure != null) throw new CompletionException(failure);
            api.detach(generation.id);
            ac.cult.cultac.manager.datastore.SharedVerboseTags.releaseDeclarations(generation.loader);
            generation.loader.close();
            RecordMethodCaches.release();
            if (!generation.preserveArtifact) deleteHome(generation.home);
            synchronized (this) {
                generation.runtime = null;
                generation.state = State.CLOSED;
                generations.remove(generation.id);
            }
            report.accept("Unloaded CultAC runtime " + generation.id);
            generation.closed.complete(null);
        } catch (Throwable error) {
            synchronized (this) {
                generation.state = State.CLEANUP_FAILED;
            }
            report.accept("CultAC runtime " + generation.id + " cleanup failed: " + error);
            generation.closed.completeExceptionally(RuntimeFailures.detached(error));
        }
    }

    private void persist(Generation generation) throws IOException {
        var properties = new Properties();
        properties.setProperty(
                "artifact",
                directory
                        .resolve("runtime")
                        .relativize(generation.home.resolve("runtime.jar"))
                        .toString());
        properties.setProperty("sha256", generation.artifact.digest());
        properties.setProperty("bundled", bundledDigest);
        Path temporary = directory.resolve("runtime/current.properties.tmp");
        try (var output = Files.newOutputStream(temporary)) {
            properties.store(output, "CultAC active runtime");
        }
        Files.move(
                temporary,
                directory.resolve("runtime/current.properties"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static final java.time.format.DateTimeFormatter CREATED = java.time.format.DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault());

    public synchronized List<String> status() {
        return generations.values().stream().map(GenerationManager::describe).toList();
    }

    /** The build new sessions attach to; null before a runtime is loaded. */
    public synchronized String currentBuild() {
        return current == null ? null : current.artifact.build();
    }

    /** The installed loader's {@code cult-loader-version}. */
    public String loaderVersion() {
        return loaderVersion;
    }

    /** Where {@link #update} takes its jars from. */
    public Path updates() {
        return directory.resolve("updates");
    }

    /** Sessions still on runtimes a newer one replaced. */
    public synchronized int drainingSessions() {
        return generations.values().stream()
                .filter(g -> g.state == State.DRAINING)
                .mapToInt(g -> g.leases)
                .sum();
    }

    private static String describe(Generation g) {
        return "#" + g.order + " " + g.artifact.display() + " " + g.id + " " + g.state + " sessions=" + g.leases
                + " work=" + g.resources.inFlight() + " created " + CREATED.format(g.created);
    }

    /** One runtime's status line, selected by its {@code #} number or its id. */
    public synchronized String status(String selector) {
        return describe(select(selector));
    }

    /** Accounts of the logged-in players the selected runtime inspects, in login order. */
    public synchronized List<UUID> players(String selector) {
        var generation = select(selector);
        return users.values().stream()
                .filter(binding -> binding.owner().equals(generation.id))
                .sorted(Comparator.comparingLong(UserBinding::bindingOrder))
                .map(UserBinding::uuid)
                .distinct()
                .toList();
    }

    private Generation select(String selector) {
        String number = selector.startsWith("#") ? selector.substring(1) : selector;
        var matches = generations.values().stream()
                .filter(g -> Long.toString(g.order).equals(number) || g.id.equals(selector))
                .toList();
        if (matches.size() != 1) throw new IllegalArgumentException("No runtime " + selector);
        return matches.getFirst();
    }

    private synchronized List<Generation> snapshot() {
        return generations.values().stream()
                .filter(g -> g.runtime != null && (g.state == State.CURRENT || g.state == State.DRAINING))
                .toList();
    }

    synchronized List<String> runtimeIds() {
        return snapshot().stream().map(g -> g.id).toList();
    }

    synchronized String currentId() {
        if (current == null) throw RuntimeFailures.ended();
        return current.id;
    }

    private synchronized Generation generation(String id) {
        var owner = generations.get(id);
        if (owner == null
                || owner.state == State.STOPPING
                || owner.state == State.CLOSED
                || owner.state == State.CLEANUP_FAILED) throw RuntimeFailures.ended();
        return owner;
    }

    <T> T call(String owner, RuntimeCall<T> action) throws Exception {
        var generation = generation(owner);
        return invoke(generation, () -> action.apply(generation.runtime));
    }

    public Object publicApi() {
        return api.facade();
    }

    public CompletionStage<Void> execute(Object sender, String input, Object target, boolean aggregate) {
        if (aggregate)
            return CompletableFuture.allOf(snapshot().stream()
                    .map(g ->
                            operation(g, () -> g.runtime.execute(sender, input)).toCompletableFuture())
                    .toArray(CompletableFuture[]::new));
        UserBinding user = user(target);
        var owner = generation(user == null ? currentId() : user.owner());
        return operation(owner, () -> owner.runtime.execute(sender, input));
    }

    public CompletionStage<List<String>> suggest(Object sender, String input) {
        var owner = generation(currentId());
        return operation(owner, () -> owner.runtime.suggest(sender, input));
    }

    public List<String> placeholders() {
        try {
            return call(currentId(), runtime -> List.copyOf(runtime.placeholders()));
        } catch (Exception failure) {
            return List.of();
        }
    }

    public List<String> commandRoots() {
        try {
            List<String> roots = call(currentId(), runtime -> List.copyOf(runtime.commandRoots()));
            return roots.isEmpty() ? RuntimeInstance.DEFAULT_COMMAND_ROOTS : roots;
        } catch (Exception failure) {
            return RuntimeInstance.DEFAULT_COMMAND_ROOTS;
        }
    }

    /** The current generation's observe-only setting; false when there is none. */
    public boolean isObserveOnly() {
        try {
            return call(currentId(), RuntimeInstance::isObserveOnly);
        } catch (Exception failure) {
            return false;
        }
    }

    /** A missing runtime, or one that cannot answer, lets the connection in. */
    public boolean failsOpen() {
        try {
            return call(currentId(), RuntimeInstance::failsOpen);
        } catch (Exception failure) {
            return true;
        }
    }

    public String placeholder(UUID player, String parameter) {
        UserBinding user = user(player);
        try {
            return call(
                    user == null ? currentId() : user.owner(),
                    runtime -> runtime.placeholder(user == null ? null : player, parameter));
        } catch (Exception failure) {
            throw RuntimeFailures.detached(failure);
        }
    }

    private <T> CompletionStage<T> operation(Generation owner, Supplier<CompletionStage<T>> action) {
        return owner.resources.callAsync(action::get);
    }

    synchronized String ownerOf(Object connection) {
        var session = sessions.get(connection);
        return session == null ? null : session.owner.id;
    }

    synchronized UserBinding user(Object identity) {
        if (identity instanceof UUID uuid) {
            var entries = usersByUuid.get(uuid);
            return entries == null
                    ? null
                    : entries.stream()
                            .max(Comparator.comparingLong(UserBinding::generationOrder)
                                    .thenComparingLong(UserBinding::bindingOrder))
                            .orElse(null);
        }
        return nativeUsers.get(identity);
    }

    private void bindUser(Generation owner, Object connection, UUID uuid, Object nativePlayer, Object user) {
        synchronized (this) {
            var session = sessions.get(connection);
            if (session == null || session.owner != owner)
                throw new IllegalStateException("User has no pinned connection");
            var previous = users.get(connection);
            if (previous != null) removeBinding(previous);
            var binding = new UserBinding(owner.id, connection, uuid, nativePlayer, user, owner.order, ++bindingOrder);
            users.put(connection, binding);
            usersByUuid.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(binding);
            if (nativePlayer != null) nativeUsers.put(nativePlayer, binding);
        }
    }

    private void removeBinding(UserBinding binding) {
        users.remove(binding.connection(), binding);
        var entries = usersByUuid.get(binding.uuid());
        entries.remove(binding);
        if (entries.isEmpty()) usersByUuid.remove(binding.uuid());
        if (binding.nativePlayer() != null) nativeUsers.remove(binding.nativePlayer(), binding);
    }

    private void unbindUser(Object connection) {
        UserBinding binding;
        synchronized (this) {
            binding = users.get(connection);
            if (binding != null) removeBinding(binding);
        }
        if (binding != null) api.detachUser(binding.user());
    }

    record UserBinding(
            String owner,
            Object connection,
            UUID uuid,
            Object nativePlayer,
            Object user,
            long generationOrder,
            long bindingOrder) {}

    public CompletableFuture<Boolean> reload(Object configuration) {
        return CompletableFuture.supplyAsync(
                () -> {
                    var live = snapshot();
                    var leases = new ArrayList<ResourceScope.Lease>();
                    var old = new LinkedHashMap<Generation, Object>();
                    var next = new LinkedHashMap<Generation, Object>();
                    var applied = new ArrayList<Generation>();
                    boolean success = false;
                    try {
                        if (configuration instanceof ac.grim.grimac.api.config.ConfigManager config) config.reload();
                        for (var generation : live) {
                            leases.add(generation.resources.acquire());
                            old.put(generation, generation.runtime.currentConfiguration());
                            next.put(
                                    generation,
                                    invoke(generation, () -> generation.runtime.prepareReload(configuration)));
                        }
                        for (var generation : live) {
                            applied.add(generation);
                            if (!invoke(generation, () -> generation.runtime.applyReload(next.get(generation))))
                                throw new IllegalStateException(
                                        "Configuration rejected by " + generation.artifact.build());
                        }
                        Generation active;
                        synchronized (this) {
                            active = current;
                        }
                        if (active != null)
                            try (var files = new ConfigFiles(directory, active.runtime.configurationFiles())) {
                                files.commit();
                            }
                        success = true;
                    } catch (Throwable failure) {
                        report.accept("CultAC configuration reload failed: " + failure);
                        Collections.reverse(applied);
                        for (var generation : applied) {
                            try {
                                invoke(generation, () -> generation.runtime.applyReload(old.get(generation)));
                            } catch (Exception rollback) {
                                report.accept("CultAC configuration rollback failed: " + rollback);
                            }
                        }
                    } finally {
                        for (var lease : leases) {
                            try {
                                lease.close();
                            } catch (Exception failure) {
                                report.accept("CultAC reload cleanup failed: " + failure);
                            }
                        }
                        synchronized (shared) {
                            var bus = shared.get("event-bus");
                            if (bus instanceof ac.grim.grimac.api.event.EventBus events)
                                events.get(ac.grim.grimac.api.event.events.GrimReloadEvent.class)
                                        .fire(success);
                        }
                    }
                    return success;
                },
                management);
    }

    ClassLoader parentLoader() {
        return parent;
    }

    private <T> T invoke(Generation generation, CheckedSupplier<T> action) throws Exception {
        return generation.resources.call(action::get);
    }

    /** Runs a lifecycle call off the manager thread; past the timeout the caller stops waiting for it. */
    private <T> T lifecycle(Generation generation, String step, CheckedSupplier<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        lifecycleThread(generation, step, () -> {
            try {
                result.complete(invoke(generation, action));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(lifecycleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            result.whenComplete((ignored, failure) -> report.accept("CultAC runtime " + generation.id + " finished "
                    + step + " after its timeout" + (failure == null ? "" : ": " + failure)));
            throw new TimeoutException("CultAC runtime " + generation.artifact.display() + " did not finish " + step
                    + " within " + lifecycleTimeout.toSeconds() + "s");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            throw (Error) failure.getCause();
        }
    }

    private static void lifecycleThread(Generation generation, String step, Runnable task) {
        Thread.ofPlatform()
                .daemon()
                .name("cult-runtime-" + step + "-" + generation.id)
                .start(task);
    }

    /** The only unguarded engine invocation: its gate has drained before final stop. */
    private <T> T finishInvoke(Generation generation, CheckedSupplier<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(generation.loader);
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public CompletionStage<Void> stop() {
        return shutdown(null);
    }

    /** Install the latest engine and clean staging before any final runtime unload. */
    public CompletionStage<Void> shutdown(Path installedPlugin) {
        synchronized (this) {
            if (closing) return shutdown.minimalCompletionStage();
            closing = true;
        }
        management.execute(() -> beginStop(installedPlugin));
        return shutdown.minimalCompletionStage();
    }

    private void beginStop(Path installedPlugin) {
        List<Generation> all;
        Generation latest;
        synchronized (this) {
            latest = current;
            if (latest != null) latest.preserveArtifact = true;
            all = new ArrayList<>(generations.values());
        }
        Throwable installationFailure = null;
        if (latest != null && installedPlugin != null) {
            try {
                PluginInstaller.install(installedPlugin, latest.home.resolve("runtime.jar"), latest.artifact, platform);
                report.accept(
                        "Installed CultAC " + latest.artifact.display() + " into " + installedPlugin.getFileName());
                Files.deleteIfExists(directory.resolve("runtime/current.properties"));
                latest.preserveArtifact = false;
                try {
                    int removed = PluginInstaller.cleanUpdates(directory.resolve("updates"));
                    report.accept("Removed " + removed + " staged CultAC update files");
                } catch (IOException cleanup) {
                    report.accept("CultAC update-folder cleanup failed: " + cleanup);
                }
            } catch (Throwable failure) {
                installationFailure = RuntimeFailures.detached(failure);
                report.accept(
                        "CultAC plugin installation failed; active artifact and staged updates retained: " + failure);
            }
        }
        synchronized (this) {
            current = null;
            for (var generation : all)
                if (generation.state != State.STOPPING && generation.state != State.CLEANUP_FAILED)
                    generation.state = State.DRAINING;
        }
        for (var connection : sessionSnapshot()) {
            Generation owner;
            synchronized (this) {
                var session = sessions.get(connection);
                owner = session == null ? null : session.owner;
            }
            if (owner != null)
                disconnect(connection).whenComplete((ignored, error) -> {
                    if (error != null) failedCleanup(owner, error);
                });
        }
        for (var generation : all) retire(generation);
        Throwable promotionError = installationFailure;
        CompletableFuture.allOf(all.stream().map(g -> g.closed).toArray(CompletableFuture[]::new))
                .orTimeout(lifecycleTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenCompleteAsync(
                        (ignored, failure) -> {
                            Throwable error = failure == null ? promotionError : RuntimeFailures.detached(failure);
                            synchronized (shared) {
                                var values = new ArrayList<>(shared.values());
                                Collections.reverse(values);
                                for (var value : values)
                                    if (value instanceof AutoCloseable resource) {
                                        try {
                                            resource.close();
                                        } catch (Exception cleanup) {
                                            if (error == null) error = RuntimeFailures.detached(cleanup);
                                            else error.addSuppressed(RuntimeFailures.detached(cleanup));
                                        }
                                    }
                                shared.clear();
                            }
                            if (error == null) shutdown.complete(null);
                            else shutdown.completeExceptionally(error);
                            management.shutdown();
                        },
                        this::serial);
    }

    private static void deleteHome(Path home) throws IOException {
        try (var files = Files.walk(home)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private synchronized List<Object> sessionSnapshot() {
        return new ArrayList<>(sessions.keySet());
    }

    @Override
    public void close() {
        stop().toCompletableFuture().join();
    }

    private final class Context implements HostContext {
        private final Generation generation;

        Context(Generation generation) {
            this.generation = generation;
        }

        public String generationId() {
            return generation.id;
        }

        public String platform() {
            return platform;
        }

        public String build() {
            return generation.artifact.display();
        }

        public Path directory() {
            return directory;
        }

        public Object plugin() {
            return plugin;
        }

        public Object server() {
            return server;
        }

        public Object logger() {
            return logger;
        }

        public ResourceScope resources() {
            return generation.resources;
        }

        public boolean current() {
            synchronized (GenerationManager.this) {
                return current == generation;
            }
        }

        public boolean owns(Object session) {
            synchronized (GenerationManager.this) {
                var binding = sessions.get(session);
                return binding != null && binding.owner == generation;
            }
        }

        public boolean pin(Object session) {
            synchronized (GenerationManager.this) {
                var owner = sessions.get(session);
                if (owner != null) return owner.owner == generation;
                if (closing || current != generation || generation.state != State.CURRENT) return false;
                sessions.put(session, new Session(generation));
                generation.leases++;
                return true;
            }
        }

        public void release(Object session, CompletionStage<Void> cleanup) {
            GenerationManager.this.release(session, generation, cleanup);
        }

        public String ownerOf(Object connection) {
            return GenerationManager.this.ownerOf(connection);
        }

        public boolean handoff(Object connection, Object nativePlayer) throws Exception {
            if (!owns(connection)) return false;
            return GenerationManager.this.handoff(connection, nativePlayer);
        }

        public void bindUser(Object connection, UUID uuid, Object nativePlayer, Object user) {
            GenerationManager.this.bindUser(generation, connection, uuid, nativePlayer, user);
        }

        public void unbindUser(Object connection) {
            if (owns(connection)) GenerationManager.this.unbindUser(connection);
        }

        public Object shared(String key, Supplier<Object> create) {
            synchronized (shared) {
                var value = shared.get(key);
                if (value == null) {
                    value = create.get();
                    if (value.getClass().getClassLoader() instanceof RuntimeClassLoader) {
                        throw new IllegalArgumentException("Shared services must be bootstrap-owned: " + key);
                    }
                    shared.put(key, value);
                }
                return value;
            }
        }

        public Object publicApi() {
            return api.facade();
        }

        public Object wrap(Object value) {
            return api.wrap(generation.id, value, null);
        }

        public Object wrap(Object value, Object user) {
            return api.wrap(generation.id, value, user);
        }

        public CompletionStage<String> update(String filename) {
            return GenerationManager.this.update(filename);
        }

        public List<String> status() {
            return GenerationManager.this.status();
        }
    }

    private static final class Generation {
        final String id;
        final Path home;
        final RuntimeArtifact artifact;
        final RuntimeClassLoader loader;
        final ScopedResources resources;
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        RuntimeInstance runtime;
        State state = State.PREPARING;
        int leases;
        int handoffs;
        final long order;
        final java.time.Instant created = java.time.Instant.now();
        boolean preserveArtifact;

        Generation(String id, Path home, RuntimeArtifact artifact, RuntimeClassLoader loader, long order) {
            this.order = order;
            this.id = id;
            this.home = home;
            this.artifact = artifact;
            this.loader = loader;
            resources = new ScopedResources(loader);
        }
    }

    private static final class Session {
        final Generation owner;
        final Object upstream;
        CompletableFuture<Void> disconnect;

        Session(Generation owner) {
            this(owner, null);
        }

        Session(Generation owner, Object upstream) {
            this.owner = owner;
            this.upstream = upstream;
        }
    }

    @FunctionalInterface
    interface RuntimeCall<T> {
        T apply(RuntimeInstance runtime) throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
