package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagCut;
import ac.cult.cultac.cloud.intake.v3.DiagLimits;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.intake.v3.DiagWatch;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Reply;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.github.luben.zstd.ZstdInputStream;
import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The diagnostics goldens of the intake contract. {@link #readsTheGoldenReplies} decodes the golden {@code DiagLimits},
 * {@code DiagWatch} and {@code DiagCut} as the intake service sends them. {@link #buildsTheGoldenPart} builds the golden bundle
 * through the public diagnostics path and checks its {@code DiagPart} record as it is queued for the socket.
 *
 * <p>The golden bundle: player {@link #PLAYER}, bundle {@link #BUNDLE}, header {@code "agent golden, client 1.21.8,
 * brand vanilla, ping 42 ms"}; ticks 100 to 104, each with trace {@code "trace <seq>"} and offset {@code seq / 1000};
 * tick 102 flagged by {@code Simulation} and {@code Timer}; the cut from 100 to 104 with full texts for 103 only, so
 * full texts go out for 102 (flagged) and 103, each {@code "state <seq>"} as one Zstandard frame; one part, last,
 * coverage FULL, no reason, no history.
 */
class IntakeDiagInteropTest {
    static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    static final byte[] BUNDLE = {
        0x01,
        (byte) 0x92,
        0x33,
        0x44,
        0x55,
        0x66,
        0x77,
        (byte) 0x88,
        (byte) 0x99,
        0x0a,
        0x1b,
        0x2c,
        0x3d,
        0x4e,
        0x5f,
        0x60
    };
    static final String HEADER = "agent golden, client 1.21.8, brand vanilla, ping 42 ms";

    /** The golden replies, as the service's encoder must write them. */
    static Replies goldenReplies() {
        return Replies.newBuilder()
                .addReplies(Reply.newBuilder()
                        .setDiagLimits(DiagLimits.newBuilder()
                                .setEnabled(true)
                                .setRingTicks(400)
                                .setPlayerRingBytes(1_048_576)
                                .setServerRingBytes(4_194_304)
                                .setMaxPlayers(2)
                                .setPartBytes(32_768)
                                .setUploadBytesPerSecond(32_768)
                                .setBundleBytes(524_288)
                                .setBundlesPerHour(30)))
                .addReplies(Reply.newBuilder()
                        .setDiagWatch(DiagWatch.newBuilder()
                                .setPlayer(ByteString.copyFrom(IntakeWire.uuid(PLAYER)))
                                .setLeaseSeconds(330)))
                .addReplies(Reply.newBuilder()
                        .setDiagCut(DiagCut.newBuilder()
                                .setPlayer(ByteString.copyFrom(IntakeWire.uuid(PLAYER)))
                                .setBundle(ByteString.copyFrom(BUNDLE))
                                .setFromSeq(1000)
                                .setToSeq(1240)
                                .setFullFromSeq(1180)
                                .setFullToSeq(1210)))
                .addReplies(Reply.newBuilder()
                        .setDiagLimits(DiagLimits.newBuilder()
                                .setEnabled(true)
                                .setRingTicks(1_000_000)
                                .setMaxPlayers(100)
                                .setBundlesPerHour(100_000)))
                .build();
    }

    @Test
    void readsTheGoldenReplies() {
        List<IntakeWire.Reply> replies = IntakeWire.replies(goldenReplies().toByteArray());
        assertEquals(4, replies.size());
        assertEquals(
                new IntakeWire.DiagLimitsReply(
                        new Limits(true, 400, 1_048_576, 4_194_304, 2, 32_768, 32_768, 524_288, 30)),
                replies.get(0));
        assertNull(replies.get(0).player());
        assertEquals(new IntakeWire.DiagWatchReply(PLAYER, 330), replies.get(1));
        IntakeWire.DiagCutReply cut = (IntakeWire.DiagCutReply) replies.get(2);
        assertEquals(PLAYER, cut.player());
        assertArrayEquals(BUNDLE, cut.cut().bundle());
        assertEquals(1000, cut.cut().fromSeq());
        assertEquals(1240, cut.cut().toSeq());
        assertEquals(1180, cut.cut().fullFromSeq());
        assertEquals(1210, cut.cut().fullToSeq());
        Limits clamped = ((IntakeWire.DiagLimitsReply) replies.get(3)).limits();
        assertEquals(Limits.MAX_RING_TICKS, clamped.ringTicks());
        assertEquals(Limits.MAX_PLAYERS, clamped.maxPlayers());
        assertEquals(Limits.MAX_BUNDLES_PER_HOUR, clamped.bundlesPerHour());
        assertEquals(Limits.OFF.playerRingBytes(), clamped.playerRingBytes());
    }

    @Test
    void aMalformedDiagReplyIsDroppedAlone() {
        Replies replies = Replies.newBuilder()
                .addReplies(Reply.newBuilder()
                        .setDiagWatch(DiagWatch.newBuilder().setPlayer(ByteString.copyFrom(new byte[3]))))
                .addReplies(Reply.newBuilder().setDiagCut(DiagCut.newBuilder().setPlayer(ByteString.EMPTY)))
                .addReplies(goldenReplies().getReplies(1))
                .build();
        List<IntakeWire.Reply> read = IntakeWire.replies(replies.toByteArray());
        assertEquals(List.of(new IntakeWire.DiagWatchReply(PLAYER, 330)), read);
    }

    @Test
    void buildsTheGoldenPart() throws Exception {
        DiagUpload upload = new DiagUpload();
        Diagnostics diagnostics = new Diagnostics(upload, "interop");
        try {
            diagnostics.connected(1, Limits.of(true, 0, 0, 0, 0, 0, Limits.MAX_UPLOAD_BYTES_PER_SECOND, 0, 0));
            var watch = diagnostics.watch(PLAYER, 1, 60, () -> 0, () -> "", ended -> {});
            assertNotNull(watch);
            for (int seq = 100; seq <= 104; seq++) {
                watch.capture(seq, seq / 1000.0, "trace " + seq, "state " + seq, null, System.nanoTime());
                if (seq == 102) watch.tickEnd(seq, List.of("Simulation", "Timer"));
            }
            long handled = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (watch.ring == null || watch.ring.newest() < 104) {
                assertTrue(System.nanoTime() < handled, "the captures reach the ring");
                Thread.sleep(5);
            }
            diagnostics.cut(PLAYER, new Cut(BUNDLE, 100, 104, 103, 103), HEADER);
            List<byte[]> records = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long nanos = 0;
            while (records.isEmpty() && System.nanoTime() < deadline) {
                byte[] record = upload.poll(1, nanos += TimeUnit.SECONDS.toNanos(10));
                if (record != null) records.add(record);
                else Thread.sleep(5);
            }
            assertEquals(1, records.size());
            IntakeMessage message = Intake.parseFrom(records.get(0)).getMessages(0);
            assertTrue(message.hasDiag());
            DiagPart part = message.getDiag();
            assertArrayEquals(IntakeWire.uuid(PLAYER), part.getPlayer().toByteArray());
            assertArrayEquals(BUNDLE, part.getBundle().toByteArray());
            assertEquals(0, part.getPart());
            assertTrue(part.getLast());
            assertEquals(HEADER, part.getHeader());
            assertEquals("", part.getHistory());
            assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, part.getCoverage());
            assertEquals("", part.getReason());
            assertEquals(5, part.getTicksCount());
            for (int i = 0; i < 5; i++) {
                DiagTick tick = part.getTicks(i);
                long seq = 100 + i;
                assertEquals(seq, tick.getSeq());
                assertTrue(tick.hasOffset());
                assertEquals(seq / 1000.0, tick.getOffset());
                assertEquals("trace " + seq, tick.getTrace());
                assertEquals(seq == 102 ? List.of("Simulation", "Timer") : List.of(), tick.getFlagsList());
                boolean full = seq == 102 || seq == 103;
                assertEquals(
                        full ? "state " + seq : "", full ? unzstd(tick.getFull().toByteArray()) : "");
                assertEquals(full, !tick.getFull().isEmpty());
            }
        } finally {
            diagnostics.close();
        }
    }

    private static String unzstd(byte[] frame) throws IOException {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
