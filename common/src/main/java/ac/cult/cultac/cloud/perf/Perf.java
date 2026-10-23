package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.diag.DiagUpload;
import ac.cult.cultac.cloud.diag.Limits;
import ac.cult.cultac.cloud.intake.v3.PerfLimits;
import ac.cult.cultac.cloud.intake.v3.PerfOutcome;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import com.google.protobuf.ByteString;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The intake service's perf profiles for the open socket, one recording at a time. A {@code PerfStart} hands its recording to the
 * {@code cult-perf} daemon thread, started when one is accepted and gone when none waits; that thread slices the
 * zstd profile into {@code PerfPart}s for {@link DiagUpload} to pace onto the socket. A refused start is answered with
 * one last part without data. A recording ends with its socket, and no part of it is queued after that.
 */
public final class Perf {
    /** How long the perf thread waits for room in the upload before it offers a part again. */
    private static final long ROOM_WAIT_MILLIS = 50;
    /** How long one part may wait for room before the profile ends {@code FAILED}: the service's grace for a profile. */
    static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(120);

    private static final LogThrottle FAILURES = new LogThrottle();

    /** Records one window: {@link PerfRecorder#record}, or a call that creates the recorder first. */
    @FunctionalInterface
    public interface Recorder {
        PerfRecorder.Result record(PerfSettings settings, BooleanSupplier cancelled);
    }

    private static final class Job {
        final long generation;
        final UUID profile;
        final PerfSettings settings;
        final int partBytes;
        volatile boolean cancelled;
        /** The index of the next part; perf thread only. */
        int index;

        Job(long generation, UUID profile, PerfSettings settings, int partBytes) {
            this.generation = generation;
            this.profile = profile;
            this.settings = settings;
            this.partBytes = partBytes;
        }
    }

    private final DiagUpload upload;
    private final Recorder recorder;
    private final LongSupplier nanos;
    private final ThreadFactory threads;

    // Guarded by this.
    private long generation = -1;
    private PerfLimits limits;
    private boolean closed;
    /** The accepted recording nobody has cancelled; while it exists every other start is busy. */
    private Job job;
    /** The accepted recording the perf thread has not taken yet. */
    private Job waiting;

    private Thread thread;

    public Perf(DiagUpload upload, PerfRecorder recorder) {
        this(upload, recorder::record);
    }

    public Perf(DiagUpload upload, Recorder recorder) {
        this(upload, recorder, System::nanoTime);
    }

    Perf(DiagUpload upload, Recorder recorder, LongSupplier nanos) {
        this(
                upload,
                recorder,
                nanos,
                Thread.ofPlatform().daemon().name("cult-perf").factory());
    }

    Perf(DiagUpload upload, Recorder recorder, LongSupplier nanos, ThreadFactory threads) {
        this.upload = upload;
        this.recorder = recorder;
        this.nanos = nanos;
        this.threads = threads;
    }

    /**
     * A socket of {@code generation} opened; {@code limitsOrNull} is what connect answered for perf, null when the
     * service took none. A recording for an older socket is dropped.
     */
    public synchronized void connected(long generation, PerfLimits limitsOrNull) {
        drop();
        upload.clear();
        this.generation = generation;
        limits = limitsOrNull;
        upload.rate(uploadBytesPerSecond(limitsOrNull));
    }

    /** The service's new limits for the socket of {@code generation}; disabled refuses new starts, not the running one. */
    public synchronized void limits(long generation, PerfLimits limits) {
        if (generation != this.generation || this.limits == null) return;
        this.limits = limits;
        upload.rate(uploadBytesPerSecond(limits));
    }

    /** The service's {@code PerfStart} on the socket of {@code generation}: records {@code seconds}, or refuses at once. */
    public synchronized void start(long generation, UUID profile, int seconds) {
        if (closed || generation != this.generation) return;
        if (limits == null || !limits.getEnabled()) {
            refuse(profile, PerfOutcome.PERF_OUTCOME_DISABLED, "disabled");
            return;
        }
        if (job != null) {
            refuse(profile, PerfOutcome.PERF_OUTCOME_BUSY, "busy");
            return;
        }
        job = new Job(generation, profile, PerfSettings.of(limits, seconds), partBytes(limits));
        waiting = job;
        if (thread == null) startThread();
    }

    /** Starts the perf thread for the waiting recording; one that cannot start ends that recording {@code FAILED}. */
    private void startThread() {
        try {
            Thread started = threads.newThread(this::run);
            started.start();
            thread = started;
        } catch (Throwable failure) {
            String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            FAILURES.warn("perf", "Cloud perf thread did not start; the anticheat is unaffected: " + reason, null);
            UUID profile = waiting.profile;
            drop();
            refuse(profile, PerfOutcome.PERF_OUTCOME_FAILED, reason);
        }
    }

    /** The socket of {@code generation} is gone, and with it its recording and every part not yet sent. */
    public synchronized void socketClosed(long generation) {
        if (generation != this.generation) return;
        this.generation = -1;
        limits = null;
        drop();
        upload.clear();
    }

    /** Plugin disable: the recording stops and the thread exits once the recorder returns. */
    public synchronized void close() {
        closed = true;
        limits = null;
        drop();
        upload.clear();
    }

    private void drop() {
        if (job == null) return;
        job.cancelled = true;
        job = null;
        waiting = null;
    }

    private void refuse(UUID profile, PerfOutcome outcome, String reason) {
        upload.force(generation, PerfWire.record(PerfWire.part(profile, 0, ByteString.EMPTY, true, outcome, reason)));
    }

    private void run() {
        try {
            Job next;
            while ((next = take()) != null) {
                try {
                    record(next);
                } catch (GaveUp gaveUp) {
                    fail(next, gaveUp.getMessage());
                } catch (Throwable failure) {
                    String reason = failure.getClass().getSimpleName();
                    try {
                        reason += ": " + failure.getMessage();
                        FAILURES.warn(
                                "perf", "Cloud perf recording failed; the anticheat is unaffected: " + reason, null);
                    } finally {
                        fail(next, reason);
                    }
                }
            }
        } finally {
            exited();
        }
    }

    /** A thread that ends abnormally hands a waiting recording to a new one, so no start is left behind. */
    private synchronized void exited() {
        if (thread != Thread.currentThread()) return;
        thread = null;
        if (waiting != null && !closed) startThread();
    }

    /** Ends the job with a last part {@code FAILED} after the parts it queued, unless it was cancelled. */
    private synchronized void fail(Job job, String reason) {
        if (job.cancelled) return;
        try {
            upload.force(
                    job.generation,
                    PerfWire.record(PerfWire.part(
                            job.profile, job.index, ByteString.EMPTY, true, PerfOutcome.PERF_OUTCOME_FAILED, reason)));
        } finally {
            finished(job);
        }
    }

    /** The next recording; with none the thread is gone, under the lock, so a start now starts another. */
    private synchronized Job take() {
        Job next = waiting;
        waiting = null;
        if (next == null) thread = null;
        return next;
    }

    private synchronized void finished(Job done) {
        if (job == done) job = null;
    }

    private void record(Job job) throws GaveUp {
        switch (recorder.record(job.settings, () -> job.cancelled)) {
            case PerfRecorder.Result.Done done -> send(job, done.zstdProfile(), done.reason());
            case PerfRecorder.Result.Skipped skipped ->
                offer(
                        job,
                        PerfWire.record(PerfWire.part(
                                job.profile, 0, ByteString.EMPTY, true, skipped.outcome(), skipped.reason())),
                        true);
        }
    }

    /** The profile in slices of the job's part size, from index 0; the last carries {@code OK} and {@code reason}. */
    private void send(Job job, byte[] profile, String reason) throws GaveUp {
        int parts = Math.max(1, Math.ceilDiv(profile.length, job.partBytes));
        for (int i = 0; i < parts; i++) {
            int from = i * job.partBytes;
            int length = Math.min(profile.length - from, job.partBytes);
            byte[] record = PerfWire.record(PerfWire.part(
                    job.profile,
                    i,
                    ByteString.copyFrom(profile, from, length),
                    i == parts - 1,
                    PerfOutcome.PERF_OUTCOME_OK,
                    i == parts - 1 ? reason : ""));
            if (!offer(job, record, i == parts - 1)) return;
        }
    }

    /**
     * Queues a part once the upload has room for it; false, with nothing queued, once the job is cancelled. Gives up
     * when the upload has no room for {@link #STALL_NANOS} or the thread is interrupted. The job ends as its last part
     * is queued, so a start answering it is not busy.
     */
    private boolean offer(Job job, byte[] record, boolean last) throws GaveUp {
        long since = nanos.getAsLong();
        while (true) {
            synchronized (this) {
                if (job.cancelled) return false;
                if (upload.offer(job.generation, record)) {
                    job.index++;
                    if (last) finished(job);
                    return true;
                }
            }
            if (nanos.getAsLong() - since > STALL_NANOS) throw new GaveUp("stalled");
            try {
                Thread.sleep(ROOM_WAIT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GaveUp("interrupted");
            }
        }
    }

    /** A job ends without its profile; the message is the last part's reason. */
    private static final class GaveUp extends Exception {
        GaveUp(String reason) {
            super(reason, null, false, false);
        }
    }

    /** {@code part_bytes} read unsigned: 0 takes the default, everything else is clamped to diagnostics' part range. */
    static int partBytes(PerfLimits limits) {
        return (int) clamp(limits.getPartBytes(), Limits.MAX_PART_BYTES, Limits.MIN_PART_BYTES, Limits.MAX_PART_BYTES);
    }

    /** {@code upload_bytes_per_second} read unsigned: 0 or no limits take the default, the rest is clamped. */
    static int uploadBytesPerSecond(PerfLimits limits) {
        int fallback = Limits.OFF.uploadBytesPerSecond();
        if (limits == null) return fallback;
        return (int) clamp(limits.getUploadBytesPerSecond(), fallback, 1024, Limits.MAX_UPLOAD_BYTES_PER_SECOND);
    }

    private static long clamp(int value, long fallback, long min, long max) {
        long unsigned = Integer.toUnsignedLong(value);
        return Math.min(Math.max(unsigned == 0 ? fallback : unsigned, min), max);
    }
}
