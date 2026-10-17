package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.cloud.capture.CaptureStream;
import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagCut;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.intake.v3.DiagWatch;
import ac.cult.cultac.cloud.intake.v3.Flag;
import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Reply;
import ac.cult.cultac.cloud.transport.CloudConnections;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Diagnostics over a real socket to a stand-in intake service, through the capture and the service's uplinks: a
 * prediction captured before a tick end lands in the ring under the seq of the FRAME whose VERDICT carries that tick's
 * flags, and a cut comes back as parts on the same socket. Malformed diagnostics replies never close it.
 */
class DiagnosticsStreamTest {
    private static final byte[] BUNDLE = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6};
    private static final String DIAG_ON = "\"protocol\":3,\"diag\":{\"enabled\":true,\"uploadBytesPerSecond\":262144}";

    private final CloudConnections connections = new CloudConnections();

    @AfterEach
    void restore() {
        connections.closeAll();
        CloudCapture.segmentBinder(SegmentBinder.NONE);
    }

    @Test
    void aPredictionLandsUnderItsVerdictsSeq() throws Exception {
        try (Receiver receiver = new Receiver();
                CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.SERVER, false)) {
            start(fixture, receiver);
            assertTrue(receiver.connectBodies.poll().contains("\"diag\":1"));
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            SuperDebug debug = fixture.player.checkManager.getListener(SuperDebug.class);
            OffsetHandler simulation = fixture.player.checkManager.getCheck(OffsetHandler.class);

            reply(
                    socket,
                    Reply.newBuilder()
                            .setDiagWatch(DiagWatch.newBuilder()
                                    .setPlayer(player(fixture))
                                    .setLeaseSeconds(60))
                            .build());
            await(fixture, () -> debug.watch() != null);

            List<Long> captured = new ArrayList<>();
            for (int tick = 0; tick < 12; tick++) {
                var watch = debug.watch();
                long seq = watch.pendingSeq();
                captured.add(seq);
                watch.capture(seq, tick / 1000.0, "tick " + tick, "state " + tick, null, System.nanoTime());
                if (tick == 5) fixture.capture.flagged(simulation, 1.0);
                fixture.tickEnd();
            }
            Map<Long, Frame> frames = new HashMap<>();
            await(fixture, () -> {
                IntakeMessage message;
                while ((message = receiver.messages.poll()) != null) {
                    if (message.hasFrame()) frames.put(message.getFrame().getSeq(), message.getFrame());
                }
                return frames.keySet().containsAll(captured);
            });
            long flagged = -1;
            for (Frame frame : frames.values()) {
                for (Record record : frame.getRecordsList()) {
                    if (!record.hasVerdict()) continue;
                    for (Flag flag : record.getVerdict().getFlagsList()) {
                        if (flag.getCheck().equals("Simulation")) flagged = frame.getSeq();
                    }
                }
            }
            assertEquals(captured.get(5), flagged);

            reply(
                    socket,
                    Reply.newBuilder()
                            .setDiagCut(DiagCut.newBuilder()
                                    .setPlayer(player(fixture))
                                    .setBundle(ByteString.copyFrom(BUNDLE))
                                    .setFromSeq(flagged - 2)
                                    .setToSeq(flagged + 2)
                                    .setFullFromSeq(flagged)
                                    .setFullToSeq(flagged))
                            .build());
            List<DiagPart> parts = new ArrayList<>();
            await(fixture, () -> {
                IntakeMessage message;
                while ((message = receiver.messages.poll()) != null) {
                    if (message.hasDiag()) parts.add(message.getDiag());
                }
                return !parts.isEmpty() && parts.get(parts.size() - 1).getLast();
            });
            DiagPart last = parts.get(parts.size() - 1);
            assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, last.getCoverage());
            List<DiagTick> ticks = new ArrayList<>();
            for (DiagPart part : parts) ticks.addAll(part.getTicksList());
            assertEquals(5, ticks.size());
            for (DiagTick tick : ticks) {
                int index = captured.indexOf(tick.getSeq());
                assertEquals("tick " + index, tick.getTrace());
                assertEquals(tick.getSeq() == flagged, !tick.getFull().isEmpty());
                assertEquals(tick.getSeq() == flagged ? List.of("Simulation") : List.of(), tick.getFlagsList());
            }
            assertTrue(parts.get(0).getHeader().contains("client "));

            socket.close().sync();
            await(fixture, () -> debug.watch() == null);
        }
    }

    @Test
    void malformedDiagnosticsRepliesAreDroppedAndTheSocketStays() throws Exception {
        try (Receiver receiver = new Receiver();
                CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.SERVER, false)) {
            start(fixture, receiver);
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            SuperDebug debug = fixture.player.checkManager.getListener(SuperDebug.class);
            reply(
                    socket,
                    Reply.newBuilder()
                            .setDiagWatch(DiagWatch.newBuilder()
                                    .setPlayer(ByteString.copyFrom(new byte[] {1, 2, 3}))
                                    .setLeaseSeconds(60))
                            .build());
            reply(
                    socket,
                    Reply.newBuilder()
                            .setDiagCut(DiagCut.newBuilder()
                                    .setPlayer(player(fixture))
                                    .setBundle(ByteString.copyFrom(new byte[] {1}))
                                    .setToSeq(5))
                            .build());
            reply(
                    socket,
                    Reply.newBuilder()
                            .setDiagCut(DiagCut.newBuilder()
                                    .setPlayer(ByteString.copyFrom(IntakeWire.uuid(new UUID(1, 1))))
                                    .setBundle(ByteString.copyFrom(BUNDLE))
                                    .setToSeq(5))
                            .build());
            reply(
                    socket,
                    Reply.newBuilder()
                            .setDiagWatch(DiagWatch.newBuilder()
                                    .setPlayer(player(fixture))
                                    .setLeaseSeconds(60))
                            .build());
            await(fixture, () -> debug.watch() != null);
            fixture.tickEnd();
            assertTrue(socket.isActive());
            assertNull(receiver.connects.poll(300, TimeUnit.MILLISECONDS), "no reconnect");
        }
    }

    /** The player logs in on backend "a", whose socket connects with diagnostics on and answers the JOIN. */
    private void start(CaptureFixture fixture, Receiver receiver) throws Exception {
        connections.configure(new CloudSettings(receiver.url(), "test-key", "{server}", "", List.of(3)));
        CloudCapture.uplinks(new CloudService.ConnectionUplinks(connections));
        CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
        fixture.player.gamemode = GameMode.SURVIVAL;
        fixture.phase(ConnectionPhase.CONFIGURATION);
        fixture.install();
        fixture.phase(ConnectionPhase.PLAY);
        fixture.capture.loginSent(
                new ClientboundLogin(7, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL)));
        fixture.clientbound("login", (byte) 1);
        fixture.channel.runPendingTasks();
        receiver.allowConnect(DIAG_ON);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IntakeMessage join;
        do {
            fixture.channel.runPendingTasks();
            join = receiver.messages.poll(10, TimeUnit.MILLISECONDS);
        } while (join == null && System.nanoTime() < deadline);
        assertNotNull(join);
        assertTrue(join.hasJoin());
        int tickEnd = fixture.id(PacketDirection.SERVERBOUND, "client_tick_end");
        await(fixture, () -> stream(fixture).joined() && stream(fixture).wants(IntakeWire.SERVERBOUND, tickEnd));
    }

    private static ByteString player(CaptureFixture fixture) {
        return ByteString.copyFrom(IntakeWire.uuid(fixture.player.getUniqueId()));
    }

    private static void reply(Channel socket, Reply reply) {
        byte[] bytes = Replies.newBuilder().addReplies(reply).build().toByteArray();
        socket.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
    }

    private static CaptureStream stream(CaptureFixture fixture) {
        try {
            var field = CloudCapture.class.getDeclaredField("stream");
            field.setAccessible(true);
            return (CaptureStream) field.get(fixture.capture);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(CaptureFixture fixture, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            fixture.channel.runPendingTasks();
            if (condition.getAsBoolean()) return;
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(5);
        }
    }
}
