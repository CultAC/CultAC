package ac.cult.placement;

import ac.cult.placement.api.BlockGeometry;
import ac.cult.placement.api.InteractionEngine;
import ac.cult.placement.api.PlacementEngine;
import ac.cult.runtime.RuntimeModel;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Parallel requests pin a model generation and read request-local tags. */
public final class PlacementRuntime implements BlockGeometry, AutoCloseable {
    private final Path directory;
    private final Path workerJar;
    private final Path cache;
    private final RuntimeModel model;
    private final AtomicReference<PlacementModel> current = new AtomicReference<>();
    private final AtomicBoolean recovering = new AtomicBoolean();

    public static boolean available() {
        return available(RuntimeModel.JAVA_26_3);
    }

    public static boolean available(RuntimeModel model) {
        return PlacementRuntime.class.getResource("/vanilla/" + model.version() + ".properties") != null;
    }

    public PlacementRuntime(Path workerJar) throws Exception {
        this(workerJar, workerJar.toAbsolutePath().getParent().resolve("runtime"), false, RuntimeModel.JAVA_26_3);
    }

    /** Production uses one original vanilla model for geometry and interactions. */
    public static PlacementRuntime openVanilla(Path workerJar) throws Exception {
        return openVanilla(workerJar, workerJar.toAbsolutePath().getParent().resolve("runtime"));
    }

    public static PlacementRuntime openVanilla(Path workerJar, Path cache) throws Exception {
        return openVanilla(workerJar, cache, RuntimeModel.JAVA_26_3);
    }

    public static PlacementRuntime openVanilla(Path workerJar, Path cache, RuntimeModel model) throws Exception {
        return new PlacementRuntime(workerJar, cache, true, Objects.requireNonNull(model));
    }

    public RuntimeModel model() {
        return model;
    }

    private PlacementRuntime(Path workerJar, Path cache, boolean vanilla, RuntimeModel model) throws Exception {
        this.workerJar = workerJar;
        this.cache = cache;
        this.model = model;
        if (vanilla) {
            directory = null;
            var interactions = openInteractions(workerJar, cache, model);
            current.set(new PlacementModel(interactions.geometry(), interactions, null));
            return;
        }
        ArchiveLoader loader = null;
        InteractionRuntime interactions = null;
        String audit = System.getProperty("placementGeometryProfile");
        if (audit == null)
            throw new IOException("Compact geometry is a local audit profile; production must use openVanilla");
        directory = Path.of(audit).toAbsolutePath().normalize();
        try {
            try (var in = Files.newInputStream(directory.resolve("engine.jar"))) {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536];
                for (int read; (read = in.read(buffer)) != -1; ) digest.update(buffer, 0, read);
                if (!HexFormat.of()
                        .formatHex(digest.digest())
                        .equals("7e2ba06b59d57c08c6b97fd4215170f6dc2192293683e962a63721de761844c4")) {
                    throw new IOException("Unexpected placement engine version");
                }
            }
            var roots = new ArrayList<URL>();
            roots.add(directory.resolve("helpers.jar").toUri().toURL());
            roots.add(directory.resolve("engine.jar").toUri().toURL());
            roots.add(directory.resolve("adapter.jar").toUri().toURL());
            roots.add(workerJar.toUri().toURL());
            try (var files = Files.list(directory.resolve("lib"))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".jar"))
                        .sorted()
                        .toList()) roots.add(file.toUri().toURL());
            }
            loader = new ArchiveLoader(roots.toArray(URL[]::new), PlacementEngine.class.getClassLoader());
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader == null ? interactions.classLoader() : loader);
                var engine = (PlacementEngine) Class.forName("bench.bridge.CompensatedEngine", true, loader)
                        .getConstructor()
                        .newInstance();
                interactions = openInteractions(workerJar, cache, model);
                current.set(new PlacementModel(engine, interactions, loader));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            loader.releaseArchiveIndexes();
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public int stateCount() {
        return query(pinned -> pinned.engine.stateCount());
    }

    public String stateName(int state) {
        return query(pinned -> pinned.engine.stateName(state));
    }

    @Override
    public BlockGeometry.State state(int id) {
        return query(pinned -> pinned.engine.state(id));
    }

    @Override
    public List<PlacementEngine.Box> shape(
            PlacementEngine.World world, PlacementEngine.Pos pos, int state, Shape kind, Context context) {
        return query(pinned -> pinned.engine.shape(world, pos, state, kind, context));
    }

    public List<PlacementEngine.Box> collision(PlacementEngine.World world, PlacementEngine.Pos pos) {
        return query(pinned -> pinned.engine.collision(world, pos));
    }

    public List<PlacementEngine.Box> outline(PlacementEngine.World world, PlacementEngine.Pos pos) {
        return query(pinned -> pinned.engine.outline(world, pos));
    }

    public PlacementEngine.Result place(PlacementEngine.Request request) {
        return query(pinned -> pinned.engine.place(request));
    }

    public InteractionEngine.Result interact(InteractionEngine.Request request) {
        return query(pinned -> pinned.interactions.interact(request));
    }

    private <T> T query(Function<PlacementModel, T> operation) {
        PlacementModel pinned;
        do {
            pinned = current.get();
            if (pinned == null) throw new IllegalStateException("Placement runtime is closed");
        } while (!pinned.acquire());
        var previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(pinned.classLoader());
            return operation.apply(pinned);
        } catch (RuntimeException | Error failure) {
            narrowingFailed(pinned, failure);
            throw failure;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            pinned.release();
        }
    }

    /** Interaction linkage is completed once during model construction, before publication. */
    public void prepareInteractions() {
        query(pinned -> null);
    }

    public boolean narrowed() {
        var pinned = current.get();
        return pinned != null && pinned.interactions.narrowed;
    }

    private static final System.Logger LOGGER = System.getLogger("CultAC");

    /** The narrowed model when it starts, else the full one; startup is off the packet path. */
    private static InteractionRuntime openInteractions(Path workerJar, Path cache, RuntimeModel model)
            throws Exception {
        if (model != RuntimeModel.JAVA_26_3) return new InteractionRuntime(workerJar, cache, model, false);
        try {
            return new InteractionRuntime(workerJar, cache, model, true);
        } catch (Exception | Error failure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "The narrowed vanilla model failed to start; using the full model",
                    failure);
            return new InteractionRuntime(workerJar, cache, model, false);
        }
    }

    /**
     * What leaving content out can cause: a class that fails to link or initialize (such as
     * one registering into a skipped, frozen registry), a data registry the model did not
     * load, or a failure inside the narrowing support itself. Invalid requests fail the same
     * way in both models and do not count.
     */
    private static boolean fromNarrowing(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LinkageError) return true;
            if (cause instanceof IllegalStateException state
                    && (String.valueOf(state.getMessage()).contains("already frozen")
                            || String.valueOf(state.getMessage()).startsWith("Missing registry"))) return true;
            for (var frame : cause.getStackTrace())
                if (frame.getClassName().startsWith("ac.cult.placement.runtime.ModelScope")) return true;
        }
        return false;
    }

    /**
     * A narrowed model that throws may have reached something it leaves out. Report it once and
     * build the full model in the background (about 3 s, never on a packet thread), then swap.
     */
    private void narrowingFailed(PlacementModel failed, Throwable failure) {
        if (failed == null
                || !failed.interactions.narrowed
                || !fromNarrowing(failure)
                || !recovering.compareAndSet(false, true)) return;
        LOGGER.log(
                System.Logger.Level.WARNING, "The narrowed vanilla model failed; switching to the full model", failure);
        var recovery = Thread.ofPlatform()
                .name("cult-vanilla-full-model")
                .daemon()
                .inheritInheritableThreadLocals(false)
                .unstarted(() -> {
                    InteractionRuntime full = null;
                    try {
                        full = new InteractionRuntime(workerJar, cache, model, false);
                        var replacement = new PlacementModel(full.geometry(), full, null);
                        full.releaseArchiveIndexes();
                        if (current.compareAndSet(failed, replacement)) {
                            full = null;
                            failed.release();
                            failed.closed.whenComplete((ignored, closing) -> {
                                if (closing != null)
                                    LOGGER.log(
                                            System.Logger.Level.WARNING, "Unable to close the narrowed model", closing);
                            });
                        }
                    } catch (Exception | Error unavailable) {
                        LOGGER.log(System.Logger.Level.ERROR, "Unable to open the full vanilla model", unavailable);
                    } finally {
                        if (full != null) {
                            try {
                                full.close();
                            } catch (Exception closing) {
                                LOGGER.log(
                                        System.Logger.Level.WARNING, "Unable to close the unused full model", closing);
                            }
                        }
                        recovering.set(false);
                    }
                });
        // Failures are detected while the caller's context loader is the isolated one.
        // A background builder must not inherit and retain that retiring loader.
        recovery.setContextClassLoader(PlacementRuntime.class.getClassLoader());
        recovery.start();
    }

    @Override
    public void close() throws IOException {
        var retired = current.getAndSet(null);
        if (retired == null) return;
        retired.release();
        try {
            retired.closed.join();
        } catch (java.util.concurrent.CompletionException failure) {
            throw new IOException("Unable to close placement model", failure.getCause());
        }
    }
}
