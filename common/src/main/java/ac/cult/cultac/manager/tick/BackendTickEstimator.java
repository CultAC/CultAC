package ac.cult.cultac.manager.tick;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Best-effort server tick boundaries for a connection that cannot observe the server's tick loop.
 *
 * <p>Paper suspends player connection flushing for the whole of {@code MinecraftServer#tickChildren}
 * and flushes once after sending chunks, so each tick normally leaves the server as a single burst.
 * In {@link Mode#FLUSH} the end of each complete burst is a tick end. Two waves in one burst are
 * split where the second begins: an entity tracked twice ({@code ServerEntity#sendChanges} runs once
 * per tick) or the every-20-ticks time synchronization at the start of {@code tickChildren}.
 *
 * <p>The time synchronization carries the game time, so each 20-tick window measures how often
 * bursts had to be split. When merging is frequent the estimator switches to {@link Mode#TIMER}: a
 * timer at the tick rate, phase-locked to where complete bursts usually end. Extra boundaries only
 * add proof transactions, so they are harmless; this class never suppresses an observed burst end
 * in flush mode. Owner-thread only.
 */
public final class BackendTickEstimator implements AutoCloseable {
    public enum Mode {
        FLUSH,
        TIMER
    }

    /** Observed tracking waves only; tick ends without tracking evidence do not inflate the hit rate. */
    public record TickEndStats(long hits, long misses, long emitted) {
        public long observedWaves() {
            return hits + misses;
        }

        public double hitPercent() {
            return observedWaves() == 0 ? Double.NaN : 100D * hits / observedWaves();
        }

        public double missPercent() {
            return observedWaves() == 0 ? Double.NaN : 100D * misses / observedWaves();
        }
    }

    /** Schedules on the connection owner; the returned handle cancels. */
    @FunctionalInterface
    public interface Scheduler {
        AutoCloseable schedule(Runnable task, long delayNanos);
    }

    /** The backend's current tick period in nanoseconds, or a non-positive value while frozen. */
    @FunctionalInterface
    public interface TickPeriod {
        long nanos();
    }

    private static final int PHASE_SAMPLES = 40;
    private static final int UNSTABLE_WINDOWS_TO_TIMER = 2;
    private static final int STABLE_WINDOWS_TO_FLUSH = 3;
    // A window is unstable when more than this fraction of its ticks needed a merge split.
    private static final double MAX_MERGE_FRACTION = 0.05;
    private static final int MAX_WINDOW_TICKS = 200;

    private final Runnable tickEnd;
    private final Scheduler scheduler;
    private final TickPeriod period;
    private final Runnable sourceCheck;
    private final IntOpenHashSet wave = new IntOpenHashSet();
    // Unlike wave (which measures burst merging), this survives a burst that emits no tick end.
    private final IntOpenHashSet unendedTrackingWave = new IntOpenHashSet();
    private long tickEndHits;
    private long tickEndMisses;
    private long emittedTickEnds;
    private final long[] phaseSamples = new long[PHASE_SAMPLES];
    private int phaseSampleCount;
    private int phaseSampleNext;

    private Mode mode = Mode.FLUSH;
    private long windowGameTime = Long.MIN_VALUE;
    private long windowStartNanos;
    private volatile double ticksPerSecond = Double.NaN;
    private int windowMerges;
    private int unstableWindows;
    private int stableWindows;
    private AutoCloseable timer;
    private long timerDeadline;
    private boolean closed;
    private final Runnable onTimer = this::onTimer;
    private final Runnable onFrozenTimer = () -> {
        timer = null;
        if (mode == Mode.TIMER) scheduleTimer(timerDeadline);
    };

    public BackendTickEstimator(Runnable tickEnd, Scheduler scheduler, TickPeriod period) {
        this(tickEnd, scheduler, period, () -> {});
    }

    /** {@code sourceCheck} runs at each time synchronization so the platform can follow backend switches. */
    public BackendTickEstimator(Runnable tickEnd, Scheduler scheduler, TickPeriod period, Runnable sourceCheck) {
        this.tickEnd = tickEnd;
        this.scheduler = scheduler;
        this.period = period;
        this.sourceCheck = sourceCheck;
    }

    public Mode mode() {
        return mode;
    }

    /** Connection-owner snapshot, accumulated since attach or the last debug reset. */
    public TickEndStats tickEndStats() {
        return new TickEndStats(tickEndHits, tickEndMisses, emittedTickEnds);
    }

    /** Resets diagnostics only; the estimator's mode, wave and timer are unchanged. Owner thread only. */
    public void resetTickEndStats() {
        tickEndHits = 0;
        tickEndMisses = 0;
        emittedTickEnds = 0;
        unendedTrackingWave.clear();
    }

    /** The backend's measured tick rate from successive time synchronizations; NaN until measured. */
    public double ticksPerSecond() {
        return ticksPerSecond;
    }

    /** The backend stream stopped; {@code partialFrame} when the read ended inside a frame. */
    public void onBurstEnd(boolean partialFrame, long now) {
        if (closed || partialFrame) return;
        recordPhase(now);
        if (mode == Mode.FLUSH) fire();
        else if (timer != null && Math.abs(timerDeadline - now) <= toleranceNanos()) {
            // The observed burst end is the tick end the timer predicted; use it instead.
            cancelTimer();
            fire();
            scheduleTimer(now);
        } else {
            // Keep measuring whether bursts still hold exactly one tick.
            wave.clear();
        }
    }

    /** A tracked entity's movement for this tick is about to be sent. */
    public void onEntityTracked(int entityId, long now) {
        if (closed) return;
        // ServerEntity#sendChanges emits at most one tracked movement per entity per tick.
        // A repeat proves another wave began before we ended the previous one. Record the
        // miss BEFORE a corrective split fires, then start observing the new wave afterward.
        if (unendedTrackingWave.contains(entityId)) missTrackingWave();
        if (!wave.add(entityId)) {
            splitMergedTick();
            wave.add(entityId);
        }
        unendedTrackingWave.add(entityId);
    }

    /** The every-20-ticks time synchronization is about to be sent; it starts a server tick. */
    public void onGameTime(long gameTime, long now) {
        if (closed) return;
        sourceCheck.run();
        // Time synchronization starts a server tick, so any unended tracking wave is late.
        if (!unendedTrackingWave.isEmpty()) missTrackingWave();
        if (!wave.isEmpty()) splitMergedTick();
        if (windowGameTime != Long.MIN_VALUE) {
            long ticks = gameTime - windowGameTime;
            if (ticks > 0 && ticks <= MAX_WINDOW_TICKS) {
                evaluate(ticks, now);
                long elapsed = now - windowStartNanos;
                if (elapsed > 0) {
                    double rate = ticks * 1_000_000_000D / elapsed;
                    double previous = ticksPerSecond;
                    ticksPerSecond = Double.isNaN(previous) ? rate : previous * 0.8 + rate * 0.2;
                }
            }
        }
        windowGameTime = gameTime;
        windowStartNanos = now;
        windowMerges = 0;
    }

    private void splitMergedTick() {
        windowMerges++;
        if (mode == Mode.FLUSH) fire();
        else wave.clear();
    }

    private void evaluate(long ticks, long now) {
        boolean stable = windowMerges <= Math.max(1, ticks * MAX_MERGE_FRACTION);
        if (stable) {
            unstableWindows = 0;
            if (mode == Mode.TIMER && ++stableWindows >= STABLE_WINDOWS_TO_FLUSH) {
                mode = Mode.FLUSH;
                cancelTimer();
            }
        } else {
            stableWindows = 0;
            if (mode == Mode.FLUSH && ++unstableWindows >= UNSTABLE_WINDOWS_TO_TIMER) {
                mode = Mode.TIMER;
                scheduleTimer(now);
            }
        }
    }

    private void fire() {
        emittedTickEnds++;
        if (!unendedTrackingWave.isEmpty()) {
            tickEndHits++;
            unendedTrackingWave.clear();
        }
        wave.clear();
        tickEnd.run();
    }

    private void missTrackingWave() {
        tickEndMisses++;
        unendedTrackingWave.clear();
    }

    private void onTimer() {
        timer = null;
        if (closed || mode != Mode.TIMER) return;
        fire();
        scheduleTimer(timerDeadline);
    }

    private void scheduleTimer(long now) {
        cancelTimer();
        long tick = period.nanos();
        if (closed || tick <= 0) {
            // Frozen: no ticks run. Re-check after a nominal tick.
            if (!closed) {
                timerDeadline = now + 50_000_000L;
                timer = scheduler.schedule(onFrozenTimer, 50_000_000L);
            }
            return;
        }
        long phase = phase(tick);
        long next = now - Math.floorMod(now - phase, tick) + tick;
        timerDeadline = next;
        timer = scheduler.schedule(onTimer, Math.max(0, next - now));
    }

    private void cancelTimer() {
        if (timer == null) return;
        try {
            timer.close();
        } catch (Exception ignored) {
            // A cancelled or completed task has nothing left to release.
        }
        timer = null;
    }

    private void recordPhase(long now) {
        phaseSamples[phaseSampleNext] = now;
        phaseSampleNext = (phaseSampleNext + 1) % PHASE_SAMPLES;
        if (phaseSampleCount < PHASE_SAMPLES) phaseSampleCount++;
    }

    /** The circular mean of recent burst ends modulo the tick period. */
    private long phase(long tick) {
        if (phaseSampleCount == 0) return 0;
        double sin = 0;
        double cos = 0;
        for (int i = 0; i < phaseSampleCount; i++) {
            double angle = 2 * Math.PI * Math.floorMod(phaseSamples[i], tick) / tick;
            sin += Math.sin(angle);
            cos += Math.cos(angle);
        }
        double angle = Math.atan2(sin, cos);
        if (angle < 0) angle += 2 * Math.PI;
        return (long) (angle / (2 * Math.PI) * tick);
    }

    private long toleranceNanos() {
        return Math.max(1, period.nanos() / 5);
    }

    @Override
    public void close() {
        closed = true;
        cancelTimer();
    }
}
