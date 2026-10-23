package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.diag.DiagUpload;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.PerfLimits;
import ac.cult.cultac.cloud.intake.v3.PerfOutcome;
import ac.cult.cultac.cloud.intake.v3.PerfPart;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PerfTest {
    private static final PerfLimits ON =
            PerfLimits.newBuilder().setEnabled(true).setPartBytes(32 * 1024).build();
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private long pollNanos;

    @Test
    void sendsSlicedPartsEndingWithOk() throws Exception {
        byte[] blob = blob(70 * 1024);
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, (settings, cancelled) -> new PerfRecorder.Result.Done(blob));
        UUID profile = UUID.randomUUID();
        perf.connected(1, ON);

        perf.start(1, profile, 60);

        List<PerfPart> parts = drain(upload, 1);
        assertEquals(List.of(0, 1, 2), parts.stream().map(PerfPart::getIndex).toList());
        assertFalse(parts.get(0).getLast());
        assertFalse(parts.get(1).getLast());
        assertTrue(parts.get(2).getLast());
        assertEquals(PerfOutcome.PERF_OUTCOME_UNSPECIFIED, parts.get(0).getOutcome());
        assertEquals(PerfOutcome.PERF_OUTCOME_UNSPECIFIED, parts.get(1).getOutcome());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, parts.get(2).getOutcome());
        assertEquals(32 * 1024, parts.get(0).getData().size());
        assertEquals(32 * 1024, parts.get(1).getData().size());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (PerfPart part : parts) {
            assertEquals(uuid(profile), part.getProfile());
            part.getData().writeTo(joined);
        }
        assertArrayEquals(blob, joined.toByteArray());
        perf.close();
    }

    @Test
    void theLastPartCarriesTheProfileReason() throws Exception {
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(
                upload, (settings, cancelled) -> new PerfRecorder.Result.Done(blob(40 * 1024), "nodes=9 capped=2"));
        perf.connected(1, ON);

        perf.start(1, UUID.randomUUID(), 60);

        List<PerfPart> parts = drain(upload, 1);
        assertEquals("", parts.getFirst().getReason());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, parts.getLast().getOutcome());
        assertEquals("nodes=9 capped=2", parts.getLast().getReason());
        perf.close();
    }

    @Test
    void aFailureWhoseMessageThrowsStillEndsTheJob() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, (settings, cancelled) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException() {
                    @Override
                    public String getMessage() {
                        throw new UnsupportedOperationException("no message");
                    }
                };
            }
            return new PerfRecorder.Result.Done(new byte[10]);
        });
        perf.connected(1, ON);

        perf.start(1, UUID.randomUUID(), 60);
        PerfPart failed = drain(upload, 1).getFirst();
        perf.start(1, UUID.randomUUID(), 60);
        PerfPart ok = drain(upload, 1).getFirst();

        assertTrue(failed.getLast());
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, failed.getOutcome());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, ok.getOutcome());
        perf.close();
    }

    @Test
    void secondStartWhileRecordingIsBusy() throws Exception {
        Blocking recorder = new Blocking(blob(1024));
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, recorder);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        perf.connected(1, ON);

        perf.start(1, first, 60);
        assertTrue(recorder.entered.await(5, TimeUnit.SECONDS));
        perf.start(1, second, 60);

        PerfPart busy = drain(upload, 1).getFirst();
        assertEquals(uuid(second), busy.getProfile());
        assertEquals(0, busy.getIndex());
        assertTrue(busy.getLast());
        assertTrue(busy.getData().isEmpty());
        assertEquals(PerfOutcome.PERF_OUTCOME_BUSY, busy.getOutcome());
        recorder.release.countDown();
        PerfPart done = drain(upload, 1).getLast();
        assertEquals(uuid(first), done.getProfile());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, done.getOutcome());
        assertEquals(1, recorder.calls.get());
        assertEquals(1, recorder.maxConcurrent.get());
        perf.close();
    }

    @Test
    void disabledLimitsRefuseWithDisabled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, (settings, cancelled) -> {
            calls.incrementAndGet();
            return new PerfRecorder.Result.Done(new byte[1]);
        });
        UUID refused = UUID.randomUUID();
        perf.connected(1, ON);
        perf.limits(1, ON.toBuilder().setEnabled(false).build());

        perf.start(1, refused, 60);

        PerfPart part = drain(upload, 1).getFirst();
        assertEquals(uuid(refused), part.getProfile());
        assertTrue(part.getLast());
        assertTrue(part.getData().isEmpty());
        assertEquals(PerfOutcome.PERF_OUTCOME_DISABLED, part.getOutcome());
        assertEquals(0, calls.get());
        perf.close();
    }

    @Test
    void reconnectDropsTheProfile() throws Exception {
        Blocking recorder = new Blocking(blob(1024));
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, recorder);
        UUID dropped = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        perf.connected(1, ON);
        perf.start(1, dropped, 60);
        assertTrue(recorder.entered.await(5, TimeUnit.SECONDS));

        perf.socketClosed(1);
        assertTrue(recorder.cancelSeen.await(5, TimeUnit.SECONDS));
        recorder.release.countDown();
        Thread thread = recorder.thread.get();
        thread.join(5_000);
        assertFalse(thread.isAlive());
        assertFalse(upload.pending());

        perf.connected(2, ON);
        perf.start(2, next, 60);

        List<PerfPart> parts = drain(upload, 2);
        assertTrue(parts.stream().allMatch(part -> part.getProfile().equals(uuid(next))), parts::toString);
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, parts.getLast().getOutcome());
        assertEquals(2, recorder.calls.get());
        perf.close();
    }

    @Test
    void startAfterReconnectWaitsForTheCancelledRecording() throws Exception {
        Blocking recorder = new Blocking(blob(1024));
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, recorder);
        UUID next = UUID.randomUUID();
        perf.connected(1, ON);
        perf.start(1, UUID.randomUUID(), 60);
        assertTrue(recorder.entered.await(5, TimeUnit.SECONDS));

        perf.connected(2, ON);
        assertTrue(recorder.cancelSeen.await(5, TimeUnit.SECONDS));
        perf.start(2, next, 60);
        recorder.release.countDown();

        List<PerfPart> parts = drain(upload, 2);
        assertTrue(parts.stream().allMatch(part -> part.getProfile().equals(uuid(next))), parts::toString);
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, parts.getLast().getOutcome());
        assertEquals(2, recorder.calls.get());
        assertEquals(1, recorder.maxConcurrent.get());
        perf.close();
    }

    @Test
    void closeEndsTheThread() throws Exception {
        Blocking recorder = new Blocking(blob(1024), true);
        recorder.release.countDown();
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, recorder);
        perf.connected(1, ON);
        perf.start(1, UUID.randomUUID(), 60);
        assertTrue(recorder.entered.await(5, TimeUnit.SECONDS));
        Thread thread = recorder.thread.get();
        assertEquals("cult-perf", thread.getName());
        assertTrue(thread.isDaemon());

        perf.close();
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertFalse(upload.pending());
        perf.start(1, UUID.randomUUID(), 60);
        assertFalse(upload.pending());
        assertEquals(1, recorder.calls.get());
    }

    @Test
    void recorderLinkageErrorFailsTheStartAndPerfStaysUsable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, (settings, cancelled) -> {
            if (calls.incrementAndGet() == 1) throw new NoClassDefFoundError("jdk/jfr/consumer/RecordingStream");
            return new PerfRecorder.Result.Done(new byte[10]);
        });
        perf.connected(1, ON);

        perf.start(1, UUID.randomUUID(), 60);
        PerfPart failed = drain(upload, 1).getFirst();
        perf.start(1, UUID.randomUUID(), 60);
        PerfPart ok = drain(upload, 1).getFirst();

        assertTrue(failed.getLast());
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, failed.getOutcome());
        assertTrue(failed.getReason().contains("NoClassDefFoundError"), failed.getReason());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, ok.getOutcome());
        perf.close();
    }

    @Test
    void recorderErrorFailsTheStartAndTheNextStartCompletes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, (settings, cancelled) -> {
            if (calls.incrementAndGet() == 1) throw new StackOverflowError("deep");
            return new PerfRecorder.Result.Done(new byte[10]);
        });
        perf.connected(1, ON);

        perf.start(1, UUID.randomUUID(), 60);
        PerfPart failed = drain(upload, 1).getFirst();
        perf.start(1, UUID.randomUUID(), 60);
        PerfPart ok = drain(upload, 1).getFirst();

        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, failed.getOutcome());
        assertTrue(failed.getReason().contains("StackOverflowError"), failed.getReason());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, ok.getOutcome());
        assertEquals(2, calls.get());
        perf.close();
    }

    @Test
    void aThreadThatCannotStartFailsTheStartAndTheNextStartCompletes() throws Exception {
        AtomicInteger made = new AtomicInteger();
        ThreadFactory threads = task -> made.incrementAndGet() == 1
                ? new Thread(task) {
                    @Override
                    public void start() {
                        throw new InternalError("unable to create native thread");
                    }
                }
                : Thread.ofPlatform().daemon().unstarted(task);
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(
                upload, (settings, cancelled) -> new PerfRecorder.Result.Done(new byte[10]), System::nanoTime, threads);
        perf.connected(1, ON);
        UUID refused = UUID.randomUUID();

        perf.start(1, refused, 60);
        PerfPart failed = drain(upload, 1).getFirst();
        perf.start(1, UUID.randomUUID(), 60);
        PerfPart ok = drain(upload, 1).getFirst();

        assertEquals(uuid(refused), failed.getProfile());
        assertTrue(failed.getLast());
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, failed.getOutcome());
        assertTrue(failed.getReason().contains("InternalError"), failed.getReason());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, ok.getOutcome());
        assertEquals(2, made.get());
        perf.close();
    }

    @Test
    void anUploadWithoutRoomStallsTheProfileAndFreesPerf() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicReference<Thread> thread = new AtomicReference<>();
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(
                upload,
                (settings, cancelled) -> {
                    thread.compareAndSet(null, Thread.currentThread());
                    return new PerfRecorder.Result.Done(blob(70 * 1024));
                },
                () -> clock.addAndGet(TimeUnit.SECONDS.toNanos(10)));
        perf.connected(1, ON);
        while (upload.offer(99, new byte[32 * 1024])) {}
        UUID stalled = UUID.randomUUID();

        perf.start(1, stalled, 60);
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (thread.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
        thread.get().join(5_000);
        assertFalse(thread.get().isAlive());
        PerfPart failed = drain(upload, 1).getFirst();
        perf.start(1, UUID.randomUUID(), 60);
        List<PerfPart> next = drain(upload, 1);

        assertEquals(uuid(stalled), failed.getProfile());
        assertEquals(0, failed.getIndex());
        assertTrue(failed.getLast());
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, failed.getOutcome());
        assertEquals("stalled", failed.getReason());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, next.getLast().getOutcome());
        perf.close();
    }

    @Test
    void disabledMidRecordingLetsItFinishAndRefusesTheNext() throws Exception {
        Blocking recorder = new Blocking(blob(1024));
        DiagUpload upload = new DiagUpload();
        Perf perf = new Perf(upload, recorder);
        UUID running = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        perf.connected(1, ON);
        perf.start(1, running, 60);
        assertTrue(recorder.entered.await(5, TimeUnit.SECONDS));

        perf.limits(1, ON.toBuilder().setEnabled(false).build());
        recorder.release.countDown();
        PerfPart done = drain(upload, 1).getLast();
        perf.start(1, refused, 60);
        PerfPart disabled = drain(upload, 1).getFirst();

        assertEquals(uuid(running), done.getProfile());
        assertEquals(PerfOutcome.PERF_OUTCOME_OK, done.getOutcome());
        assertEquals(uuid(refused), disabled.getProfile());
        assertEquals(PerfOutcome.PERF_OUTCOME_DISABLED, disabled.getOutcome());
        assertEquals(1, recorder.calls.get());
        perf.close();
    }

    /** Parts the upload hands the socket of {@code generation}, until a last part or a quiet second after one. */
    private List<PerfPart> drain(DiagUpload upload, long generation) throws Exception {
        List<PerfPart> parts = new ArrayList<>();
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (System.nanoTime() < deadline) {
            byte[] record = upload.poll(generation, pollNanos += TimeUnit.SECONDS.toNanos(10));
            if (record == null) {
                Thread.sleep(5);
                continue;
            }
            PerfPart part = part(record);
            parts.add(part);
            if (part.getLast()) return parts;
        }
        throw new AssertionError("no last part within 5 s: " + parts);
    }

    private static PerfPart part(byte[] record) throws InvalidProtocolBufferException {
        Intake intake = Intake.parseFrom(record);
        assertEquals(1, intake.getMessagesCount());
        IntakeMessage message = intake.getMessages(0);
        assertTrue(message.hasPerf());
        return message.getPerf();
    }

    private static ByteString uuid(UUID id) {
        return ByteString.copyFrom(ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
    }

    private static byte[] blob(int size) {
        byte[] blob = new byte[size];
        new Random(42).nextBytes(blob);
        return blob;
    }

    /** Blocks each recording until {@link #release}, and with {@code untilCancelled} also until it is cancelled. */
    private static final class Blocking implements Perf.Recorder {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch cancelSeen = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        private final byte[] blob;
        private final boolean untilCancelled;

        Blocking(byte[] blob) {
            this(blob, false);
        }

        Blocking(byte[] blob, boolean untilCancelled) {
            this.blob = blob;
            this.untilCancelled = untilCancelled;
        }

        @Override
        public PerfRecorder.Result record(PerfSettings settings, BooleanSupplier cancelled) {
            calls.incrementAndGet();
            maxConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
            thread.set(Thread.currentThread());
            entered.countDown();
            try {
                while (release.getCount() > 0 || untilCancelled && !cancelled.getAsBoolean()) {
                    if (cancelled.getAsBoolean()) cancelSeen.countDown();
                    Thread.sleep(5);
                }
                if (cancelled.getAsBoolean()) cancelSeen.countDown();
                return new PerfRecorder.Result.Done(blob);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new PerfRecorder.Result.Skipped(PerfOutcome.PERF_OUTCOME_FAILED, "interrupted");
            } finally {
                running.decrementAndGet();
            }
        }
    }
}
