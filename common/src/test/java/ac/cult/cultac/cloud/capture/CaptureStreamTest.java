package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.Flag;
import ac.cult.cultac.cloud.intake.v3.GameMode;
import ac.cult.cultac.cloud.intake.v3.Ground;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Meta;
import ac.cult.cultac.cloud.intake.v3.Outcome;
import ac.cult.cultac.cloud.intake.v3.Outcomes;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.Verdict;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaptureStreamTest {
    private static final UUID PLAYER = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", false);
    private static final int SERVERBOUND_ID = 1;
    private static final int CLIENTBOUND_ID = 2;
    private static final int DIMENSION_ID = 3;

    @Test
    void borrowedWireCopiesMatchOwnedArraysAcrossDelayedVerdictsPolicyDropsAndReuse() {
        for (boolean delayedPolicy : new boolean[] {false, true}) {
            for (boolean direct : new boolean[] {false, true}) {
                TestSink ownedSink = new TestSink();
                TestSink copiedSink = new TestSink();
                CaptureStream owned = joined(ownedSink);
                CaptureStream copied = joined(copiedSink);
                if (!delayedPolicy) {
                    answer(owned);
                    answer(copied);
                }
                ByteBuf wire = direct ? PooledByteBufAllocator.DEFAULT.directBuffer(300) : Unpooled.buffer(300);
                try {
                    for (int tick = 1; tick <= 40; tick++) {
                        if (tick % 13 == 0) {
                            ownedSink.refuse = 1;
                            copiedSink.refuse = 1;
                        }
                        for (int packet = 0; packet < 32; packet++) {
                            if (tick % 17 == 0 && packet == 20) {
                                overfill(owned, tick * 1000L + packet);
                                overfill(copied, tick * 1000L + packet);
                            }
                            boolean serverbound = packet % 3 == 0;
                            int kind = serverbound ? IntakeWire.SERVERBOUND : IntakeWire.CLIENTBOUND;
                            int id = serverbound ? SERVERBOUND_ID : CLIENTBOUND_ID;
                            int length = packet % 4 == 0 ? 280 : 8 + packet % 8;
                            byte[] bytes = new byte[length];
                            Arrays.fill(bytes, (byte) (tick + packet));
                            bytes[0] = (byte) id;
                            long nanos = tick * 1000L + packet;
                            int minY = serverbound ? 0 : -64;
                            int height = serverbound ? 0 : 384;
                            CaptureStream.Session session = serverbound ? null : SESSION;
                            owned.tap(kind, id, bytes, nanos, minY, height, session);
                            wire.clear().writeByte(77).writeBytes(bytes).readerIndex(1);
                            copied.tap(kind, id, wire, nanos, minY, height, session, 0);
                            assertEquals(1, wire.readerIndex());
                            wire.setZero(0, wire.writerIndex());
                        }
                        owned.cut(tick * 1000L + 900);
                        copied.cut(tick * 1000L + 900);
                        if (tick > 3) {
                            verdict(owned, tick - 3, tick - 3, 0);
                            verdict(copied, tick - 3, tick - 3, 0);
                        }
                        if (delayedPolicy && tick == 10) {
                            answer(owned);
                            answer(copied);
                        }
                    }
                    for (int tick = 38; tick <= 40; tick++) {
                        verdict(owned, tick, tick, 0);
                        verdict(copied, tick, tick, 0);
                    }
                    assertTrue(ownedSink.sent.size() > 30);
                    assertEquals(ownedSink.sent.size(), copiedSink.sent.size());
                    for (int i = 0; i < ownedSink.sent.size(); i++)
                        assertArrayEquals(ownedSink.sent.get(i), copiedSink.sent.get(i));
                } finally {
                    wire.release();
                }
            }
        }
    }

    @Test
    void reusableCopyCapacityStaysBoundedAndIsGivenBackWhenTrafficQuietsStallsOrResets() {
        long process = CapturePacketCopies.processBytes();
        CaptureStream stream = answered(new TestSink());
        ByteBuf wire = PooledByteBufAllocator.DEFAULT.directBuffer(300);
        long second = 1_000_000_000L;
        try {
            long nanos = 0;
            long peak = 0;
            for (int tick = 1; tick <= 40; tick++) {
                nanos = tick * 50_000_000L;
                for (int packet = 0; packet < 160; packet++)
                    serverbound(stream, wire, 1 + (tick * 7 + packet * 13) % 256, nanos);
                stream.cut(nanos);
                if (tick > 3) verdict(stream, tick - 3, tick - 3, 0);
                peak = Math.max(peak, stream.pooledBytes());
                assertTrue(stream.pooledBytes() <= CapturePacketCopies.PLAYER_BYTES, stream.pooledBytes() + "");
            }
            for (int tick = 38; tick <= 40; tick++) verdict(stream, tick, tick, 0);
            assertTrue(peak > CapturePacketCopies.MAX_RETAINED_BYTES, peak + "");

            for (int tick = 41; tick <= 100; tick++) {
                nanos += second;
                serverbound(stream, wire, 1, nanos);
                stream.cut(nanos);
                verdict(stream, tick, tick, 0);
                assertTrue(stream.pooledBytes() <= CapturePacketCopies.PLAYER_BYTES, stream.pooledBytes() + "");
            }
            long quiet = stream.pooledBytes();
            assertTrue(quiet <= 2 * CapturePacketCopies.POOL_BYTES + 128, quiet + " after a peak of " + peak);

            for (int tick = 101; tick <= 200; tick++) {
                nanos += 50_000_000L;
                for (int packet = 0; packet < 160; packet++)
                    serverbound(stream, wire, 1 + (tick * 11 + packet * 17) % 256, nanos);
                stream.cut(nanos);
                assertTrue(stream.pooledBytes() <= CapturePacketCopies.PLAYER_BYTES, stream.pooledBytes() + "");
                assertTrue(
                        CapturePacketCopies.processBytes() - process <= CapturePacketCopies.PLAYER_BYTES,
                        CapturePacketCopies.processBytes() - process + "");
            }

            stream.reset();

            assertEquals(0, stream.pooledBytes());
            assertEquals(process, CapturePacketCopies.processBytes());
        } finally {
            wire.release();
        }
    }

    private static void serverbound(CaptureStream stream, ByteBuf wire, int length, long nanos) {
        wire.clear()
                .writeByte(77)
                .writeByte(SERVERBOUND_ID)
                .writeZero(length - 1)
                .readerIndex(1);
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, wire, nanos, 0, 0, null, 0);
    }

    @Test
    void firstJoinWithAnOpenSocketIsByteIdenticalToAnImmediateJoin() {
        TestSink immediate = new TestSink();
        CaptureStream a = new CaptureStream(PLAYER);
        a.start(5);
        a.join(immediate, join(), 100, SESSION);
        TestSink prepared = new TestSink();
        CaptureStream b = new CaptureStream(PLAYER);
        b.start(5);
        b.prepare(prepared);
        b.join(prepared, join(), 100, SESSION);
        for (CaptureStream stream : List.of(a, b)) {
            answer(stream);
            stream.observe(SESSION, -64, 384, 150);
            tick(stream, 200);
            verdict(stream, 6, 6, 0);
        }
        assertEquals(immediate.sent.size(), prepared.sent.size());
        for (int i = 0; i < immediate.sent.size(); i++) assertArrayEquals(immediate.sent.get(i), prepared.sent.get(i));
    }

    @Test
    void anOverfullPreJoinOpenFrameDropsTheInitialCapture() {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        packet(stream, 100);
        overfill(stream, 100);
        assertFalse(stream.capturing());
        assertTrue(sink.budget.take(TestBudget.MAX_HELD_BYTES));
    }

    @Test
    void preJoinBudgetExhaustionAtTheCutDropsAndReleasesTheOpenFrame() {
        TestBudget budget = new TestBudget(100);
        CaptureStream stream = preparing(new TestSink(budget));
        tick(stream, 100);
        assertFalse(stream.capturing());
        assertTrue(budget.take(100));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void leavingOrResettingBeforeJoinFreesTheBudget(boolean leave) {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        tick(stream, 100);
        verdict(stream, 1, 1, 0);
        tick(stream, 200);
        packet(stream, 100);
        if (leave) stream.leave(300);
        else stream.reset();
        assertTrue(sink.sent.isEmpty());
        assertFalse(stream.capturing());
        assertTrue(sink.budget.take(TestBudget.MAX_HELD_BYTES));
    }

    @Test
    void firstJoinKeepsLoginChunksAndSpawnsInOrderWithTheirVerdicts() {
        TestSink sink = new TestSink();
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(5);
        stream.prepare(sink);
        CaptureStream.Session login = new CaptureStream.Session(42, 1, "minecraft:overworld", true);
        stream.observe(login, -64, 384, 100_000);
        stream.tap(IntakeWire.CLIENTBOUND, DIMENSION_ID, new byte[] {DIMENSION_ID}, 100_000, -64, 384, login);
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[] {CLIENTBOUND_ID, 10}, 110_000, -64, 384, login);
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[] {CLIENTBOUND_ID, 20}, 120_000, -64, 384, login);
        stream.cut(150_000);
        verdict(stream, 6, 6, 1L << IntakeWire.FLYING);
        tick(stream, 200_000);
        assertEquals(100_000, stream.baseNanos(300_000));
        assertEquals(0, sink.sent.size());

        stream.join(sink, join(), 300_000, login);
        answer(stream);
        verdict(stream, 7, 7, 1L << IntakeWire.GLIDING);

        List<Frame> frames = frames(sink);
        assertEquals(2, frames.size());
        assertVerdict(frames.get(0), 0, 1L << IntakeWire.FLYING);
        assertVerdict(frames.get(1), 1, 1L << IntakeWire.GLIDING);
        List<Capture> records = frames.get(0).records();
        assertEquals(
                List.of(
                        IntakeWire.WORLD,
                        IntakeWire.META,
                        IntakeWire.CLIENTBOUND,
                        IntakeWire.CLIENTBOUND,
                        IntakeWire.VERDICT),
                records.stream().map(Capture::kind).toList());
        assertEquals(42, records.get(1).record().getMeta().getEntityId());
        assertArrayEquals(
                new byte[] {CLIENTBOUND_ID, 10},
                records.get(2).record().getClientbound().toByteArray());
        assertArrayEquals(
                new byte[] {CLIENTBOUND_ID, 20},
                records.get(3).record().getClientbound().toByteArray());
        assertEquals(
                List.of(0L, 0L, 10L, 10L, 30L),
                records.stream()
                        .map(record -> record.record().getAdvanceNanos())
                        .toList());
        assertTrue(sink.budget.take(TestBudget.MAX_HELD_BYTES));
    }

    @Test
    void firstJoinKeepsAnUnfinishedLoginFrameAndSettings() {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        stream.settings(Map.of("combat", "1.9"), 100);
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[] {CLIENTBOUND_ID}, 110, -64, 384, SESSION);
        stream.join(sink, join(), 200, SESSION);
        answer(stream);
        stream.cut(250);
        verdict(stream, 1, 1, 0);
        assertEquals(
                List.of(IntakeWire.META, IntakeWire.CLIENTBOUND, IntakeWire.VERDICT),
                frames(sink).get(0).records().stream().map(Capture::kind).toList());
        assertEquals(0, frames(sink).get(0).records().get(0).record().getAdvanceNanos());
        assertTrue(sink.budget.take(TestBudget.MAX_HELD_BYTES));
    }

    @Test
    void preJoinVerdictLagUsesTheSameMissingFrameOrdering() {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        tick(stream, 100);
        tick(stream, 200);
        verdict(stream, 2, 1, 1L << IntakeWire.GLIDING);
        stream.join(sink, join(), 300, SESSION);
        answer(stream);
        assertMissing(frames(sink).get(0));
        assertEquals(1, frames(sink).get(1).seq());
        assertEquals(0, frames(sink).get(1).verdict().getTickEndSeq());
    }

    @Test
    void preJoinFrameLimitFallsBackToJoinAndReleasesEverything() {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        for (int i = 1; i <= CaptureStream.MAX_PRE_JOIN_FRAMES + 1; i++) {
            tick(stream, i * 100L);
            verdict(stream, i, i, 0);
        }
        assertFalse(stream.capturing());
        assertEquals(30_000, stream.baseNanos(30_000));
        assertTrue(sink.budget.take(TestBudget.MAX_HELD_BYTES));
        sink.budget.give(TestBudget.MAX_HELD_BYTES);
        stream.join(sink, join(), 30_000, SESSION);
        answer(stream);
        tick(stream, 31_000);
        verdict(stream, CaptureStream.MAX_PRE_JOIN_FRAMES + 2, CaptureStream.MAX_PRE_JOIN_FRAMES + 2, 0);
        assertEquals(1, frames(sink).size());
        assertVerdict(frames(sink).get(0), 0, 0);
    }

    @Test
    void preJoinByteLimitFallsBackEvenWithVerdictsAlreadyPaired() {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        byte[] packet = new byte[1024 * 1024];
        for (int i = 1; i <= CaptureStream.MAX_PRE_JOIN_BYTES / packet.length + 1; i++) {
            if (stream.wants(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID))
                stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, packet, i * 100L, -64, 384, SESSION);
            stream.cut(i * 100L + 50);
            verdict(stream, i, i, 0);
        }
        assertFalse(stream.capturing());
        assertTrue(sink.budget.take(TestBudget.MAX_HELD_BYTES));
    }

    @Test
    void preJoinBudgetIncludesOpenFramesAndIsSharedByPlayers() {
        TestBudget budget = new TestBudget(300);
        CaptureStream a = preparing(new TestSink(budget));
        CaptureStream b = preparing(new TestSink(budget));
        packet(a, 100);
        packet(b, 100);
        assertTrue(a.capturing());
        assertFalse(b.capturing());
        a.leave(100);
        assertTrue(budget.take(300));
    }

    @Test
    void reconnectDoesNotReplayFramesFromEitherSideOfTheDisconnect() {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        tick(stream, 100_000);
        stream.join(sink, join(), 200_000, SESSION);
        stream.reset();
        tick(stream, 300_000);
        stream.join(sink, join(), 400_000, SESSION);
        answer(stream);
        tick(stream, 500_000);
        verdict(stream, 3, 3, 0);
        assertEquals(2, sink.entries(IntakeMessage.KindCase.JOIN).size());
        assertEquals(1, frames(sink).size());
        assertVerdict(frames(sink).get(0), 0, 0);
        assertEquals(100, frames(sink).get(0).records().get(0).record().getAdvanceNanos());
        assertTrue(sink.budget.take(TestBudget.MAX_HELD_BYTES));
    }

    @Test
    void bufferedVelocityNamesAPingInAnEarlierBufferedFrame() {
        TestSink sink = new TestSink();
        CaptureStream stream = preparing(sink);
        stream.pingPacket(PING_ID);
        endOfTick(stream, 900, 100, 70);
        tick(stream, 150);
        engineVerdict(stream, 1, engine(1, false, null));
        tick(stream, 200);
        engineVerdict(stream, 2, engine(1, true, 900));
        stream.join(sink, join(), 300, SESSION);
        stream.capture(new int[] {SERVERBOUND_ID}, new int[] {PING_ID}, new int[] {DIMENSION_ID});
        assertTrue(frames(sink).get(1).verdict().getVelocity().hasPing());
        assertEquals(0, frames(sink).get(1).verdict().getVelocity().getPing());
        assertEquals(70L, frames(sink).get(0).serverTick());
    }

    private static CaptureStream preparing(TestSink sink) {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.prepare(sink);
        return stream;
    }

    @Test
    void openFrameCrossingJoinReservesEveryByteUntilCapture() {
        for (boolean refusedJoin : List.of(false, true)) {
            TestSink sink = new TestSink();
            CaptureStream stream = preparing(sink);
            stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[1], 100, -64, 384, SESSION);
            assertEquals(65, sink.used);
            sink.refuse = refusedJoin ? 1 : 0;
            stream.join(sink, join(), 200, SESSION);
            stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[7 * 1024 * 1024], 250, -64, 384, SESSION);
            assertEquals(7 * 1024 * 1024 + 129, sink.used);
            stream.cut(300);
            long retained = sink.used;
            assertTrue(retained > 7 * 1024 * 1024);
            verdict(stream, 1, 1, 0);
            assertEquals(retained, sink.used);
            answer(stream);
            assertEquals(0, sink.used);
            stream.reset();
            stream.reset();
            assertEquals(0, sink.used);
        }
    }

    @Test
    void afterTheServiceAnswersFramesStillTakeFromTheSharedBudget() {
        TestSink sink = new TestSink(new TestBudget(20_000));
        CaptureStream stream = answered(sink);
        packet(stream, 100);
        stream.cut(150);
        assertTrue(sink.used > 0);
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[25_000], 200, -64, 384, SESSION);
        stream.cut(250);
        verdict(stream, 2, 2, 0);
        assertEquals(1, frames(sink).size(), "the frame past the budget is dropped");
        assertEquals(0, sink.used);
        stream.leave(300);
        assertEquals(0, sink.used);
    }

    @Test
    void crossingJoinOverflowLeaveAndReconnectReleaseExactlyOnce() {
        for (int ending = 0; ending < 4; ending++) {
            TestSink sink = new TestSink();
            CaptureStream stream = preparing(sink);
            packet(stream, 100);
            stream.join(sink, join(), 200, SESSION);
            packet(stream, 250);
            stream.cut(300);
            verdict(stream, 1, 1, 0);
            packet(stream, 350);
            if (ending == 0) {
                overfill(stream, 400);
                stream.leave(450);
            } else if (ending == 1) stream.leave(450);
            else if (ending == 2) stream.reset();
            else stream.join(sink, join(), 450, SESSION);
            assertEquals(0, sink.used);
            stream.reset();
            assertEquals(0, sink.used);
        }
    }

    @Test
    void aSkippedTickEndHandlerLeavesItsVerdictMissing() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        tick(stream, 10);
        tick(stream, 20);
        tick(stream, 30);

        verdict(stream, 1, 1, 1L << IntakeWire.FLYING);
        // A failure before the handlers skips a tick end's run after it is counted.
        verdict(stream, 3, 2, 1L << IntakeWire.GLIDING);

        List<Frame> frames = frames(sink);
        assertEquals(3, frames.size());
        assertVerdict(frames.get(0), 0, 1L << IntakeWire.FLYING);
        assertEquals(1, frames.get(1).seq());
        assertMissing(frames.get(1));
        assertEquals(2, frames.get(2).seq());
        Verdict verdict = frames.get(2).verdict();
        assertEquals(1L << IntakeWire.GLIDING, verdict.getConditions());
        assertEquals(1, verdict.getTickEndSeq());
    }

    @Test
    void verdictsForTicksBeforeTheJoinFindNothing() {
        TestSink sink = new TestSink();
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(5);
        tick(stream, 10);
        stream.join(sink, join(), 0, SESSION);
        answer(stream);
        tick(stream, 20);

        verdict(stream, 6, 6, 1L << IntakeWire.FLYING);
        verdict(stream, 7, 7, 1L << IntakeWire.GLIDING);

        List<Frame> frames = frames(sink);
        assertEquals(1, frames.size());
        assertVerdict(frames.get(0), 0, 1L << IntakeWire.GLIDING);
    }

    @Test
    void resetDropsFramesStillWaitingForTheirVerdict() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        tick(stream, 10);
        tick(stream, 20);
        stream.reset();
        stream.join(sink, join(), 25, SESSION);
        answer(stream);
        tick(stream, 30);

        verdict(stream, 1, 1, 0);
        verdict(stream, 2, 2, 0);
        assertEquals(0, frames(sink).size());
        verdict(stream, 3, 3, 1L << IntakeWire.RIPTIDE);

        List<Frame> frames = frames(sink);
        assertEquals(1, frames.size());
        assertVerdict(frames.get(0), 0, 1L << IntakeWire.RIPTIDE);
    }

    @Test
    void awaitingIsCappedAndTheOldestFrameLeavesMissing() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        for (int i = 1; i <= CaptureStream.MAX_AWAITING; i++) tick(stream, i * 10L);
        assertEquals(0, frames(sink).size());

        tick(stream, 1000);

        assertEquals(1, frames(sink).size());
        assertEquals(0, frames(sink).get(0).seq());
        assertMissing(frames(sink).get(0));
        verdict(stream, 1, 1, 0);
        verdict(stream, CaptureStream.MAX_AWAITING + 1, CaptureStream.MAX_AWAITING + 1, 0);
        List<Frame> frames = frames(sink);
        assertEquals(CaptureStream.MAX_AWAITING + 1, frames.size());
        assertEquals(0, frames.get(1).gap());
        assertVerdict(frames.get(frames.size() - 1), CaptureStream.MAX_AWAITING, 0);
    }

    @Test
    void anOverfullOpenFrameIsDroppedWhole() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        byte[] half = new byte[(int) (CaptureStream.MAX_OPEN_FRAME_BYTES / 2) + 1];
        half[0] = SERVERBOUND_ID;
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, half, 1, 0, 0, null);
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, half, 2, 0, 0, null);
        assertTrue(!stream.wants(IntakeWire.SERVERBOUND, SERVERBOUND_ID));
        stream.cut(3);
        tick(stream, 10);

        verdict(stream, 1, 1, 0);
        verdict(stream, 2, 2, 0);

        List<Frame> frames = frames(sink);
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0).seq());
        assertEquals(1, frames.get(0).gap());
        assertEquals(1, frames.get(0).records(IntakeWire.SERVERBOUND).size());
    }

    @Test
    void manyTinyRecordsCountTowardTheOpenFrameCap() {
        CaptureStream stream = answered(new TestSink());
        long fit = CaptureStream.MAX_OPEN_FRAME_BYTES / (CaptureStream.RECORD_OVERHEAD_BYTES + 1);
        for (long i = 0; i < fit; i++) {
            stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, new byte[] {SERVERBOUND_ID}, i, 0, 0, null);
        }
        assertTrue(stream.wants(IntakeWire.SERVERBOUND, SERVERBOUND_ID));
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, new byte[] {SERVERBOUND_ID}, fit, 0, 0, null);
        assertTrue(!stream.wants(IntakeWire.SERVERBOUND, SERVERBOUND_ID));
    }

    @Test
    void heldFramesShareTheServersBudget() {
        TestBudget budget = new TestBudget(2L * CaptureStream.VERDICT_BYTES + 200);
        TestSink sinkA = new TestSink(budget);
        TestSink sinkB = new TestSink(budget);
        CaptureStream a = joined(sinkA);
        CaptureStream b = joined(sinkB);

        packet(a, 80);
        a.cut(1);
        verdict(a, 1, 1, 0);
        packet(b, 80);
        b.cut(1);
        // Both frames include record overhead and verdict headroom.
        verdict(b, 1, 1, 0);

        answer(a);
        assertEquals(1, frames(sinkA).size());
        packet(b, 80);
        b.cut(2);
        verdict(b, 2, 2, 0);
        answer(b);

        List<Frame> frames = frames(sinkB);
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0).seq());
        assertEquals(1, frames.get(0).gap());
        assertTrue(budget.take(2L * CaptureStream.VERDICT_BYTES + 200));
    }

    @Test
    void resetGivesHeldBytesBack() {
        TestBudget budget = new TestBudget(100);
        CaptureStream stream = joined(new TestSink(budget));
        packet(stream, 80);
        stream.cut(1);
        verdict(stream, 1, 1, 0);
        stream.reset();
        assertTrue(budget.take(100));
    }

    @Test
    void aJoinTheSocketRefusedIsSentAgain() {
        TestSink sink = new TestSink();
        sink.refuse = 1;
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.join(sink, join(), 0, SESSION);
        assertEquals(0, sink.sent.size());

        stream.cut(10);

        assertEquals(1, sink.sent.size());
        assertArrayEquals(join(), sink.sent.get(0));
    }

    @Test
    void framesTheSocketRefusedAreCountedInTheNextGap() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        sink.refuse = 2;
        tick(stream, 10);
        tick(stream, 20);
        tick(stream, 30);
        verdict(stream, 1, 1, 0);
        verdict(stream, 2, 2, 0);
        verdict(stream, 3, 3, 0);

        List<Frame> frames = frames(sink);
        assertEquals(1, frames.size());
        assertEquals(2, frames.get(0).seq());
        assertEquals(2, frames.get(0).gap());
    }

    @Test
    void metaFollowsEachWorldRecordAndEachGameModeChange() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        CaptureStream.Session respawned = new CaptureStream.Session(9, 1, "minecraft:the_end", true);
        stream.observe(respawned, 0, 256, 10);
        stream.tap(IntakeWire.CLIENTBOUND, DIMENSION_ID, new byte[] {DIMENSION_ID}, 10, -64, 384, respawned);
        CaptureStream.Session spectating = new CaptureStream.Session(9, 3, "minecraft:the_end", false);
        stream.observe(spectating, 0, 256, 20);
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[] {CLIENTBOUND_ID}, 20, 0, 0, spectating);
        stream.cut(30);
        verdict(stream, 1, 1, 0);

        List<Capture> records = frames(sink).get(0).records();
        assertEquals(
                List.of(IntakeWire.WORLD, IntakeWire.META, IntakeWire.META, IntakeWire.CLIENTBOUND, IntakeWire.VERDICT),
                records.stream().map(Capture::kind).toList());
        Meta world = records.get(1).record().getMeta();
        assertEquals(9, world.getEntityId());
        assertEquals(GameMode.GAME_MODE_CREATIVE, world.getGameMode());
        assertEquals("minecraft:the_end", world.getDimensionKey());
        assertEquals(
                Meta.newBuilder().setGameMode(GameMode.GAME_MODE_SPECTATOR).build(),
                records.get(2).record().getMeta());
    }

    @Test
    void theTicksFlagsGoInItsVerdict() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        tick(stream, 10);
        tick(stream, 20);

        verdict(stream, 1, 1, 0, List.of(IntakeWire.Flag.of("Reach", 3.0), IntakeWire.Flag.of("Timer", 1.25)));
        verdict(stream, 2, 2, 0, List.of());

        List<Frame> frames = frames(sink);
        Verdict first = frames.get(0).verdict();
        assertEquals(
                Verdict.newBuilder()
                        .setTickEndSeq(0)
                        .addFlags(Flag.newBuilder().setCheck("Reach").setViolationsHundredths(300))
                        .addFlags(Flag.newBuilder().setCheck("Timer").setViolationsHundredths(125))
                        .build(),
                first);
        assertVerdict(frames.get(1), 1, 0);
    }

    @Test
    void aFrameKeepsRoomForItsFlags() {
        TestSink sink = new TestSink();
        sink.maxFrameBytes = 4000;
        CaptureStream stream = answered(sink);
        for (int i = 0; i < 5; i++) packet(stream, 500);
        tick(stream, 10);
        List<IntakeWire.Flag> flags = new ArrayList<>();
        for (int i = 0; i < IntakeWire.MAX_FLAGS; i++) flags.add(IntakeWire.Flag.of("x".repeat(60) + i, i));

        verdict(stream, 1, 1, 0, flags);

        byte[] record = sink.entries(IntakeMessage.KindCase.FRAME).get(0);
        assertTrue(record.length <= sink.maxFrameBytes, record.length + " bytes");
        Frame frame = parse(record);
        assertEquals(IntakeWire.MAX_FLAGS, frame.verdict().getFlagsCount());
        assertTrue(frame.records(IntakeWire.GAP).size() > 0);
    }

    @Test
    void leavingSendsWhatIsLeftMarkedMissing() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        tick(stream, 10);
        packet(stream, 4);

        stream.leave(20);

        List<Frame> frames = frames(sink);
        assertEquals(2, frames.size());
        assertMissing(frames.get(0));
        assertEquals(1, frames.get(1).seq());
        assertMissing(frames.get(1));
        assertEquals(1, sink.entries(IntakeMessage.KindCase.LEAVE).size());
    }

    @Test
    void tappedPacketsLosePlayerTextOrAreNotKept() {
        int writing = 4;
        PlayerText text = new PlayerText(
                777,
                ProtocolCodecs.decoder().playerText(ProtocolVersion.V26_3),
                name -> name.equals("open_screen") ? CLIENTBOUND_ID : -1,
                name -> name.equals("edit_book") ? writing : name.equals("client_tick_end") ? SERVERBOUND_ID : -1);
        TestSink sink = new TestSink();
        CaptureStream stream = joined(sink);
        stream.text(text);
        stream.capture(new int[] {SERVERBOUND_ID, writing}, new int[] {CLIENTBOUND_ID}, new int[] {DIMENSION_ID});
        byte[] titled = {CLIENTBOUND_ID, 3, 2, 8, 0, 3, 'B', 'o', 'b'};
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, titled, 10, -64, 384, SESSION);
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, Arrays.copyOf(titled, 8), 11, -64, 384, SESSION);
        stream.tap(IntakeWire.SERVERBOUND, writing, new byte[] {(byte) writing, 0, 1, 0}, 12, 0, 0, null);
        byte[] untouched = {SERVERBOUND_ID, 7, 7};
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, untouched, 13, 0, 0, null);
        tick(stream, 20);
        verdict(stream, 1, 1, 0);

        List<Capture> records = frames(sink).getFirst().records();
        assertEquals(
                List.of(IntakeWire.CLIENTBOUND, IntakeWire.SERVERBOUND, IntakeWire.SERVERBOUND, IntakeWire.VERDICT),
                records.stream().map(Capture::kind).toList());
        assertArrayEquals(
                new byte[] {CLIENTBOUND_ID, 3, 2, 8, 0, 0},
                records.get(0).record().getClientbound().toByteArray());
        assertArrayEquals(untouched, records.get(1).record().getServerbound().toByteArray());
    }

    @Test
    void withoutCodecsTextGoesOutOnlyWhereAllowedAndChatNever() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.of(777));
        int open =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:open_screen");
        int chat =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:player_chat");
        int command =
                data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:chat_command");
        byte[] titled = {(byte) open, 3, 2, 8, 0, 3, 'B', 'o', 'b'};
        byte[] said = {(byte) chat, 2, 'h', 'i'};
        byte[] typed = {(byte) command, 5, 'l', 'o', 'g', 'i', 'n'};
        for (boolean textStripped : new boolean[] {true, false}) {
            TestSink sink = new TestSink();
            sink.textStripped = textStripped;
            CaptureStream stream = joined(sink);
            stream.text(PlayerText.load(777, version -> {
                throw new IllegalStateException("no codecs");
            }));
            stream.capture(new int[] {SERVERBOUND_ID, command}, new int[] {open, chat}, new int[0]);
            stream.tap(IntakeWire.CLIENTBOUND, open, titled, 10, -64, 384, SESSION);
            stream.tap(IntakeWire.CLIENTBOUND, chat, said, 11, -64, 384, SESSION);
            stream.tap(IntakeWire.SERVERBOUND, command, typed, 12, 0, 0, null);
            tick(stream, 20);
            verdict(stream, 1, 1, 0);

            List<Capture> records = frames(sink).getFirst().records();
            List<Integer> kinds = textStripped
                    ? List.of(IntakeWire.SERVERBOUND, IntakeWire.VERDICT)
                    : List.of(IntakeWire.CLIENTBOUND, IntakeWire.SERVERBOUND, IntakeWire.VERDICT);
            assertEquals(kinds, records.stream().map(Capture::kind).toList(), "textStripped " + textStripped);
            if (!textStripped) {
                assertArrayEquals(
                        titled, records.getFirst().record().getClientbound().toByteArray());
            }
        }
    }

    @Test
    void withoutCodecsPreJoinTextWaitsForTheSocketsFlag() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.of(777));
        int open =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:open_screen");
        int chat =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:player_chat");
        int moved =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:move_entity_pos");
        byte[] titled = {(byte) open, 3, 2, 8, 0, 3, 'B', 'o', 'b'};
        byte[] said = {(byte) chat, 2, 'h', 'i'};
        byte[] movement = {(byte) moved, 1, 2, 3, 4, 5, 6, 7, 1};
        for (boolean textStripped : new boolean[] {true, false}) {
            TestSink sink = new TestSink();
            CaptureStream stream = new CaptureStream(PLAYER);
            stream.start(5);
            stream.prepare(sink);
            stream.text(PlayerText.load(777, version -> {
                throw new IllegalStateException("no codecs");
            }));
            stream.tap(IntakeWire.CLIENTBOUND, open, titled, 100, -64, 384, SESSION);
            stream.tap(IntakeWire.CLIENTBOUND, chat, said, 110, -64, 384, SESSION);
            stream.tap(IntakeWire.CLIENTBOUND, moved, movement, 120, -64, 384, SESSION);
            stream.cut(150);
            verdict(stream, 6, 6, 0);

            sink.textStripped = textStripped;
            stream.join(sink, join(), 300, SESSION);
            stream.capture(new int[] {SERVERBOUND_ID}, new int[] {open, chat, moved}, new int[0]);

            List<Capture> records = frames(sink).getFirst().records();
            List<byte[]> kept = records.stream()
                    .filter(record -> record.kind() == IntakeWire.CLIENTBOUND)
                    .map(record -> record.record().getClientbound().toByteArray())
                    .toList();
            assertEquals(textStripped ? 1 : 2, kept.size(), "textStripped " + textStripped);
            if (!textStripped) assertArrayEquals(titled, kept.getFirst());
            assertArrayEquals(movement, kept.getLast());
        }
    }

    @Test
    void chatAndCommandsAreNeverKeptWhateverTheServiceAsksFor() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.of(777));
        int command =
                data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:chat_command");
        int chat =
                data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:player_chat");
        TestSink sink = new TestSink();
        CaptureStream stream = joined(sink);
        stream.text(PlayerText.of(777));
        byte[] typed = {(byte) command, 5, 'l', 'o', 'g', 'i', 'n'};
        byte[] said = {(byte) chat, 2, 'h', 'i'};
        assertTrue(stream.wants(IntakeWire.SERVERBOUND, command));
        stream.tap(IntakeWire.SERVERBOUND, command, typed, 5, 0, 0, null);
        stream.tap(IntakeWire.CLIENTBOUND, chat, said, 6, -64, 384, SESSION);
        tick(stream, 10);
        stream.capture(new int[] {SERVERBOUND_ID, command}, new int[] {chat}, new int[0]);
        stream.tap(IntakeWire.SERVERBOUND, command, typed, 15, 0, 0, null);
        stream.tap(IntakeWire.CLIENTBOUND, chat, said, 16, -64, 384, SESSION);
        tick(stream, 20);
        verdict(stream, 1, 1, 0);
        verdict(stream, 2, 2, 0);

        List<Frame> frames = frames(sink);
        assertEquals(2, frames.size());
        for (Frame frame : frames) {
            assertEquals(
                    List.of(IntakeWire.SERVERBOUND, IntakeWire.VERDICT),
                    frame.records().stream().map(Capture::kind).toList());
        }
    }

    @Test
    void hugeIdsAllocateNothingLarge() {
        for (int huge : new int[] {2_000_000_000, Integer.MAX_VALUE, 1024}) {
            TestSink sink = new TestSink();
            CaptureStream stream = joined(sink);
            int[] ids = new int[CaptureStream.MAX_POLICY_IDS + 1];
            Arrays.fill(ids, huge);
            ids[0] = SERVERBOUND_ID;
            ids[ids.length - 1] = CLIENTBOUND_ID;
            stream.capture(ids, new int[] {huge, CLIENTBOUND_ID, -5}, new int[] {huge});
            assertTrue(stream.wants(IntakeWire.SERVERBOUND, SERVERBOUND_ID));
            assertFalse(stream.wants(IntakeWire.SERVERBOUND, CLIENTBOUND_ID));
            assertFalse(stream.wants(IntakeWire.SERVERBOUND, huge));
            assertTrue(stream.wants(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID));
            assertFalse(stream.wants(IntakeWire.CLIENTBOUND, huge));
        }
    }

    @Test
    void anOutcomeRidesAFrameAfterItsActionsAndNamesIt() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        packet(stream, 4);
        packet(stream, 4);
        ActionRef action = stream.action();
        stream.outcome(applied(Outcome.Type.TYPE_CLICK), action, 50);
        stream.cut(100);
        verdict(stream, 1, 1, 0);
        tick(stream, 200);
        verdict(stream, 2, 2, 0);

        List<Frame> frames = frames(sink);
        assertFalse(frames.get(0).verdict().hasOutcomes());
        Outcomes carried = frames.get(1).verdict().getOutcomes();
        assertEquals(1, carried.getFrameSeq());
        Outcome outcome = carried.getOutcomes(0);
        assertEquals(0, outcome.getActionFrame());
        assertEquals(1, outcome.getActionIndex());
        assertEquals(50, outcome.getNanos());
        assertEquals(Outcome.Status.STATUS_APPLIED, outcome.getStatus());
    }

    @Test
    void eachActionIsNamedByItsOwnPacket() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        packet(stream, 4);
        ActionRef first = stream.action();
        ActionRef again = stream.action();
        stream.skipped();
        ActionRef skipped = stream.action();
        packet(stream, 4);
        ActionRef second = stream.action();
        for (ActionRef action : List.of(first, again, skipped, second))
            stream.outcome(applied(Outcome.Type.TYPE_CLICK), action, 50);
        stream.cut(100);
        verdict(stream, 1, 1, 0);
        tick(stream, 200);
        verdict(stream, 2, 2, 0);

        List<Outcome> outcomes = frames(sink).get(1).verdict().getOutcomes().getOutcomesList();
        assertEquals(4, outcomes.size());
        assertEquals(0, outcomes.get(0).getActionIndex());
        assertEquals(0, outcomes.get(1).getActionIndex());
        assertFalse(outcomes.get(2).hasActionIndex());
        assertEquals(1, outcomes.get(3).getActionIndex());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actionsWithoutATickEndStayBounded(boolean overflowed) {
        CaptureStream stream = answered(new TestSink());
        if (overflowed) overfill(stream, 1);
        List<ActionRef> actions = new ArrayList<>();
        for (int i = 0; i < 10 * CaptureStream.MAX_OPEN_ACTIONS; i++) {
            stream.skipped();
            actions.add(stream.action());
        }
        long open = actions.stream()
                .filter(action -> action.state == ActionRef.OPEN)
                .count();
        assertEquals(CaptureStream.MAX_OPEN_ACTIONS, open);
        assertEquals(ActionRef.OPEN, actions.get(actions.size() - 1).state);
    }

    @Test
    void anActionLeftOutOfItsFrameIsNamedByFrameAlone() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        stream.skipped();
        stream.outcome(applied(Outcome.Type.TYPE_HELD), stream.action(), 50);
        stream.cut(100);
        verdict(stream, 1, 1, 0);
        stream.outcome(applied(Outcome.Type.TYPE_POP), null, 150);
        tick(stream, 200);
        verdict(stream, 2, 2, 0);

        List<Outcome> outcomes = frames(sink).get(1).verdict().getOutcomes().getOutcomesList();
        assertEquals(2, outcomes.size());
        assertEquals(0, outcomes.get(0).getActionFrame());
        assertFalse(outcomes.get(0).hasActionIndex());
        assertFalse(outcomes.get(1).hasActionFrame());
    }

    @Test
    void outcomesOfAFrameTheSocketRefusedGoInTheNext() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        stream.outcome(applied(Outcome.Type.TYPE_POP), null, 50);
        sink.refuse = 1;
        tick(stream, 100);
        verdict(stream, 1, 1, 0);
        tick(stream, 200);
        verdict(stream, 2, 2, 0);

        List<Frame> frames = frames(sink);
        assertEquals(1, frames.size());
        assertEquals(1, frames.get(0).verdict().getOutcomes().getOutcomesCount());
    }

    @Test
    void aResetDropsWaitingOutcomesAndThoseOfEarlierCapturesActions() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        packet(stream, 4);
        ActionRef action = stream.action();
        stream.outcome(applied(Outcome.Type.TYPE_POP), null, 50);
        long dropped = CaptureStream.outcomesDropped.get();
        stream.join(sink, join(), 300, SESSION);
        answer(stream);
        stream.outcome(applied(Outcome.Type.TYPE_CLICK), action, 350);
        tick(stream, 400);
        verdict(stream, 1, 1, 0);
        tick(stream, 500);
        verdict(stream, 2, 2, 0);

        assertTrue(frames(sink).stream().noneMatch(frame -> frame.verdict().hasOutcomes()));
        assertTrue(CaptureStream.outcomesDropped.get() - dropped >= 2);
    }

    @Test
    void aMissingVerdictStillCarriesOutcomes() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        stream.outcome(applied(Outcome.Type.TYPE_POP), null, 50);
        tick(stream, 100);
        tick(stream, 200);
        verdict(stream, 2, 2, 0);

        Verdict missing = frames(sink).get(0).verdict();
        assertTrue(missing.getMissing());
        assertEquals(1, missing.getOutcomes().getOutcomesCount());
    }

    private static Outcome applied(Outcome.Type type) {
        return Outcome.newBuilder()
                .setType(type)
                .setStatus(Outcome.Status.STATUS_APPLIED)
                .build();
    }

    private static CaptureStream joined(TestSink sink) {
        CaptureStream stream = new CaptureStream(PLAYER);
        stream.start(0);
        stream.join(sink, join(), 0, SESSION);
        return stream;
    }

    private static CaptureStream answered(TestSink sink) {
        CaptureStream stream = joined(sink);
        answer(stream);
        return stream;
    }

    /** Joined, the service asking for pings too. */
    private static CaptureStream pinging(TestSink sink) {
        CaptureStream stream = joined(sink);
        stream.pingPacket(PING_ID);
        stream.capture(new int[] {SERVERBOUND_ID}, new int[] {CLIENTBOUND_ID, PING_ID}, new int[] {DIMENSION_ID});
        return stream;
    }

    private static byte[] join() {
        return IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0);
    }

    private static void answer(CaptureStream stream) {
        stream.capture(new int[] {SERVERBOUND_ID}, new int[] {CLIENTBOUND_ID}, new int[] {DIMENSION_ID});
    }

    private static void packet(CaptureStream stream, int bytes) {
        byte[] packet = new byte[bytes];
        packet[0] = SERVERBOUND_ID;
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, packet, 1, 0, 0, null);
    }

    /** A clientbound packet that fills the open frame past its cap. */
    private static void overfill(CaptureStream stream, long nanos) {
        stream.tap(
                IntakeWire.CLIENTBOUND,
                CLIENTBOUND_ID,
                new byte[(int) CaptureStream.MAX_OPEN_FRAME_BYTES],
                nanos,
                -64,
                384,
                SESSION);
    }

    private static void tick(CaptureStream stream, long nanos) {
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, new byte[] {SERVERBOUND_ID}, nanos, 0, 0, null);
        stream.cut(nanos);
    }

    private static void verdict(CaptureStream stream, long tickEnd, long handled, long conditions) {
        verdict(stream, tickEnd, handled, conditions, List.of());
    }

    private static void verdict(
            CaptureStream stream, long tickEnd, long handled, long conditions, List<IntakeWire.Flag> flags) {
        stream.verdict(tickEnd, handled, conditions, flags, null, List.of(), null);
    }

    private static void assertVerdict(Frame frame, long seq, long conditions) {
        assertEquals(seq, frame.seq());
        assertEquals(
                Verdict.newBuilder()
                        .setConditions(conditions)
                        .setTickEndSeq(seq)
                        .build(),
                frame.verdict());
    }

    private static void assertMissing(Frame frame) {
        assertEquals(Verdict.newBuilder().setMissing(true).build(), frame.verdict());
    }

    /** A record and its body's field number, which is its kind. */
    record Capture(int kind, Record record) {}

    record Frame(long seq, List<Capture> records, Long serverTick) {
        List<Capture> records(int kind) {
            return records.stream().filter(record -> record.kind() == kind).toList();
        }

        /** The whole frames a leading GAP says were lost; 0 without one. */
        long gap() {
            if (records.isEmpty() || records.get(0).kind() != IntakeWire.GAP) return 0;
            return records.get(0).record().getGap().getLostFrames();
        }

        Verdict verdict() {
            Capture last = records.get(records.size() - 1);
            assertEquals(IntakeWire.VERDICT, last.kind());
            return last.record().getVerdict();
        }
    }

    static Frame parse(byte[] entry) {
        IntakeMessage message = TestSink.message(entry);
        List<Capture> records = new ArrayList<>();
        for (Record record : message.getFrame().getRecordsList()) {
            records.add(new Capture(record.getBodyCase().getNumber(), record));
        }
        boolean ticked = message.getFrame().hasServerTick();
        return new Frame(
                message.getFrame().getSeq(),
                records,
                ticked ? message.getFrame().getServerTick() : null);
    }

    private static List<Frame> frames(TestSink sink) {
        List<Frame> frames = new ArrayList<>();
        for (byte[] entry : sink.entries(IntakeMessage.KindCase.FRAME)) {
            assertEquals(
                    PLAYER, IntakeWire.uuid(TestSink.message(entry).getFrame().getPlayer()));
            frames.add(parse(entry));
        }
        return frames;
    }

    private static final int PING_ID = 4;

    private static IntakeWire.Engine engine(int ground, boolean velocity, Integer ping) {
        return new IntakeWire.Engine(0, ground, 0, 64, 64, 64, 0, 600, false, 0, 0, false, velocity, ping);
    }

    private static void engineVerdict(CaptureStream stream, long tickEnd, IntakeWire.Engine engine) {
        stream.verdict(tickEnd, tickEnd, 0, List.of(), null, List.of(), engine);
    }

    private static boolean restated(Frame frame) {
        return frame.verdict().hasFall();
    }

    private static void ping(CaptureStream stream, int wireId, long nanos) {
        ping(stream, wireId, nanos, 0);
    }

    @Test
    void engineStateIsRestatedOnlyWhenTheServiceMayNotHaveIt() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        for (int i = 1; i <= 6; i++) tick(stream, i * 10L);

        engineVerdict(stream, 1, engine(1, false, null));
        engineVerdict(stream, 2, engine(1, false, null));
        engineVerdict(stream, 3, engine(0, false, null));
        // The fourth tick end's verdict never comes.
        engineVerdict(stream, 5, engine(0, false, null));
        sink.refuse = 1;
        engineVerdict(stream, 6, engine(0, false, null));
        tick(stream, 70);
        engineVerdict(stream, 7, engine(0, false, null));

        List<Frame> frames = frames(sink);
        assertEquals(6, frames.size());
        assertTrue(restated(frames.get(0)));
        assertEquals(
                Verdict.newBuilder().setTickEndSeq(1).build(), frames.get(1).verdict());
        assertEquals(
                Verdict.newBuilder()
                        .setTickEndSeq(2)
                        .setGround(Ground.GROUND_OFF)
                        .build(),
                frames.get(2).verdict());
        assertMissing(frames.get(3));
        assertTrue(restated(frames.get(4)));
        assertEquals(1, frames.get(5).gap());
        assertTrue(restated(frames.get(5)));
    }

    @Test
    void engineStateIsRestatedEveryKeyframe() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        int frames = IntakeWire.KEYFRAME_FRAMES * 2 + 1;
        for (int i = 1; i <= frames; i++) {
            tick(stream, i * 10L);
            engineVerdict(stream, i, engine(1, false, null));
        }
        List<Integer> restatedAt = new ArrayList<>();
        List<Frame> sent = frames(sink);
        for (int i = 0; i < sent.size(); i++) if (restated(sent.get(i))) restatedAt.add(i);
        assertEquals(List.of(0, IntakeWire.KEYFRAME_FRAMES, IntakeWire.KEYFRAME_FRAMES * 2), restatedAt);
    }

    @Test
    void velocityNamesItsPingByTheOrderTheServiceGotPingsIn() {
        TestSink sink = new TestSink();
        CaptureStream stream = pinging(sink);
        ping(stream, 900, 1);
        ping(stream, 901, 2);
        tick(stream, 10);
        ping(stream, 902, 11);
        tick(stream, 20);
        ping(stream, 903, 21);
        tick(stream, 30);
        ping(stream, 904, 31);
        ping(stream, 905, 32);
        tick(stream, 40);

        engineVerdict(stream, 1, engine(1, true, 901));
        engineVerdict(stream, 2, engine(1, true, 900));
        // Frame 3 is refused, so ping 903 never reaches the service and 904 is its fourth.
        sink.refuse = 1;
        engineVerdict(stream, 3, engine(1, false, null));
        engineVerdict(stream, 4, engine(1, true, 903));
        tick(stream, 50);
        engineVerdict(stream, 5, engine(1, true, 904));

        List<Frame> frames = frames(sink);
        assertEquals(4, frames.size());
        assertEquals(1, frames.get(0).verdict().getVelocity().getPing());
        assertTrue(frames.get(1).verdict().getVelocity().hasPing());
        assertEquals(0, frames.get(1).verdict().getVelocity().getPing());
        assertTrue(frames.get(2).verdict().hasVelocity());
        assertFalse(frames.get(2).verdict().getVelocity().hasPing());
        assertEquals(3, frames.get(3).verdict().getVelocity().getPing());
    }

    @Test
    void aPingLeftOutOfAnAcceptedFrameTakesNoOrdinal() {
        List<Frame> frames = null;
        // The largest packet before the ping that leaves no room for the ping in its frame.
        for (int size = 3000; size > 2000 && frames == null; size--) {
            List<Frame> sent = pingAfter(size);
            Frame first = sent.get(0);
            boolean large = first.records(IntakeWire.CLIENTBOUND).stream()
                    .anyMatch(capture -> capture.record().getClientbound().byteAt(0) == CLIENTBOUND_ID);
            boolean ping = first.records(IntakeWire.CLIENTBOUND).stream()
                    .anyMatch(capture -> capture.record().getClientbound().byteAt(0) == PING_ID);
            if (large && !ping) frames = sent;
        }
        assertTrue(frames != null);
        assertEquals(2, frames.size());
        assertTrue(frames.get(0).verdict().hasVelocity());
        assertFalse(frames.get(0).verdict().getVelocity().hasPing());
        assertTrue(frames.get(1).verdict().getVelocity().hasPing());
        assertEquals(0, frames.get(1).verdict().getVelocity().getPing());
    }

    /** Two frames: a packet of {@code size} then ping 900 in the first, ping 901 in the second; each names its ping. */
    private static List<Frame> pingAfter(int size) {
        TestSink sink = new TestSink();
        sink.maxFrameBytes = 3000;
        CaptureStream stream = pinging(sink);
        byte[] large = new byte[size];
        large[0] = CLIENTBOUND_ID;
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, large, 1, -64, 384, SESSION);
        ping(stream, 900, 2);
        tick(stream, 10);
        ping(stream, 901, 11);
        tick(stream, 20);
        engineVerdict(stream, 1, engine(1, true, 900));
        engineVerdict(stream, 2, engine(1, true, 901));
        return frames(sink);
    }

    @Test
    void refusedFrameCarriesWorldIntoTheNext() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        CaptureStream.Session respawned = new CaptureStream.Session(9, 1, "minecraft:the_nether", true);
        stream.observe(respawned, 0, 256, 10);
        stream.tap(IntakeWire.CLIENTBOUND, DIMENSION_ID, new byte[] {DIMENSION_ID}, 10, 0, 256, respawned);
        tick(stream, 20);
        sink.refuse = 1;
        verdict(stream, 1, 1, 0);
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[] {CLIENTBOUND_ID}, 30, 0, 256, respawned);
        tick(stream, 40);
        verdict(stream, 2, 2, 0);
        tick(stream, 50);
        verdict(stream, 3, 3, 0);

        List<Frame> frames = frames(sink);
        assertEquals(2, frames.size());
        List<Capture> records = frames.get(0).records();
        assertEquals(
                List.of(IntakeWire.GAP, IntakeWire.WORLD, IntakeWire.META),
                records.subList(0, 3).stream().map(Capture::kind).toList());
        assertEquals(0, records.get(1).record().getWorld().getMinY());
        assertEquals(256, records.get(1).record().getWorld().getHeight());
        Meta meta = records.get(2).record().getMeta();
        assertEquals(9, meta.getEntityId());
        assertEquals("minecraft:the_nether", meta.getDimensionKey());
        assertTrue(frames.get(1).records(IntakeWire.WORLD).isEmpty());
    }

    @Test
    void overfullFrameCarriesWorldIntoTheNext() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        CaptureStream.Session respawned = new CaptureStream.Session(9, 1, "minecraft:the_nether", true);
        stream.observe(respawned, 0, 256, 10);
        stream.tap(IntakeWire.CLIENTBOUND, DIMENSION_ID, new byte[] {DIMENSION_ID}, 10, 0, 256, respawned);
        stream.tap(
                IntakeWire.CLIENTBOUND,
                CLIENTBOUND_ID,
                new byte[(int) CaptureStream.MAX_OPEN_FRAME_BYTES],
                11,
                0,
                256,
                respawned);
        tick(stream, 20);
        verdict(stream, 1, 1, 0);
        tick(stream, 30);
        verdict(stream, 2, 2, 0);

        List<Frame> frames = frames(sink);
        assertEquals(1, frames.size());
        assertEquals(
                List.of(IntakeWire.GAP, IntakeWire.WORLD, IntakeWire.META),
                frames.get(0).records().subList(0, 3).stream()
                        .map(Capture::kind)
                        .toList());
    }

    @Test
    void aRespawnInAnOverfullFrameIsRepairedWithTheNewWorldsFacts() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        overfill(stream, 5);
        CaptureStream.Session respawned = new CaptureStream.Session(9, 1, "minecraft:the_nether", true);
        stream.observe(respawned, 0, 256, 10);
        tick(stream, 20);
        verdict(stream, 1, 1, 0);
        stream.tap(IntakeWire.SERVERBOUND, SERVERBOUND_ID, new byte[] {SERVERBOUND_ID}, 25, 0, 0, null);
        tick(stream, 30);
        verdict(stream, 2, 2, 0);

        List<Frame> frames = frames(sink);
        assertEquals(1, frames.size());
        List<Capture> records = frames.get(0).records();
        assertEquals(
                List.of(IntakeWire.GAP, IntakeWire.WORLD, IntakeWire.META),
                records.subList(0, 3).stream().map(Capture::kind).toList());
        assertEquals(0, records.get(1).record().getWorld().getMinY());
        assertEquals(256, records.get(1).record().getWorld().getHeight());
        assertEquals("minecraft:the_nether", records.get(2).record().getMeta().getDimensionKey());
    }

    @Test
    void theTicksGeometryAndSightGoInItsVerdict() {
        TestSink sink = new TestSink();
        CaptureStream stream = answered(sink);
        tick(stream, 10);
        IntakeWire.Geometry geometry =
                new IntakeWire.Geometry(0, "minecraft:player", new double[6], new double[] {0, 1.62, 0}, 2.0);
        stream.verdict(1, 1, 0, List.of(), new IntakeWire.Target(5, 2.0), List.of(geometry), null);

        Verdict verdict = frames(sink).get(0).verdict();
        assertEquals(5, verdict.getTarget().getEntityId());
        assertEquals(2.0, verdict.getTarget().getReach(), 0);
        assertEquals(1, verdict.getGeometryCount());
        assertEquals(0, verdict.getTickEndSeq());
    }

    @Test
    void aPingWithoutAWireIdStillTakesItsOrdinal() {
        TestSink sink = new TestSink();
        CaptureStream stream = pinging(sink);
        ping(stream, 900, 1);
        // The service's replay keeps every ping, so these short ones take ordinals 1 and 3.
        stream.tap(IntakeWire.CLIENTBOUND, PING_ID, new byte[] {PING_ID, 1, 2}, 2, -64, 384, SESSION);
        ping(stream, 901, 3);
        tick(stream, 10);
        stream.tap(IntakeWire.CLIENTBOUND, PING_ID, new byte[] {PING_ID}, 11, -64, 384, SESSION);
        ping(stream, 902, 12);
        tick(stream, 20);

        engineVerdict(stream, 1, engine(1, true, 901));
        engineVerdict(stream, 2, engine(1, true, 902));

        List<Frame> frames = frames(sink);
        assertEquals(2, frames.get(0).verdict().getVelocity().getPing());
        assertEquals(4, frames.get(1).verdict().getVelocity().getPing());
    }

    private static void ping(CaptureStream stream, int wireId, long nanos, int kind) {
        byte[] packet = {PING_ID, (byte) (wireId >>> 24), (byte) (wireId >>> 16), (byte) (wireId >>> 8), (byte) wireId};
        stream.tap(IntakeWire.CLIENTBOUND, PING_ID, packet, nanos, -64, 384, SESSION, kind);
    }

    private static void endOfTick(CaptureStream stream, int wireId, long nanos, long serverTick) {
        stream.endOfTick(serverTick);
        ping(stream, wireId, nanos, IntakeWire.PING_END_OF_TICK);
    }

    private static void clientbound(CaptureStream stream, long nanos) {
        stream.tap(IntakeWire.CLIENTBOUND, CLIENTBOUND_ID, new byte[] {CLIENTBOUND_ID, 1}, nanos, -64, 384, SESSION);
    }

    /**
     * Each clientbound record's and ping marker's server tick, from the frame's and the deltas; its ping kinds alongside.
     */
    private static List<long[]> serverTicks(Frame frame) {
        List<long[]> ticks = new ArrayList<>();
        long tick = frame.serverTick() == null ? -1 : frame.serverTick();
        for (Capture capture : frame.records()) {
            boolean marker = capture.kind() == IntakeWire.PING_MARKER;
            if (!marker && capture.kind() != IntakeWire.CLIENTBOUND) continue;
            tick += capture.record().getTickDelta();
            ticks.add(new long[] {
                frame.serverTick() == null ? -1 : tick,
                marker ? capture.record().getPingValue() : capture.record().getPingKindValue()
            });
        }
        return ticks;
    }

    @Test
    void firstFrameTakesItsTickWithoutEndPings() {
        TestSink sink = new TestSink();
        CaptureStream stream = pinging(sink);
        // The tick end is marked by an end-of-tick ping the stream never taps.
        clientbound(stream, 11);
        ping(stream, 7, 12, IntakeWire.PING_FOREIGN);
        stream.endOfTick(100);
        clientbound(stream, 14);
        ping(stream, 9, 15, IntakeWire.PING_FOREIGN);
        tick(stream, 20);
        clientbound(stream, 21);
        stream.endOfTick(101);
        tick(stream, 30);
        for (int seq = 1; seq <= 2; seq++) verdict(stream, seq, seq, 0);

        List<Frame> frames = frames(sink);
        assertEquals(2, frames.size());
        assertArrayEquals(
                new long[][] {{100, 0}, {100, IntakeWire.PING_FOREIGN}, {101, 0}, {101, IntakeWire.PING_FOREIGN}},
                serverTicks(frames.get(0)).toArray(long[][]::new));
        assertArrayEquals(new long[][] {{101, 0}}, serverTicks(frames.get(1)).toArray(long[][]::new));
    }

    @Test
    void clientboundRecordsTakeTheNextEndOfTickPingsTick() {
        TestSink sink = new TestSink();
        CaptureStream stream = pinging(sink);
        // Before any end-of-tick ping, and none follows in the frame: no ticks.
        clientbound(stream, 1);
        tick(stream, 10);
        // A packet before the session's first end-of-tick ping takes that ping's tick.
        clientbound(stream, 11);
        ping(stream, 7, 12, 3);
        endOfTick(stream, 8, 13, 100);
        clientbound(stream, 14);
        ping(stream, 9, 15, 2);
        tick(stream, 20);
        // No end-of-tick ping in the frame: the tick after the last one's.
        clientbound(stream, 21);
        ping(stream, 10, 22, IntakeWire.PING_FOREIGN);
        tick(stream, 30);
        // A server tick with no end-of-tick ping of its own: the next one in the frame wins.
        clientbound(stream, 31);
        endOfTick(stream, 11, 32, 103);
        tick(stream, 40);
        for (int seq = 1; seq <= 4; seq++) verdict(stream, seq, seq, 0);

        List<Frame> frames = frames(sink);
        assertEquals(4, frames.size());
        assertEquals(null, frames.get(0).serverTick());
        assertEquals(
                0, frames.get(0).records(IntakeWire.CLIENTBOUND).get(0).record().getTickDelta());
        assertEquals(100L, frames.get(1).serverTick());
        assertArrayEquals(
                new long[][] {{100, 0}, {100, 3}, {100, 1}, {101, 0}, {101, 2}},
                serverTicks(frames.get(1)).toArray(long[][]::new));
        assertArrayEquals(
                new long[][] {{101, 0}, {101, 4}}, serverTicks(frames.get(2)).toArray(long[][]::new));
        assertArrayEquals(
                new long[][] {{103, 0}, {103, 1}}, serverTicks(frames.get(3)).toArray(long[][]::new));
    }

    @Test
    void aTickEndWithoutAPingStillEndsTheTick() {
        TestSink sink = new TestSink();
        CaptureStream stream = pinging(sink);
        endOfTick(stream, 8, 1, 99);
        tick(stream, 5);
        // Tick 100's packets share leading ping 9, and the tick ends without a ping of its own.
        ping(stream, 9, 11, 3);
        clientbound(stream, 12);
        stream.endOfTick(100);
        clientbound(stream, 13);
        tick(stream, 20);
        for (int seq = 1; seq <= 2; seq++) verdict(stream, seq, seq, 0);

        List<Frame> frames = frames(sink);
        assertEquals(2, frames.size());
        assertArrayEquals(
                new long[][] {{100, 3}, {100, 0}, {101, 0}}, serverTicks(frames.get(1)).toArray(long[][]::new));
    }
}
