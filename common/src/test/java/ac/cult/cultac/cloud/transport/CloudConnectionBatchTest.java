package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.capture.PlayerText;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * A socket's messages, each open for a while and ended by age, by a record that ends it or by the socket's loss, read
 * on the receiver as one zstd frame per socket.
 */
class CloudConnectionBatchTest {
    private static final UUID PLAYER = new UUID(1, 2);
    private static final long NEVER = Long.MAX_VALUE / 4;

    @Test
    void messagesEndedByAgeReadAsOneStream() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(TimeUnit.MILLISECONDS.toNanos(200)))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            for (long seq = 1; seq <= 3; seq++) {
                assertTrue(connection.send(generation.get(), frame(seq), 0));
                IntakeMessage frame = receiver.messages.poll(5, TimeUnit.SECONDS);
                assertNotNull(frame, "message " + seq + " ends by age");
                assertEquals(seq, frame.getFrame().getSeq());
            }
            synchronized (receiver.wire) {
                assertEquals(3, receiver.wire.size());
            }
        }
    }

    @Test
    void aJoinOnAQuietSocketGoesOutAtTheNextFlush() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            assertTrue(connection.send(generation.get(), frame(1), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "a frame waits in the open message");

            assertTrue(connection.send(generation.get(), join(), 0));
            IntakeMessage frame = next(receiver);
            IntakeMessage join = next(receiver);
            assertEquals(IntakeMessage.KindCase.FRAME, frame.getKindCase());
            assertEquals(IntakeMessage.KindCase.JOIN, join.getKindCase());
            synchronized (receiver.wire) {
                assertEquals(1, receiver.wire.size(), "the frame before it rides the same message");
            }

            assertTrue(connection.send(generation.get(), frame(2), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "a frame waits in the open message");
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            frame = next(receiver);
            IntakeMessage leave = next(receiver);
            assertEquals(IntakeMessage.KindCase.FRAME, frame.getKindCase());
            assertEquals(IntakeMessage.KindCase.LEAVE, leave.getKindCase());
            synchronized (receiver.wire) {
                assertEquals(2, receiver.wire.size(), "the frame before it rides the same message");
            }
        }
    }

    @Test
    void aJoinThatOpensAMessageEndsIt() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            assertTrue(connection.send(generation.get(), join(), 0));
            IntakeMessage join = next(receiver);
            assertEquals(IntakeMessage.KindCase.JOIN, join.getKindCase());

            assertTrue(connection.send(generation.get(), frame(1), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "the next frame opens a new message");
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            assertEquals(1, next(receiver).getFrame().getSeq());
            assertEquals(IntakeMessage.KindCase.LEAVE, next(receiver).getKindCase());
            synchronized (receiver.wire) {
                assertEquals(2, receiver.wire.size());
            }
        }
    }

    @Test
    void aReloadThatChangesTheAgentSendsTheOpenMessageBeforeTheSocketGoes(@TempDir Path folder) throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            long first = generation.get();
            assertTrue(connection.send(first, large(new Random(1), 1), 0));
            assertTrue(connection.send(first, frame(2), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "the frames wait in the open message");

            reload(receiver, folder);
            assertEquals(
                    1, next(receiver).getFrame().getSeq(), "the open message goes out before the socket is dropped");
            assertEquals(2, next(receiver).getFrame().getSeq());
            assertEquals(Integer.valueOf(1000), receiver.closes.poll(5, TimeUnit.SECONDS), "the socket closes normally");

            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > first, "reconnected");
            assertTrue(connection.send(generation.get(), frame(3), 0));
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            assertEquals(3, next(receiver).getFrame().getSeq());
            assertEquals(IntakeMessage.KindCase.LEAVE, next(receiver).getKindCase());
        }
    }

    @Test
    void aReloadWhoseOpenMessageCannotGoOutDropsItWithinTheEndBound(@TempDir Path folder) throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            Channel stuck = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(stuck);
            stuck.config().setAutoRead(false);
            long first = generation.get();
            assertTrue(connection.send(first, large(new Random(2), 1), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "the frame waits in the open message");

            long reloaded = System.nanoTime();
            reload(receiver, folder);
            await(() -> connection.summary().contains("queued 0.0 of"), "the unsent open message is dropped");
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reloaded);
            assertTrue(took < 3000, "dropped at the 1 s end bound, not the 15 s write bound: " + took + " ms");

            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > first, "reconnected");
            assertTrue(connection.send(generation.get(), frame(2), 0));
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            assertEquals(2, next(receiver).getFrame().getSeq(), "nothing of the dropped message is sent");
            assertEquals(IntakeMessage.KindCase.LEAVE, next(receiver).getKindCase());
        }
    }

    @Test
    void aReloadRightAfterAMessageEndedStillClosesNormally(@TempDir Path folder) throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            long first = generation.get();
            assertTrue(connection.send(first, large(new Random(3), 1), 0));
            assertTrue(connection.send(first, IntakeWire.leave(PLAYER), 0));
            assertEquals(1, next(receiver).getFrame().getSeq());
            assertEquals(IntakeMessage.KindCase.LEAVE, next(receiver).getKindCase());

            reload(receiver, folder);
            assertEquals(
                    Integer.valueOf(1000),
                    receiver.closes.poll(5, TimeUnit.SECONDS),
                    "with no message open the socket still closes normally, after the last one is read");
            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > first, "reconnected");
        }
    }

    @Test
    void aLeaveQueuedAFlushBeforeShutdownGoesOut() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            assertTrue(connection.send(generation.get(), frame(1), 0));
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            await(() -> queued(connection) == 0, "the LEAVE's message is written");
            receiver.connections.closeAll();
            IntakeMessage frame = receiver.messages.poll(5, TimeUnit.SECONDS);
            IntakeMessage leave = receiver.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertNotNull(leave, "the LEAVE went out before the socket closed");
            assertEquals(IntakeMessage.KindCase.LEAVE, leave.getKindCase());
            await(connection::closed, "closed");
        }
    }

    @Test
    void aLostSocketDropsItsOpenMessageAndTheNextSocketStartsAFreshFrame() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            Channel first = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            long lost = generation.get();
            Random random = new Random(1);
            for (int i = 0; i < 2; i++) {
                byte[] payload = new byte[300 * 1024];
                random.nextBytes(payload);
                IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
                builder.add(IntakeWire.SERVERBOUND, 0, payload, payload.length, 0, 0, 0);
                assertTrue(connection.send(lost, builder.build(PLAYER, 1 + i), 0));
            }
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "both frames wait in the open message");
            assertFalse(connection.summary().contains("queued 0.0 of"), connection.summary());

            first.close();
            await(() -> connection.summary().contains("queued 0.0 of"), "the lost socket's open message is dropped");
            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > lost, "reconnected");
            assertTrue(connection.send(generation.get(), frame(3), 0));
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            IntakeMessage frame = receiver.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "the new socket's first message reads on a new decoder");
            assertEquals(3, frame.getFrame().getSeq(), "nothing of the lost socket's open message is sent");
            assertEquals(IntakeMessage.KindCase.LEAVE, next(receiver).getKindCase());
        }
    }

    @Test
    void aMessageAgesOnTheInjectedClock() throws Exception {
        AtomicLong clock = new AtomicLong(System.nanoTime());
        long batchNanos = TimeUnit.MINUTES.toNanos(10);
        CloudConnection.Timing timing = new CloudConnection.Timing(
                NEVER, NEVER, clock::get, CloudConnection.Timing.DEFAULT.takeoverNanos(), batchNanos);
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing)) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            assertTrue(connection.send(generation.get(), frame(1), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "the frame waits in the open message");
            clock.addAndGet(batchNanos - 1);
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "not yet open for the batch period");
            clock.addAndGet(1);
            assertEquals(1, next(receiver).getFrame().getSeq(), "the message ends once open for the batch period");
        }
    }

    @Test
    void recordsOfferedWhileTheOpenMessageEndsAreRefused(@TempDir Path folder) throws Exception {
        AtomicLong clock = new AtomicLong(System.nanoTime());
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(handTiming(clock))) {
            AtomicLong generation = new AtomicLong(-1);
            AtomicLong disconnected = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation, disconnected);
            Channel stuck = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(stuck);
            stuck.config().setAutoRead(false);
            long first = generation.get();
            assertTrue(connection.send(first, large(new Random(4), 1), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "the frame waits in the open message");

            reload(receiver, folder);
            // The clock stands still, so the socket stays until it moves unless finishing the message outlasts the
            // end bound in real time.
            await(() -> !connection.send(first, frame(2), 0), "a record offered while the message ends is refused");
            assertEquals(-1, disconnected.get(), "refused while the socket is still open");
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(endNanos()) + 1000);
            assertEquals(-1, disconnected.get(), "the end waits on the clock, not on real time");
            assertFalse(connection.send(first, frame(3), 0), "still refused");

            clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
            await(() -> disconnected.get() == first, "the socket is dropped once the end bound passes");
            await(() -> queued(connection) == 0, "nothing of the ended socket stays counted");
            synchronized (globals.logs) {
                List<String> warnings = globals.logs.stream()
                        .filter(log -> log.getLevel() == Level.WARNING
                                && log.getMessage().contains("agent changed on reload"))
                        .map(LogRecord::getMessage)
                        .toList();
                assertEquals(1, warnings.size(), warnings.toString());
                assertTrue(
                        warnings.get(0).contains("was not sent")
                                || warnings.get(0).contains("did not answer"),
                        warnings.get(0));
            }

            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > first, "reconnected");
            assertTrue(connection.send(generation.get(), frame(4), 0));
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            assertEquals(4, next(receiver).getFrame().getSeq(), "nothing offered to the ended socket is sent");
            assertEquals(IntakeMessage.KindCase.LEAVE, next(receiver).getKindCase());
        }
    }

    @Test
    void aMissingCodecEndsTheOpenMessageBeforeTheSocketGoes() throws Exception {
        Set<Integer> unstripped = unstripped();
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            long first = generation.get();
            assertTrue(connection.send(first, frame(1), 0));
            assertTrue(connection.send(first, frame(2), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "the frames wait in the open message");

            unstripped.add(777);
            assertEquals(
                    1, next(receiver).getFrame().getSeq(), "the open message goes out before the socket is dropped");
            assertEquals(2, next(receiver).getFrame().getSeq());
            assertEquals(
                    Integer.valueOf(1000), receiver.closes.poll(5, TimeUnit.SECONDS), "the socket closes normally");
            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > first, "reconnected");
            await(() -> queued(connection) == 0, "nothing of the ended socket stays counted");
            synchronized (globals.logs) {
                assertEquals(
                        1,
                        globals.logs.stream()
                                .filter(log -> log.getLevel() == Level.WARNING
                                        && log.getMessage().contains("reconnecting with textStripped false"))
                                .count());
            }
        } finally {
            unstripped.remove(777);
        }
    }

    @Test
    void closeAllDropsTheOpenMessageAndItsCount() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(NEVER))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            assertTrue(connection.send(generation.get(), large(new Random(5), 1), 0));
            assertTrue(connection.send(generation.get(), frame(2), 0));
            assertNull(receiver.messages.poll(300, TimeUnit.MILLISECONDS), "the frames wait in the open message");
            assertTrue(queued(connection) > 0, "the open message is counted");

            receiver.connections.closeAll();
            await(connection::closed, "closed");
            await(() -> queued(connection) == 0, "the open message's count is let go");
            assertFalse(connection.send(generation.get(), frame(3), 0), "a closed connection refuses records");
            assertNull(receiver.messages.poll(500, TimeUnit.MILLISECONDS), "the open message is lost with the socket");
            assertNull(receiver.closes.poll(100, TimeUnit.MILLISECONDS), "the socket is aborted, not closed normally");
        }
    }

    /** The bytes {@code connection} counts as queued, records and the compressed bytes of its open message. */
    private static long queued(CloudConnection connection) {
        try {
            Field field = CloudConnection.class.getDeclaredField("queuedBytes");
            field.setAccessible(true);
            return ((QueuedBytes) field.get(connection)).get();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static long endNanos() throws ReflectiveOperationException {
        Field field = CloudConnection.class.getDeclaredField("END_NANOS");
        field.setAccessible(true);
        return field.getLong(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> unstripped() throws ReflectiveOperationException {
        Field field = PlayerText.class.getDeclaredField("UNSTRIPPED");
        field.setAccessible(true);
        return (Set<Integer>) field.get(null);
    }

    private static CloudConnection.Timing timing(long batchNanos) {
        return new CloudConnection.Timing(
                CloudConnection.Timing.DEFAULT.quietNanos(),
                CloudConnection.Timing.DEFAULT.idleNanos(),
                System::nanoTime,
                CloudConnection.Timing.DEFAULT.takeoverNanos(),
                batchNanos);
    }

    /** Timing on {@code clock} in which no quiet or idle period passes and no message ends by age. */
    private static CloudConnection.Timing handTiming(AtomicLong clock) {
        return new CloudConnection.Timing(
                NEVER, NEVER, clock::get, CloudConnection.Timing.DEFAULT.takeoverNanos(), NEVER);
    }

    private static CloudConnection connect(Receiver receiver, AtomicLong generation) throws Exception {
        return connect(receiver, generation, new AtomicLong(-1));
    }

    private static CloudConnection connect(Receiver receiver, AtomicLong generation, AtomicLong disconnected)
            throws Exception {
        receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
        CloudConnection connection = receiver.connections.forServer("proxy.a");
        assertNotNull(connection);
        connection.register(PLAYER, new CloudConnection.Stream() {
            public void connected(long connected) {
                generation.set(connected);
            }

            public void disconnected(long ended) {
                disconnected.set(ended);
            }

            public void reply(long replied, IntakeWire.Reply reply) {}
        });
        receiver.allowConnect("\"protocol\":5");
        await(() -> generation.get() > 0, "connected");
        return connection;
    }

    /** Reloads the plugin config with a setting that changes the agent's config hash. */
    private static void reload(Receiver receiver, Path folder) throws Exception {
        Files.writeString(folder.resolve("cult.yml"), "Reach:\n  threshold: 0.0006\n");
        Mockito.when(CultAPI.INSTANCE.getGrimPlugin().getDataFolder()).thenReturn(folder.toFile());
        receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
    }

    private static IntakeMessage next(Receiver receiver) throws InterruptedException {
        IntakeMessage message = receiver.messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "no message within 5 s");
        return message;
    }

    /** A frame of 900 KiB that does not compress: one message, under the record limit, that fills socket buffers. */
    private static byte[] large(Random random, long seq) {
        byte[] payload = new byte[900 * 1024];
        random.nextBytes(payload);
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        builder.add(IntakeWire.SERVERBOUND, 0, payload, payload.length, 0, 0, 0);
        return builder.build(PLAYER, seq);
    }

    private static byte[] frame(long seq) {
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        builder.add(IntakeWire.SERVERBOUND, 0, new byte[] {1, 2, 3}, 3, 0, 0, 0);
        return builder.build(PLAYER, seq);
    }

    private static byte[] join() {
        return IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0);
    }
}
