package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.PingKind;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Intake protocol 5's compact records, as cultac writes them. */
class CaptureStreamCompactTest {
    private static final UUID PLAYER = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
    private static final int TICK_END = 1;
    private static final int PONG = 2;
    private static final int PING = 3;
    private static final CloudConnection.Terms PROTOCOL_5 = CloudConnection.Terms.sections(0, 0);

    /** Each frame's records, in the order sent. */
    private static List<List<Record>> frames(TestSink sink) {
        return sink.frames().stream().map(Frame::getRecordsList).toList();
    }

    private static CaptureStream joined(TestSink sink) {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.pingPacket(PING);
        stream.pongPacket(PONG);
        stream.join(sink, IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        stream.capture(new int[] {TICK_END, PONG}, new int[] {PING}, new int[0]);
        return stream;
    }

    private static byte[] probe(int packet, int id) {
        return ByteBuffer.allocate(5).put((byte) packet).putInt(id).array();
    }

    private static void ping(CaptureStream stream, int id, long nanos) {
        stream.tap(IntakeWire.CLIENTBOUND, PING, probe(PING, id), nanos, -64, 384, SESSION, IntakeWire.PING_FOREIGN);
    }

    private static void pong(CaptureStream stream, int id, long nanos) {
        stream.tap(IntakeWire.SERVERBOUND, PONG, probe(PONG, id), nanos, 0, 0, null);
    }

    /** Ends the open frame at {@code nanos} and hands cultac's verdict on it over, so it goes out. */
    private static void tick(CaptureStream stream, long index, long nanos) {
        stream.tap(IntakeWire.SERVERBOUND, TICK_END, new byte[] {TICK_END}, nanos, 0, 0, null);
        stream.cut(nanos);
        stream.verdict(index, index, 0, List.of(), null, List.of(), null);
    }

    @Test
    void pongsBecomeMarkersOnlyForTheOldestPendingPing() {
        TestSink sink = new TestSink(PROTOCOL_5);
        CaptureStream stream = joined(sink);
        byte[] longPing =
                ByteBuffer.allocate(6).put((byte) PING).putInt(9).put((byte) 0).array();

        ping(stream, 11, 1_000_000);
        ping(stream, 12, 1_100_000);
        ping(stream, 13, 1_200_000);
        stream.tap(IntakeWire.CLIENTBOUND, PING, longPing, 1_300_000, -64, 384, SESSION, IntakeWire.PING_FOREIGN);
        tick(stream, 1, 2_000_000);
        pong(stream, 11, 3_000_000);
        pong(stream, 13, 3_100_000);
        pong(stream, 0x777, 3_200_000);
        pong(stream, 12, 3_300_000);
        tick(stream, 2, 4_000_000);

        List<Record> first = frames(sink).get(0);
        assertEquals(Record.BodyCase.PING, first.get(0).getBodyCase());
        assertEquals(PingKind.PING_KIND_FOREIGN, first.get(0).getPing());
        assertEquals(Record.BodyCase.PING, first.get(2).getBodyCase());
        assertEquals(
                Record.BodyCase.CLIENTBOUND, first.get(3).getBodyCase(), "a ping with more than its id goes whole");
        assertArrayEquals(longPing, first.get(3).getClientbound().toByteArray());
        assertEquals(PingKind.PING_KIND_FOREIGN, first.get(3).getPingKind());
        List<Record> second = frames(sink).get(1);
        assertEquals(Record.BodyCase.PONG, second.get(0).getBodyCase());
        assertEquals(Record.BodyCase.SERVERBOUND, second.get(1).getBodyCase(), "an out-of-order pong goes whole");
        assertEquals(1, second.get(1).getSkippedPings());
        assertArrayEquals(probe(PONG, 13), second.get(1).getServerbound().toByteArray());
        assertEquals(Record.BodyCase.SERVERBOUND, second.get(2).getBodyCase(), "an unsolicited pong goes whole");
        assertEquals(0, second.get(2).getSkippedPings());
        assertEquals(Record.BodyCase.SERVERBOUND, second.get(3).getBodyCase(), "a pong to a skipped ping goes whole");
        assertEquals(0, second.get(3).getSkippedPings());
    }

    @Test
    void aRefusedFrameLeavesPingsAndClock() {
        TestSink sink = new TestSink(PROTOCOL_5);
        CaptureStream stream = joined(sink);
        ping(stream, 21, 1_000_000);
        tick(stream, 1, 2_000_000);
        ping(stream, 22, 3_000_000);
        sink.refuse = 1;
        tick(stream, 2, 4_000_000);
        pong(stream, 22, 5_000_000);
        pong(stream, 21, 5_100_000);
        tick(stream, 3, 6_000_000);

        List<List<Record>> frames = frames(sink);
        assertEquals(2, frames.size());
        List<Record> last = frames.get(1);
        assertEquals(Record.BodyCase.GAP, last.get(0).getBodyCase());
        assertEquals(Record.BodyCase.SERVERBOUND, last.get(1).getBodyCase(), "the refused frame's ping was never sent");
        assertEquals(Record.BodyCase.PONG, last.get(2).getBodyCase());
        long micros = 0;
        for (List<Record> frame : frames) for (Record record : frame) micros += record.getAdvanceNanos();
        assertEquals(6_000, micros, "the refused frame's time is the next one's");
    }

    @Test
    void anUnansweredForeignPingIsSkippedAcrossTheIdWrap() {
        TestSink sink = new TestSink(PROTOCOL_5);
        CaptureStream stream = joined(sink);
        ping(stream, Short.MIN_VALUE + 1, 1_000_000);
        ping(stream, Short.MIN_VALUE, 1_100_000);
        ping(stream, -1, 1_200_000);
        ping(stream, -2, 1_300_000);
        tick(stream, 1, 2_000_000);
        pong(stream, Short.MIN_VALUE, 3_000_000);
        pong(stream, -1, 3_100_000);
        pong(stream, -2, 3_200_000);
        tick(stream, 2, 4_000_000);

        for (Record ping : frames(sink).get(0).subList(0, 4)) assertEquals(Record.BodyCase.PING, ping.getBodyCase());
        List<Record> pongs = frames(sink).get(1);
        assertEquals(Record.BodyCase.SERVERBOUND, pongs.get(0).getBodyCase());
        assertEquals(1, pongs.get(0).getSkippedPings());
        assertArrayEquals(
                probe(PONG, Short.MIN_VALUE), pongs.get(0).getServerbound().toByteArray());
        assertEquals(Record.BodyCase.PONG, pongs.get(1).getBodyCase());
        assertEquals(Record.BodyCase.PONG, pongs.get(2).getBodyCase());
    }

    @Test
    void anUnknownPongGoesWhole() {
        TestSink sink = new TestSink(PROTOCOL_5);
        CaptureStream stream = joined(sink);
        pong(stream, -5, 500_000);
        for (int i = 1; i <= IntakeWire.MAX_PENDING_PINGS + 1; i++) ping(stream, -i, 1_000_000 + i);
        tick(stream, 1, 2_000_000);
        pong(stream, -1, 3_000_000);
        pong(stream, -2, 3_100_000);
        tick(stream, 2, 4_000_000);

        Record beforeCapture = frames(sink).get(0).get(0);
        assertEquals(Record.BodyCase.SERVERBOUND, beforeCapture.getBodyCase());
        assertEquals(0, beforeCapture.getSkippedPings());
        List<Record> pongs = frames(sink).get(1);
        assertEquals(Record.BodyCase.SERVERBOUND, pongs.get(0).getBodyCase(), "the evicted ping's pong goes whole");
        assertEquals(0, pongs.get(0).getSkippedPings());
        assertEquals(Record.BodyCase.PONG, pongs.get(1).getBodyCase());
    }

    @Test
    void aPongWhoseIdTwoPendingPingsShareAnswersTheOlder() {
        TestSink sink = new TestSink(PROTOCOL_5);
        CaptureStream stream = joined(sink);
        ping(stream, 5, 1_000_000);
        ping(stream, 7, 1_100_000);
        ping(stream, 5, 1_200_000);
        tick(stream, 1, 2_000_000);
        pong(stream, 5, 3_000_000);
        pong(stream, 7, 3_100_000);
        pong(stream, 5, 3_200_000);
        tick(stream, 2, 4_000_000);

        List<Record> pongs = frames(sink).get(1);
        for (int i = 0; i < 3; i++)
            assertEquals(Record.BodyCase.PONG, pongs.get(i).getBodyCase());
    }

    /** Each clientbound record's and ping marker's server tick, from the frame's and the deltas; its ping kind beside. */
    private static List<long[]> serverTicks(Frame frame) {
        List<long[]> ticks = new ArrayList<>();
        long tick = frame.getServerTick();
        for (Record record : frame.getRecordsList()) {
            if (record.getBodyCase() == Record.BodyCase.CLIENTBOUND) {
                tick += record.getTickDelta();
                ticks.add(new long[] {tick, record.getPingKindValue()});
            } else if (record.getBodyCase() == Record.BodyCase.PING) {
                tick += record.getTickDelta();
                ticks.add(new long[] {tick, record.getPingValue()});
            }
        }
        return ticks;
    }

    @Test
    void endOfTickPingsStampRecordsWithTheirServerTick() {
        int other = 4;
        TestSink sink = new TestSink(PROTOCOL_5);
        CaptureStream stream = joined(sink);
        stream.capture(new int[] {TICK_END, PONG}, new int[] {PING, other}, new int[0]);
        stream.tap(IntakeWire.CLIENTBOUND, other, new byte[] {(byte) other, 1}, 1_100_000, -64, 384, SESSION);
        ping(stream, -1, 1_200_000);
        stream.endOfTick(100);
        stream.tap(IntakeWire.CLIENTBOUND, other, new byte[] {(byte) other, 1}, 1_400_000, -64, 384, SESSION);
        ping(stream, -2, 1_500_000);
        pong(stream, -1, 1_600_000);
        tick(stream, 1, 2_000_000);
        stream.tap(IntakeWire.CLIENTBOUND, other, new byte[] {(byte) other, 1}, 2_100_000, -64, 384, SESSION);
        stream.endOfTick(101);
        ping(stream, -3, 2_200_000);
        stream.endOfTick(102);
        tick(stream, 2, 3_000_000);

        List<Frame> frames = sink.frames();
        int foreign = IntakeWire.PING_FOREIGN;
        assertTrue(frames.get(0).hasServerTick(), "the session's first frame takes the tick that ends it");
        assertArrayEquals(
                new long[][] {{100, 0}, {100, foreign}, {101, 0}, {101, foreign}},
                serverTicks(frames.get(0)).toArray(long[][]::new));
        assertEquals(Record.BodyCase.PONG, frames.get(0).getRecords(4).getBodyCase());
        assertArrayEquals(
                new long[][] {{101, 0}, {102, foreign}},
                serverTicks(frames.get(1)).toArray(long[][]::new));
    }

    @Test
    void microsecondAdvancesNeverDriftFromTheNanosecondClock() {
        TestSink sink = new TestSink(PROTOCOL_5);
        CaptureStream stream = joined(sink);
        long nanos = 0;
        for (int index = 1; index <= 2_000; index++) {
            nanos += 1_499;
            stream.tap(IntakeWire.SERVERBOUND, PONG, new byte[] {PONG, 0}, nanos, 0, 0, null);
            nanos += 1_499;
            tick(stream, index, nanos);
        }

        long micros = 0;
        for (List<Record> frame : frames(sink)) {
            for (Record record : frame) micros += record.getAdvanceNanos();
        }
        assertEquals(CaptureStream.micros(nanos), micros);
    }

    @Test
    void protocol3KeepsWholeProbesAndNanoseconds() {
        TestSink sink = new TestSink(CloudConnection.Terms.PROTOCOL_3);
        CaptureStream stream = joined(sink);
        ping(stream, 31, 1_000_123);
        tick(stream, 1, 2_000_000);
        pong(stream, 31, 3_000_000);
        tick(stream, 2, 4_000_000);

        List<Record> first = frames(sink).get(0);
        assertEquals(Record.BodyCase.CLIENTBOUND, first.get(0).getBodyCase());
        assertEquals(1_000_123, first.get(0).getAdvanceNanos());
        assertEquals(Record.BodyCase.SERVERBOUND, frames(sink).get(1).get(0).getBodyCase());
    }
}
