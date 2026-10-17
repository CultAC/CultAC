package ac.cult.cultac.cloud.diag;

import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import com.github.luben.zstd.Zstd;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One cloud socket's diagnostics: the players the service watches, their rings, the cuts it asks for, and the parts
 * that answer them. The service names every player and range; nothing here decides whom to watch.
 *
 * <p>Off costs nothing: no thread, queue or ring exists until the service's limits enable diagnostics and a watch
 * starts, and an unwatched player's packet thread reads one null field per prediction and per tick end. While watched, the
 * packet thread copies each prediction ({@link DiagWatch#capture}) into a bounded queue; the single
 * {@code cult-cloud-diag} thread prints the block grid, compresses the text, keeps the ring and builds the parts, which
 * {@link DiagUpload} paces onto the socket. Nothing runs on the server's main thread and nothing is written to a
 * player's connection. Three failures anywhere in diagnostics trip it off for the process; prediction goes on.
 */
public final class Diagnostics {
    /** Predictions and tick ends waiting for the diag thread; past it they are dropped and counted. */
    static final int QUEUE = 256;
    /** Watches, cuts and their ends waiting for the diag thread. */
    static final int MAX_COMMANDS = 256;

    static final int MAX_PENDING_CUTS = 16;
    /** How long a cut waits for the ring to reach its last seq. */
    static final long CUT_WAIT_NANOS = TimeUnit.SECONDS.toNanos(15);
    /** Captures a watch's packet-thread cost is judged over. */
    static final int CAPTURE_WINDOW = 100;
    /** A watch whose capture p99 over {@link #CAPTURE_WINDOW} exceeds this is ended. */
    static final long CAPTURE_P99_LIMIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    static final int TRIP_FAILURES = 3;
    static final int MAX_LEASE_SECONDS = 24 * 60 * 60;
    private static final int MAX_REFUSALS = 64;
    private static final long POLL_MILLIS = 50;
    private static final int ZSTD_LEVEL = 1;

    private static final LogThrottle FAILURES = new LogThrottle();
    private static final AtomicInteger failures = new AtomicInteger();
    /** Numbers diagnostics threads. */
    private static final AtomicInteger NUMBER = new AtomicInteger();

    private static volatile boolean tripped;
    /** The capture p99 past which a watch ends; {@link #CAPTURE_P99_LIMIT_NANOS} but in tests that time nothing. */
    long captureLimitNanos = CAPTURE_P99_LIMIT_NANOS;

    static final AtomicLong droppedCaptures = new AtomicLong();
    static final AtomicLong malformedCount = new AtomicLong();

    private final AtomicLong bundlesSent = new AtomicLong();
    private final AtomicLong bundlesDropped = new AtomicLong();
    private final DiagUpload upload;
    private final String serverId;
    private final LongSupplier clock;

    private volatile Limits limits = Limits.OFF;
    /** Whether the open socket took diagnostics at connect. */
    private volatile boolean offered;

    private volatile long generation = -1;
    private volatile boolean closed;
    private final Map<UUID, DiagWatch> watches = new ConcurrentHashMap<>();
    /** Why a player's last watch was refused or ended, for cuts that come after; guarded by this. */
    private final Map<UUID, String> refusals = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
            return size() > MAX_REFUSALS;
        }
    };
    /** Ring bytes reserved by the watches running; guarded by this. */
    private long reserved;

    private volatile ArrayBlockingQueue<Object> inbox;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final AtomicInteger commandCount = new AtomicInteger();
    /** Guarded by this. */
    private Thread thread;

    // Diagnostics thread only.
    private final List<Pending> cuts = new ArrayList<>();
    private final List<DiagWatch> known = new ArrayList<>();
    private double rateTokens = -1;
    private long rateRefilled;
    private volatile long ringBytes;
    private final Costs costs = new Costs();

    /** The last {@link #SAMPLES} captures' packet-thread and diag-thread nanoseconds, and text sizes; for status. */
    static final class Costs {
        static final int SAMPLES = 1024;
        private final long[] capture = new long[SAMPLES];
        private final long[] render = new long[SAMPLES];
        private long count;
        private long renders;
        private long textChars;
        private long fullBytes;

        synchronized void add(long captureNanos, long renderNanos, int chars, int compressed) {
            int at = (int) (count++ % SAMPLES);
            capture[at] = captureNanos;
            render[at] = renderNanos;
            if (chars > 0) {
                renders++;
                textChars += chars;
                fullBytes += compressed;
            }
        }

        /** Capture p50 and p99, render p50 and p99 (ns), mean text chars and compressed bytes per full text. */
        synchronized long[] summary() {
            int n = (int) Math.min(count, SAMPLES);
            if (n == 0) return new long[6];
            long[] captures = Arrays.copyOf(capture, n);
            long[] renderings = Arrays.copyOf(render, n);
            Arrays.sort(captures);
            Arrays.sort(renderings);
            return new long[] {
                captures[n / 2],
                captures[(n * 99 - 1) / 100],
                renderings[n / 2],
                renderings[(n * 99 - 1) / 100],
                renders == 0 ? 0 : textChars / renders,
                renders == 0 ? 0 : fullBytes / renders
            };
        }
    }

    private record Pending(UUID player, Cut cut, String header, DiagWatch watch, long deadline) {}

    public Diagnostics(DiagUpload upload, String serverId) {
        this(upload, serverId, System::nanoTime);
    }

    Diagnostics(DiagUpload upload, String serverId, LongSupplier clock) {
        this.upload = upload;
        this.serverId = serverId;
        this.clock = clock;
    }

    /**
     * A socket of {@code generation} opened; {@code limits} is what connect answered for diagnostics, null when the
     * service took none. Watches of an older socket are already gone.
     */
    public void connected(long generation, Limits limits) {
        endAll(null);
        upload.clear();
        this.generation = generation;
        offered = limits != null;
        apply(limits == null ? Limits.OFF : limits);
    }

    /** The service's new limits for the socket of {@code generation}; disabled ends every watch. */
    public void limits(long generation, Limits limits) {
        if (generation != this.generation || !offered) {
            malformedCount.incrementAndGet();
            return;
        }
        apply(limits);
    }

    private void apply(Limits limits) {
        this.limits = limits;
        upload.rate(limits.uploadBytesPerSecond());
        if (!limits.enabled()) endAll("lease-ended");
    }

    /** The socket of {@code generation} is gone: every watch, cut and part with it. The service seals what it has. */
    public void socketClosed(long generation) {
        if (generation == this.generation) stop();
    }

    /** Plugin disable: everything stops; the thread exits once it sees the queue empty. */
    public void close() {
        closed = true;
        stop();
    }

    private void stop() {
        offered = false;
        endAll(null);
        upload.clear();
        clearCuts();
    }

    /** Whether diagnostics are offered and enabled on the open socket, and not tripped. */
    public boolean active() {
        return offered && limits.enabled() && !tripped && !closed;
    }

    /**
     * Starts or renews a watch on the player's packet thread. {@code history} is read only when a watch starts.
     * Returns the running watch, or null when none runs: a lease of 0, diagnostics off, or no room ({@code busy}).
     */
    public synchronized DiagWatch watch(
            UUID player,
            long generation,
            int leaseSeconds,
            LongSupplier pendingSeq,
            Supplier<String> history,
            Consumer<DiagWatch> detach) {
        DiagWatch current = watches.get(player);
        if (leaseSeconds <= 0) {
            if (current != null) end(current, "lease-ended");
            return null;
        }
        if (generation != this.generation || !active()) {
            refusals.put(player, tripped ? "failed" : "busy");
            return null;
        }
        long leaseEnd = clock.getAsLong() + TimeUnit.SECONDS.toNanos(Math.min(leaseSeconds, MAX_LEASE_SECONDS));
        if (current != null && !current.ended) {
            current.leaseEndNanos = leaseEnd;
            return current;
        }
        Limits limits = this.limits;
        if (watches.size() >= limits.maxPlayers() || reserved + limits.playerRingBytes() > limits.serverRingBytes()) {
            refusals.put(player, "busy");
            return null;
        }
        DiagWatch watch = new DiagWatch(this, player, generation, leaseEnd, pendingSeq, history.get(), limits, detach);
        watches.put(player, watch);
        reserved += watch.ringBytes;
        refusals.remove(player);
        ensureThread();
        command(() -> known.add(watch));
        return watch;
    }

    /** No watch can run for this player, and why; cuts for them answer {@code NONE} with it. */
    public synchronized void refuse(UUID player, String reason) {
        refusals.put(player, reason);
    }

    /** The player left: their watch ends and later cuts answer {@code left}. */
    public synchronized void left(UUID player) {
        DiagWatch watch = watches.get(player);
        if (watch != null) end(watch, "left");
        else if (refusals.containsKey(player)) refusals.put(player, "left");
    }

    /**
     * The service asks for the player's SuperDebug of a range, {@code header} rendered on their packet thread (null when
     * the player is unknown here). A cut no watch can answer is answered {@code NONE} at once, without the thread.
     */
    public void cut(UUID player, Cut cut, String header) {
        if (!cut.valid()) {
            malformedCount.incrementAndGet();
            return;
        }
        if (!offered || closed || !limits.enabled()) return;
        long generation = this.generation;
        DiagWatch watch = watches.get(player);
        if (watch == null || watch.ended || header == null) {
            String reason;
            synchronized (this) {
                reason = tripped ? "failed" : refusals.getOrDefault(player, header == null ? "left" : "lease-ended");
            }
            answer(generation, player, cut, DiagCuts.Coverage.none(reason));
            return;
        }
        if (!command(() -> arrived(player, cut, header, watch))) {
            answer(generation, player, cut, DiagCuts.Coverage.none("busy"));
        }
    }

    /** A diagnostics message from the service this build could not use; dropped and counted, never fatal. */
    public static void malformed() {
        malformedCount.incrementAndGet();
    }

    /** Queues a prediction or tick end for the diag thread; false when the queue is full. */
    boolean offer(Object item) {
        ArrayBlockingQueue<Object> queue = inbox;
        if (queue != null && queue.offer(item)) return true;
        droppedCaptures.incrementAndGet();
        return false;
    }

    /** A failure anywhere in diagnostics; the third trips it off for the process. */
    void failed(Throwable failure) {
        FAILURES.warn("diag", "Cloud diagnostics failed; prediction is unaffected", failure);
        if (failures.incrementAndGet() >= TRIP_FAILURES && !tripped) {
            tripped = true;
            endAll("failed");
        }
    }

    private synchronized void end(DiagWatch watch, String reason) {
        if (watch.ended) return;
        watch.endReason = reason;
        watch.ended = true;
        watches.remove(watch.player, watch);
        reserved -= watch.ringBytes;
        if (reason != null) refusals.put(watch.player, reason);
        watch.detach();
    }

    private synchronized void endAll(String reason) {
        for (DiagWatch watch : List.copyOf(watches.values())) end(watch, reason);
    }

    private boolean command(Runnable command) {
        if (commandCount.incrementAndGet() > MAX_COMMANDS) {
            commandCount.decrementAndGet();
            malformedCount.incrementAndGet();
            return false;
        }
        commands.add(command);
        ensureThread();
        return true;
    }

    /** Clears the pending cuts, if a thread holds any: without one there are none. */
    private synchronized void clearCuts() {
        if (thread != null) command(cuts::clear);
    }

    private synchronized void ensureThread() {
        if (thread != null || closed) return;
        if (inbox == null) inbox = new ArrayBlockingQueue<>(QUEUE);
        thread = Thread.ofPlatform()
                .daemon()
                .name("cult-cloud-diag-" + NUMBER.incrementAndGet())
                .unstarted(this::run);
        thread.start();
    }

    private void run() {
        try {
            while (true) {
                Object item = inbox.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                runCommands();
                int handled = 0;
                while (item != null) {
                    handle(item);
                    item = ++handled < QUEUE ? inbox.poll() : null;
                }
                try {
                    sweep(clock.getAsLong());
                } catch (RuntimeException failure) {
                    failed(failure);
                }
                if (idle()) return;
            }
        } catch (InterruptedException interrupted) {
            synchronized (this) {
                thread = null;
            }
        } catch (Throwable failure) {
            synchronized (this) {
                thread = null;
            }
            endAll("failed");
            failed(failure);
        }
    }

    /** Exits the thread when nothing is left to do; under the lock, so a watch or command starting now restarts it. */
    private synchronized boolean idle() {
        if (!watches.isEmpty() || !known.isEmpty() || !cuts.isEmpty() || !commands.isEmpty() || !inbox.isEmpty()) {
            return false;
        }
        thread = null;
        ringBytes = 0;
        return true;
    }

    private void runCommands() {
        Runnable command;
        while ((command = commands.poll()) != null) {
            commandCount.decrementAndGet();
            try {
                command.run();
            } catch (RuntimeException failure) {
                failed(failure);
            }
        }
    }

    private void handle(Object item) {
        try {
            switch (item) {
                case DiagWatch.Raw raw -> captured(raw);
                case DiagWatch.TickEnd end -> {
                    if (!end.watch().ended && end.watch().ring != null)
                        end.watch().ring.flags(end.seq(), end.flags());
                }
                default -> {}
            }
        } catch (RuntimeException failure) {
            failed(failure);
        }
    }

    private void captured(DiagWatch.Raw raw) {
        DiagWatch watch = raw.watch();
        if (watch.ended) return;
        if (watch.ring == null) watch.ring = new DiagRing(watch.ringTicks, watch.ringBytes);
        if (watch.firstSeq == Long.MIN_VALUE) watch.firstSeq = raw.seq();
        long start = System.nanoTime();
        byte[] full = null;
        int chars = 0;
        if (raw.state() != null) {
            String text = text(raw.state(), raw.grid());
            chars = text.length();
            full = compress(text);
        }
        costs.add(raw.nanos(), System.nanoTime() - start, chars, full == null ? 0 : full.length);
        watch.ring.add(raw.seq(), raw.offset(), raw.trace(), full);
        watch.captureNanos[watch.captures++ % CAPTURE_WINDOW] = raw.nanos();
        // The first window is the JIT's: its interpreted captures say nothing about the steady cost.
        if (watch.captures % CAPTURE_WINDOW == 0
                && watch.captures > CAPTURE_WINDOW
                && p99(watch.captureNanos) > captureLimitNanos) {
            end(watch, "failed");
        }
    }

    /** A tick's full text: its state as the packet thread printed it, then the block grid. */
    static String text(String state, BlockGrid grid) {
        StringBuilder sb = new StringBuilder(state.length() + 16384);
        sb.append(state);
        if (grid != null) grid.appendTo(sb);
        return sb.toString();
    }

    /** One Zstandard frame. */
    static byte[] compress(String text) {
        return Zstd.compress(text.getBytes(StandardCharsets.UTF_8), ZSTD_LEVEL);
    }

    static long p99(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[(sorted.length * 99 - 1) / 100];
    }

    private void arrived(UUID player, Cut cut, String header, DiagWatch watch) {
        long generation = watch.generation;
        if (cuts.size() >= MAX_PENDING_CUTS) {
            answer(generation, player, cut, DiagCuts.Coverage.none("busy"));
            return;
        }
        if (!takeBundle(clock.getAsLong())) {
            answer(generation, player, cut, DiagCuts.Coverage.none("rate"));
            return;
        }
        cuts.add(new Pending(player, cut, header, watch, clock.getAsLong() + CUT_WAIT_NANOS));
    }

    /** The {@code bundles_per_hour} bucket, full at first. */
    private boolean takeBundle(long now) {
        int perHour = limits.bundlesPerHour();
        if (rateTokens < 0) rateTokens = perHour;
        else rateTokens = Math.min(perHour, rateTokens + (now - rateRefilled) * 1e-9 * perHour / 3600.0);
        rateRefilled = now;
        if (rateTokens < 1) return false;
        rateTokens--;
        return true;
    }

    private void sweep(long now) {
        if (tripped) endAll("failed");
        for (DiagWatch watch : known) {
            if (!watch.ended && now - watch.leaseEndNanos >= 0) end(watch, "lease-ended");
        }
        for (Iterator<Pending> pending = cuts.iterator(); pending.hasNext(); ) {
            Pending cut = pending.next();
            DiagWatch watch = cut.watch();
            DiagRing ring = watch.ring;
            boolean reached = ring != null && ring.newest() >= cut.cut().toSeq();
            if (watch.ended || reached || now - cut.deadline() >= 0) {
                pending.remove();
                finish(cut);
            }
        }
        long bytes = 0;
        for (Iterator<DiagWatch> watching = known.iterator(); watching.hasNext(); ) {
            DiagWatch watch = watching.next();
            if (watch.ended && !waitedOn(watch)) {
                watch.ring = null;
                watching.remove();
            } else if (watch.ring != null) {
                bytes += watch.ring.bytes();
            }
        }
        ringBytes = bytes;
    }

    private boolean waitedOn(DiagWatch watch) {
        for (Pending cut : cuts) if (cut.watch() == watch) return true;
        return false;
    }

    private void finish(Pending pending) {
        DiagWatch watch = pending.watch();
        Cut cut = pending.cut();
        if (watch.generation != generation || !offered) return;
        List<DiagRing.Tick> range = watch.ring == null ? List.of() : watch.ring.range(cut.fromSeq(), cut.toSeq());
        DiagCuts.Coverage coverage = DiagCuts.coverage(cut, watch, range);
        String history = watch.firstSeq > cut.fromSeq() || watch.firstSeq == Long.MIN_VALUE ? watch.history : null;
        Limits limits = this.limits;
        List<byte[]> parts = DiagCuts.parts(
                pending.player(),
                cut,
                pending.header(),
                history,
                range,
                coverage,
                limits.partBytes(),
                limits.bundleBytes());
        send(watch.generation, pending.player(), cut, parts, coverage);
    }

    private void answer(long generation, UUID player, Cut cut, DiagCuts.Coverage coverage) {
        send(generation, player, cut, List.of(DiagCuts.closing(player, cut.bundle(), 0, coverage)), coverage);
    }

    /** Queues a bundle's parts; when they stop fitting, a closing part saying so takes the place of the rest. */
    private void send(long generation, UUID player, Cut cut, List<byte[]> parts, DiagCuts.Coverage coverage) {
        for (int i = 0; i < parts.size(); i++) {
            if (upload.offer(generation, parts.get(i))) continue;
            DiagCuts.Coverage dropped = i == 0 || coverage.value() == DiagWire.COVERAGE_NONE
                    ? DiagCuts.Coverage.none("dropped")
                    : new DiagCuts.Coverage(DiagWire.COVERAGE_PARTIAL, "dropped");
            upload.force(generation, DiagCuts.closing(player, cut.bundle(), i, dropped));
            bundlesDropped.incrementAndGet();
            return;
        }
        bundlesSent.incrementAndGet();
    }

    /** One status line: counts only, never whom. */
    public String status() {
        String state = tripped ? "tripped" : active() ? "on" : "off";
        long[] costs = costs();
        return String.format(
                Locale.ROOT,
                "Diagnostics %s: %s, watched %d, ring %.1f KiB, bundles sent %d, dropped %d, captures dropped %d,"
                        + " capture p99 %.0f us, render p99 %.0f us",
                serverId,
                state,
                watches.size(),
                ringBytes / 1024.0,
                bundlesSent.get(),
                bundlesDropped.get(),
                droppedCaptures.get(),
                costs[1] / 1000.0,
                costs[3] / 1000.0);
    }

    /** Tests and benches: the recent costs, as {@link Costs#summary}. */
    long[] costs() {
        return costs.summary();
    }

    DiagUpload upload() {
        return upload;
    }

    synchronized boolean threadRunning() {
        return thread != null;
    }

    static void resetTrip() {
        failures.set(0);
        tripped = false;
    }

    static boolean tripped() {
        return tripped;
    }
}
