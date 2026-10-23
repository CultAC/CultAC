package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a capture takes from the server's shared budget for retained frames, and that it all comes back. */
class CaptureStreamBudgetTest {
    private static final UUID PLAYER = new UUID(5, 6);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
    private static final int TICK_END = 1;
    private static final int MOVE = 2;
    private static final int OTHER = 3;
    private static final int LEVEL_CHUNK = 44;
    private static final int LIMIT = 4 * 1024 * 1024;

    private static TestSink sink(TestBudget budget, CloudConnection.Terms terms) {
        TestSink sink = new TestSink(budget);
        sink.terms = terms;
        return sink;
    }

    @Test
    void aVerdictReservesTheLargestVerdictAndNoMore() {
        int outcomes = CaptureStream.MAX_OUTCOME_BYTES;
        assertTrue(CaptureStream.VERDICT_BYTES > 4000 + outcomes, CaptureStream.VERDICT_BYTES + " B");
        assertTrue(CaptureStream.VERDICT_BYTES < 5000 + outcomes, CaptureStream.VERDICT_BYTES + " B");
    }

    @Test
    void aStreamHoldsNoChunkPacketWhilePreparing() {
        TestBudget budget = new TestBudget(LIMIT);
        TestSink sink = sink(budget, CloudConnection.Terms.sections(0, 0));
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.chunks(new ChunkIds(LEVEL_CHUNK, 8, 82, 37));
        stream.prepare(sink);
        assertFalse(stream.wants(IntakeWire.CLIENTBOUND, LEVEL_CHUNK));
        assertTrue(stream.wants(IntakeWire.CLIENTBOUND, OTHER));
        stream.tap(IntakeWire.CLIENTBOUND, LEVEL_CHUNK, new byte[100_000], 1, -64, 384, SESSION);
        assertEquals(0, sink.used);
        stream.reset();
        assertFalse(stream.wants(IntakeWire.CLIENTBOUND, OTHER));
        stream.prepare(sink);
        assertFalse(stream.wants(IntakeWire.CLIENTBOUND, LEVEL_CHUNK));
        stream.reset();
    }

    @Test
    void everyByteTakenComesBackOnceTheStreamResets() {
        Random random = new Random(42);
        for (int run = 0; run < 400; run++) {
            TestBudget budget = new TestBudget(LIMIT);
            TestSink sink = sink(budget, CloudConnection.Terms.sections(0, 0));
            CaptureStream stream = new CaptureStream(PLAYER);
            stream.start(0);
            long cuts = 0;
            long at = 0;
            for (int step = 0; step < 60; step++) {
                at += 1_000_000;
                switch (random.nextInt(12)) {
                    case 0 -> {
                        if (!stream.capturing()) stream.prepare(sink);
                    }
                    case 1 ->
                        stream.join(
                                sink, IntakeWire.join(PLAYER, 777, "vanilla", -64, 384, null, null, 0), at, SESSION);
                    case 2 -> stream.capture(new int[] {TICK_END, MOVE}, new int[] {OTHER}, new int[0]);
                    case 3, 4 ->
                        stream.tap(IntakeWire.SERVERBOUND, MOVE, new byte[1 + random.nextInt(2000)], at, 0, 0, null);
                    case 5 ->
                        stream.tap(
                                IntakeWire.CLIENTBOUND,
                                OTHER,
                                new byte[random.nextInt(20) == 0 ? 3 * 1024 * 1024 : 1 + random.nextInt(5000)],
                                at,
                                -64,
                                384,
                                SESSION);
                    case 6, 7 -> {
                        stream.tap(IntakeWire.SERVERBOUND, TICK_END, new byte[] {TICK_END}, at, 0, 0, null);
                        stream.cut(at);
                        cuts++;
                    }
                    case 8 -> stream.verdict(cuts - random.nextInt(3), cuts, 0, List.of(), null, List.of(), null);
                    case 9 -> sink.refuse = sink.refuse == 0 ? Integer.MAX_VALUE : 0;
                    case 10 -> {
                        if (random.nextInt(4) == 0) stream.leave(at);
                    }
                    default -> {
                        if (random.nextInt(4) == 0) stream.reset();
                    }
                }
                assertTrue(sink.used >= 0 && sink.used <= LIMIT);
            }
            stream.reset();
            assertEquals(0, sink.used, "run " + run);
            assertEquals(LIMIT, budget.remaining(), "run " + run);
        }
    }

    @Test
    void framesAwaitingTheirVerdictShareTheServersBudget() {
        long limit = 1024 * 1024;
        TestBudget budget = new TestBudget(limit);
        List<TestSink> sinks = new ArrayList<>();
        List<CaptureStream> streams = new ArrayList<>();
        for (int player = 0; player < 32; player++) {
            UUID id = new UUID(9, player);
            TestSink sink = sink(budget, CloudConnection.Terms.sections(0, 0));
            CaptureStream stream = new CaptureStream(id);
            stream.start(0);
            stream.join(sink, IntakeWire.join(id, 777, "vanilla", -64, 384, null, null, 0), 0, SESSION);
            stream.capture(new int[] {MOVE}, new int[0], new int[0]);
            sinks.add(sink);
            streams.add(stream);
        }
        long at = 0;
        for (int tick = 1; tick <= 20; tick++) {
            for (CaptureStream stream : streams) {
                for (int i = 0; i < 10; i++) stream.tap(IntakeWire.SERVERBOUND, MOVE, new byte[1000], ++at, 0, 0, null);
                stream.cut(++at);
            }
        }
        long used = sinks.stream().mapToLong(sink -> sink.used).sum();
        assertTrue(used > limit / 2 && used <= limit, used + " B held");

        for (CaptureStream stream : streams) {
            for (int tick = 1; tick <= 20; tick++) stream.verdict(tick, tick, 0, List.of(), null, List.of(), null);
        }
        long sent = sinks.stream()
                .flatMap(sink -> sink.entries(IntakeMessage.KindCase.FRAME).stream())
                .mapToLong(entry -> entry.length)
                .sum();
        assertTrue(sent < 2 * limit, sent + " B sent");
        for (TestSink sink : sinks) assertEquals(0, sink.used);
        assertEquals(limit, budget.remaining());

        streams.get(0).tap(IntakeWire.SERVERBOUND, MOVE, new byte[500], ++at, 0, 0, null);
        assertEquals(500 + CaptureStream.RECORD_OVERHEAD_BYTES, sinks.get(0).used);
        for (CaptureStream stream : streams) stream.reset();
        assertEquals(limit, budget.remaining());
    }

    @Test
    void framesHeldAcrossTheAnswerGiveBackExactlyWhatTheyTook() {
        TestBudget budget = new TestBudget(LIMIT);
        TestSink sink = sink(budget, CloudConnection.Terms.sections(0, 0));
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.join(sink, IntakeWire.join(PLAYER, 777, "vanilla", -64, 384, null, null, 0), 0, SESSION);
        stream.tap(IntakeWire.SERVERBOUND, MOVE, new byte[500], 1, 0, 0, null);
        stream.cut(2);
        stream.tap(IntakeWire.SERVERBOUND, MOVE, new byte[500], 3, 0, 0, null);
        long open = sink.used;
        assertTrue(open > 500 + CaptureStream.VERDICT_BYTES);
        stream.capture(new int[] {MOVE}, new int[0], new int[0]);
        stream.tap(IntakeWire.SERVERBOUND, MOVE, new byte[500], 4, 0, 0, null);
        assertEquals(open + 500 + CaptureStream.RECORD_OVERHEAD_BYTES, sink.used);
        sink.refuse = Integer.MAX_VALUE;
        stream.cut(5);
        stream.verdict(1, 1, 0, List.of(), null, List.of(), null);
        stream.verdict(2, 2, 0, List.of(), null, List.of(), null);
        assertEquals(0, sink.used);
        stream.reset();
        assertEquals(LIMIT, budget.remaining());
    }
}
