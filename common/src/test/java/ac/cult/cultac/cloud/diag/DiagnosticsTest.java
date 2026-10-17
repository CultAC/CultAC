package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.intake.v3.Intake;
import com.github.luben.zstd.ZstdInputStream;
import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static final UUID OTHER = new UUID(3, 4);
    private static final byte[] BUNDLE = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final Limits ON = Limits.of(true, 400, 0, 0, 0, 0, Limits.MAX_UPLOAD_BYTES_PER_SECOND, 0, 0);

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicInteger diagReads = new AtomicInteger();
    private final DiagUpload upload = new DiagUpload();
    private final List<Diagnostics> opened = new ArrayList<>();

    @AfterEach
    void close() {
        for (Diagnostics diagnostics : opened) diagnostics.close();
        Diagnostics.resetTrip();
    }

    private Diagnostics diagnostics(Limits limits) {
        Diagnostics diagnostics = new Diagnostics(upload, "test", this::now);
        opened.add(diagnostics);
        diagnostics.connected(1, limits);
        return diagnostics;
    }

    private long now() {
        if (Thread.currentThread().getName().startsWith("cult-cloud-diag-")) diagReads.incrementAndGet();
        return clock.get();
    }

    /**
     * Waits until the diag thread has finished the pass it was in and the next whole one: a pass reads the clock at most
     * three times (twice while a cut arrives, once to sweep), so the seventh read starts a later pass.
     */
    private void awaitPasses() {
        int target = diagReads.get() + 7;
        await(() -> diagReads.get() >= target);
    }

    private static DiagWatch watch(Diagnostics diagnostics, UUID player, int lease, AtomicInteger detached) {
        return diagnostics.watch(player, 1, lease, () -> 0, () -> "history", ended -> detached.incrementAndGet());
    }

    @Test
    void limitsClampToTheCompiledMaximaAndZeroTakesTheDefault() {
        Limits huge = Limits.of(true, 1L << 40, 1L << 40, 1L << 40, 1L << 40, 1L << 40, 1L << 40, 1L << 40, 1L << 40);
        assertEquals(Limits.MAX_RING_TICKS, huge.ringTicks());
        assertEquals(Limits.MAX_PLAYER_RING_BYTES, huge.playerRingBytes());
        assertEquals(Limits.MAX_SERVER_RING_BYTES, huge.serverRingBytes());
        assertEquals(Limits.MAX_PLAYERS, huge.maxPlayers());
        assertEquals(Limits.MAX_PART_BYTES, huge.partBytes());
        assertEquals(Limits.MAX_UPLOAD_BYTES_PER_SECOND, huge.uploadBytesPerSecond());
        assertEquals(Limits.MAX_BUNDLE_BYTES, huge.bundleBytes());
        assertEquals(Limits.MAX_BUNDLES_PER_HOUR, huge.bundlesPerHour());
        Limits defaults = Limits.of(false, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(new Limits(false, 400, 1 << 20, 4 << 20, 2, 32768, 32768, 524288, 30), defaults);
    }

    @Test
    void noThreadRunsUntilDiagnosticsAreEnabledAndAWatchStarts() {
        Diagnostics off = diagnostics(Limits.OFF);
        AtomicInteger detached = new AtomicInteger();
        assertNull(watch(off, PLAYER, 60, detached));
        assertFalse(off.threadRunning());
        off.cut(PLAYER, cut(0, 10, 0, 10), "header");
        assertFalse(off.threadRunning());

        Diagnostics notOffered = diagnostics(null);
        assertNull(watch(notOffered, PLAYER, 60, detached));
        assertFalse(notOffered.threadRunning());

        Diagnostics on = diagnostics(ON);
        assertFalse(on.threadRunning());
        on.cut(PLAYER, cut(0, 10, 0, 10), "header");
        assertFalse(on.threadRunning(), "a cut nobody is watched for is answered without the thread");
        assertEquals(DiagCoverage.DIAG_COVERAGE_NONE, parts().get(0).getCoverage());
        assertNotNull(watch(on, PLAYER, 60, detached));
        assertTrue(on.threadRunning());
    }

    @Test
    void watchCapsBlockAtExactlyTheirValues() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics players = diagnostics(Limits.of(true, 0, 0, Limits.MAX_SERVER_RING_BYTES, 2, 0, 0, 0, 0));
        assertNotNull(watch(players, PLAYER, 60, detached));
        assertNotNull(watch(players, OTHER, 60, detached));
        assertNull(watch(players, new UUID(5, 6), 60, detached));
        players.cut(new UUID(5, 6), cut(0, 1, 0, 1), "header");
        assertEquals("busy", parts().get(0).getReason());

        Diagnostics bytes = diagnostics(Limits.of(true, 0, 1 << 20, 2 << 20, 8, 0, 0, 0, 0));
        assertNotNull(watch(bytes, PLAYER, 60, detached));
        assertNotNull(watch(bytes, OTHER, 60, detached));
        assertNull(watch(bytes, new UUID(5, 6), 60, detached));
    }

    @Test
    void aWatchOnAWatchedPlayerRenewsItsLease() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch first = watch(diagnostics, PLAYER, 1, detached);
        assertSame(first, watch(diagnostics, PLAYER, 120, detached));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
        awaitPasses();
        assertFalse(first.ended());
        assertNull(watch(diagnostics, PLAYER, 0, detached));
        assertTrue(first.ended());
        assertEquals(1, detached.get());
    }

    @Test
    void leaseExpiryFreesTheRingAndTheThreadExits() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 1, detached);
        for (int seq = 0; seq < 20; seq++) watch.capture(seq, 0, "t" + seq, "state " + seq, null, System.nanoTime());
        await(() -> watch.ring != null && watch.ring.size() == 20);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        await(watch::ended);
        assertEquals("lease-ended", watch.endReason);
        assertEquals(1, detached.get());
        await(() -> !diagnostics.threadRunning());
        assertNull(watch.ring);
    }

    @Test
    void socketCloseEndsEveryWatchAndDropsQueuedParts() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 60, detached);
        DiagWatch other = watch(diagnostics, OTHER, 60, detached);
        upload.offer(1, new byte[100]);
        diagnostics.socketClosed(1);
        assertTrue(watch.ended() && other.ended());
        assertEquals(2, detached.get());
        assertFalse(upload.pending());
        assertFalse(diagnostics.active());
        await(() -> !diagnostics.threadRunning());
    }

    @Test
    void disablingEndsEveryWatch() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 60, detached);
        diagnostics.limits(1, Limits.OFF);
        assertTrue(watch.ended());
        assertNull(watch(diagnostics, PLAYER, 60, detached));
        await(() -> !diagnostics.threadRunning());
    }

    @Test
    void aCutGetsTracesAndFullTextsForItsRanges() throws IOException {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 60, detached);
        for (int seq = 10; seq <= 20; seq++) {
            watch.capture(seq, seq / 1000.0, "trace " + seq, "state " + seq, null, System.nanoTime());
            if (seq == 12) watch.tickEnd(seq, List.of("Simulation", "Timer"));
        }
        await(() -> watch.ring != null && watch.ring.size() == 11);
        diagnostics.cut(PLAYER, cut(11, 18, 14, 16), "agent test");
        List<DiagPart> parts = awaitParts();
        DiagPart last = parts.get(parts.size() - 1);
        assertTrue(last.getLast());
        assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, last.getCoverage());
        assertEquals("agent test", parts.get(0).getHeader());
        assertEquals("", parts.get(0).getHistory());
        List<DiagTick> ticks = new ArrayList<>();
        for (DiagPart part : parts) {
            assertArrayEquals(BUNDLE, part.getBundle().toByteArray());
            ticks.addAll(part.getTicksList());
        }
        assertEquals(8, ticks.size());
        for (DiagTick tick : ticks) {
            long seq = tick.getSeq();
            assertEquals("trace " + seq, tick.getTrace());
            assertEquals(seq / 1000.0, tick.getOffset());
            boolean full = seq >= 14 && seq <= 16 || seq == 12;
            assertEquals(full, !tick.getFull().isEmpty(), "full at " + seq);
            if (full) assertEquals("state " + seq, unzstd(tick.getFull().toByteArray()));
            assertEquals(seq == 12 ? List.of("Simulation", "Timer") : List.of(), tick.getFlagsList());
        }
    }

    @Test
    void aCutFromBeforeTheWatchIsPartialLateStartWithTheHistory() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 60, detached);
        for (int seq = 100; seq <= 110; seq++) watch.capture(seq, 0, "t", "s", null, System.nanoTime());
        diagnostics.cut(PLAYER, cut(90, 105, 95, 100), "header");
        List<DiagPart> parts = awaitParts();
        DiagPart last = parts.get(parts.size() - 1);
        assertEquals(DiagCoverage.DIAG_COVERAGE_PARTIAL, last.getCoverage());
        assertEquals("late-start", last.getReason());
        assertEquals("history", parts.get(0).getHistory());
    }

    @Test
    void aCutWaitsForItsLastSeqThenForAtMostFifteenSeconds() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 600, detached);
        for (int seq = 0; seq <= 5; seq++) watch.capture(seq, 0, "t", "s", null, System.nanoTime());
        diagnostics.cut(PLAYER, cut(0, 8, 0, 8), "header");
        awaitPasses();
        assertFalse(upload.pending());
        for (int seq = 6; seq <= 8; seq++) watch.capture(seq, 0, "t", "s", null, System.nanoTime());
        List<DiagPart> parts = awaitParts();
        assertEquals(
                DiagCoverage.DIAG_COVERAGE_FULL, parts.get(parts.size() - 1).getCoverage());

        diagnostics.cut(PLAYER, cut(5, 20, 5, 20), "header");
        awaitPasses();
        assertFalse(upload.pending());
        clock.addAndGet(Diagnostics.CUT_WAIT_NANOS);
        parts = awaitParts();
        DiagPart last = parts.get(parts.size() - 1);
        assertEquals(DiagCoverage.DIAG_COVERAGE_PARTIAL, last.getCoverage());
        assertEquals("dropped", last.getReason());
    }

    @Test
    void cutsNobodyIsWatchedForAnswerNoneWithWhy() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        diagnostics.refuse(PLAYER, "bedrock");
        diagnostics.cut(PLAYER, cut(0, 1, 0, 1), "header");
        assertEquals("bedrock", parts().get(0).getReason());
        diagnostics.cut(OTHER, cut(0, 1, 0, 1), null);
        assertEquals("left", parts().get(0).getReason());
        DiagWatch watch = watch(diagnostics, OTHER, 60, detached);
        diagnostics.left(OTHER);
        assertTrue(watch.ended());
        diagnostics.cut(OTHER, cut(0, 1, 0, 1), "header");
        DiagPart answer = parts().get(0);
        assertEquals("left", answer.getReason());
        assertTrue(answer.getLast());
        assertEquals(0, answer.getTicksCount());
    }

    @Test
    void bundlesPerHourBlocksAtExactlyItsValue() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(Limits.of(true, 0, 0, 0, 0, 0, Limits.MAX_UPLOAD_BYTES_PER_SECOND, 0, 2));
        DiagWatch watch = watch(diagnostics, PLAYER, 600, detached);
        watch.capture(0, 0, "t", "s", null, System.nanoTime());
        for (int i = 0; i < 2; i++) {
            diagnostics.cut(PLAYER, cut(0, 0, 0, 0), "header");
            assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, awaitParts().get(0).getCoverage());
        }
        diagnostics.cut(PLAYER, cut(0, 0, 0, 0), "header");
        DiagPart refused = awaitParts().get(0);
        assertEquals(DiagCoverage.DIAG_COVERAGE_NONE, refused.getCoverage());
        assertEquals("rate", refused.getReason());
        clock.addAndGet(TimeUnit.MINUTES.toNanos(30));
        diagnostics.cut(PLAYER, cut(0, 0, 0, 0), "header");
        assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, awaitParts().get(0).getCoverage());
    }

    @Test
    void aBundleThatNoLongerFitsTheUploadEndsWithADroppedPart() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 600, detached);
        watch.capture(0, 0, "t", "s", null, System.nanoTime());
        await(() -> watch.ring != null);
        byte[] filler = DiagWire.record(DiagPart.newBuilder()
                .setBundle(ByteString.copyFrom(new byte[16]))
                .setHeader("x".repeat(210_000))
                .build());
        for (int i = 0; i < 5; i++) assertTrue(upload.force(1, filler));
        diagnostics.cut(PLAYER, cut(0, 0, 0, 0), "header");
        await(() -> upload.bytes() > 5L * filler.length);
        List<DiagPart> parts = awaitParts();
        assertEquals(1, parts.size());
        assertTrue(parts.get(0).getLast());
        assertEquals("dropped", parts.get(0).getReason());
        assertEquals(DiagCoverage.DIAG_COVERAGE_NONE, parts.get(0).getCoverage());
    }

    @Test
    void threeFailuresTripDiagnosticsForTheProcess() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 600, detached);
        for (int i = 0; i < Diagnostics.TRIP_FAILURES; i++) watch.failed(new IllegalStateException("injected " + i));
        assertTrue(Diagnostics.tripped());
        assertTrue(watch.ended());
        assertEquals("failed", watch.endReason);
        assertNull(watch(diagnostics, PLAYER, 60, detached));
        assertTrue(diagnostics.status().contains("tripped"));
    }

    @Test
    void aWatchWhoseCaptureP99PassesAMillisecondEnds() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 600, detached);
        for (int seq = 0; seq < Diagnostics.CAPTURE_WINDOW; seq++) {
            long slow = TimeUnit.MILLISECONDS.toNanos(2);
            watch.capture(seq, 0, "t", null, null, System.nanoTime() - slow);
        }
        await(() -> watch.ring != null && watch.ring.size() == Diagnostics.CAPTURE_WINDOW);
        assertFalse(watch.ended());
        for (int seq = 100; seq < 100 + Diagnostics.CAPTURE_WINDOW; seq++) {
            long slow = seq % 25 == 0 ? TimeUnit.MILLISECONDS.toNanos(2) : 0;
            watch.capture(seq, 0, "t", null, null, System.nanoTime() - slow);
        }
        await(watch::ended);
        assertEquals("failed", watch.endReason);
    }

    @Test
    void aBundleDroppingTicksIsPartialAndBounded() {
        DiagRing ring = new DiagRing(100, 1 << 30);
        for (int seq = 0; seq <= 20; seq++) ring.add(seq, 0, "t".repeat(1000), null);
        String header = "é".repeat(1000);
        List<byte[]> records = DiagCuts.parts(
                PLAYER,
                cut(0, 20, 10, 10),
                header,
                "h".repeat(100_000),
                ring.range(0, 20),
                DiagCuts.Coverage.FULL,
                Limits.MIN_PART_BYTES,
                8 * 1024);
        DiagPart first = part(records.get(0));
        assertTrue(first.getHeader().getBytes(StandardCharsets.UTF_8).length <= DiagCuts.MAX_HEADER_BYTES);
        assertTrue(first.getHeader().chars().allMatch(c -> c == 0xe9));
        assertTrue(first.getHistory().length() <= Limits.MIN_PART_BYTES / 2);
        for (byte[] record : records) assertTrue(part(record).getSerializedSize() <= Limits.MIN_PART_BYTES);
        DiagPart last = part(records.get(records.size() - 1));
        assertEquals(DiagCoverage.DIAG_COVERAGE_PARTIAL, last.getCoverage());
        assertEquals("dropped", last.getReason());
        int ticks = records.stream()
                .mapToInt(record -> part(record).getTicksCount())
                .sum();
        assertTrue(ticks > 0 && ticks < 21, "ticks " + ticks);
    }

    @Test
    void dropsAreRememberedByRange() {
        AtomicInteger detached = new AtomicInteger();
        Diagnostics diagnostics = diagnostics(ON);
        DiagWatch watch = watch(diagnostics, PLAYER, 600, detached);
        assertFalse(watch.droppedWithin(0, 1000));
        Diagnostics other = new Diagnostics(new DiagUpload(), "unstarted", clock::get);
        opened.add(other);
        other.connected(1, ON);
        DiagWatch starved = new DiagWatch(other, OTHER, 1, Long.MAX_VALUE, () -> 0, "", ON, ended -> {});
        starved.capture(100, 0, "t", null, null, System.nanoTime());
        starved.capture(300, 0, "t", null, null, System.nanoTime());
        assertTrue(starved.droppedWithin(90, 110));
        assertTrue(starved.droppedWithin(250, 350));
        assertFalse(starved.droppedWithin(120, 290));
        for (int seq = 1000; seq < 1000 + DiagWatch.DROPS; seq++) starved.capture(seq, 0, "t", null, null, 0);
        assertTrue(starved.droppedWithin(90, 110), "older than the drops remembered");
        assertFalse(starved.droppedWithin(2000, 2100));
    }

    @Test
    void theRingDropsFullTextsBeforeTicksAndKeepsItsTickCount() {
        DiagRing ring = new DiagRing(5, 3 * (DiagRing.TICK_OVERHEAD + 1000) + 500);
        for (int seq = 0; seq < 8; seq++) ring.add(seq, 0, "", new byte[1000]);
        assertEquals(5, ring.size());
        assertEquals(2, ring.evictedThrough());
        List<DiagRing.Tick> ticks = ring.range(0, 100);
        assertEquals(3, ticks.get(0).seq);
        assertNull(ticks.get(0).full);
        assertNull(ticks.get(1).full);
        assertNotNull(ticks.get(2).full);
        assertNotNull(ticks.get(4).full);
        assertTrue(ring.bytes() <= 3 * (DiagRing.TICK_OVERHEAD + 1000) + 500);

        ring.flags(9, List.of("Timer"));
        assertEquals(9, ring.newest());
        ring.flags(5, List.of("Reach"));
        assertEquals(List.of("Reach"), ring.range(5, 5).get(0).flags);
    }

    @Test
    void aBundleOverItsLimitDropsFullTextsFurthestFromTheMiddleFirst() {
        DiagRing ring = new DiagRing(100, 1 << 30);
        byte[] incompressible = new byte[3000];
        new Random(7).nextBytes(incompressible);
        for (int seq = 0; seq <= 20; seq++) ring.add(seq, 0, "t", incompressible);
        Cut cut = cut(0, 20, 0, 20);
        List<byte[]> records =
                DiagCuts.parts(PLAYER, cut, "h", null, ring.range(0, 20), DiagCuts.Coverage.FULL, 32 * 1024, 5 * 4096);
        List<Long> full = new ArrayList<>();
        int bytes = 0;
        for (byte[] record : records) {
            bytes += record.length;
            for (DiagTick tick : part(record).getTicksList())
                if (!tick.getFull().isEmpty()) full.add(tick.getSeq());
        }
        assertTrue(bytes <= 5 * 4096, "bundle of " + bytes);
        assertEquals(
                21,
                records.stream()
                        .mapToInt(record -> part(record).getTicksCount())
                        .sum());
        assertFalse(full.isEmpty());
        for (long seq : full) assertTrue(Math.abs(seq - 10) <= 3, "kept full at " + seq);
    }

    @Test
    void theUploadPacesPartsAtItsRate() {
        DiagUpload paced = new DiagUpload();
        paced.rate(32 * 1024);
        for (int i = 0; i < 6; i++) assertTrue(paced.offer(1, new byte[16 * 1024]));
        long now = 0;
        int taken = 0;
        while (paced.poll(1, now) != null) taken++;
        assertEquals(2, taken, "a full bucket holds one second and a part");
        now += TimeUnit.MILLISECONDS.toNanos(400);
        assertNull(paced.poll(1, now));
        now += TimeUnit.MILLISECONDS.toNanos(150);
        assertNotNull(paced.poll(1, now));
        assertTrue(paced.offer(2, new byte[10]));
        now += TimeUnit.SECONDS.toNanos(10);
        assertEquals(10, paced.poll(2, now).length, "parts of another socket are dropped");
        assertFalse(paced.pending());
        assertFalse(paced.offer(2, new byte[(int) DiagUpload.MAX_BYTES + 1]));
    }

    private static Cut cut(long from, long to, long fullFrom, long fullTo) {
        return new Cut(BUNDLE, from, to, fullFrom, fullTo);
    }

    private static DiagPart part(byte[] record) {
        try {
            return Intake.parseFrom(record).getMessages(0).getDiag();
        } catch (IOException malformed) {
            throw new AssertionError(malformed);
        }
    }

    /** Every part queued now. */
    private List<DiagPart> parts() {
        List<DiagPart> parts = new ArrayList<>();
        byte[] record;
        long nanos = clock.get();
        while ((record = upload.poll(1, nanos += TimeUnit.HOURS.toNanos(1))) != null) {
            DiagPart part = part(record);
            if (Arrays.equals(BUNDLE, part.getBundle().toByteArray())) parts.add(part);
        }
        return parts;
    }

    /** The parts of the next bundle, through its last. */
    private List<DiagPart> awaitParts() {
        List<DiagPart> parts = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long nanos = clock.get();
        while (System.nanoTime() < deadline) {
            nanos += TimeUnit.SECONDS.toNanos(10);
            byte[] record = upload.poll(1, nanos);
            if (record == null) {
                sleep(5);
                continue;
            }
            DiagPart part = part(record);
            if (!Arrays.equals(BUNDLE, part.getBundle().toByteArray())) continue;
            parts.add(part);
            if (part.getLast()) return parts;
        }
        throw new AssertionError("no last part; got " + parts.size());
    }

    private static String unzstd(byte[] frame) throws IOException {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
