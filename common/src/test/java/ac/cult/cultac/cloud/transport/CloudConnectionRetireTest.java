package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import io.netty.channel.Channel;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** A runtime generation that a newer one replaced lets go of its sockets without competing for their server ids. */
class CloudConnectionRetireTest {
    private static final UUID PLAYER = new UUID(1, 2);

    @Test
    void aRetiredSocketDrainsClosesAndNeverReconnects() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            AtomicLong generation = new AtomicLong(-1);
            connection.register(PLAYER, new CloudConnection.Stream() {
                public void connected(long connected) {
                    generation.set(connected);
                }

                public void disconnected(long ended) {}

                public void reply(long replied, IntakeWire.Reply reply) {}
            });
            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > 0, "connected");

            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            receiver.connections.retireAll();
            IntakeMessage leave = receiver.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(leave, "what was queued goes out");
            assertEquals(IntakeMessage.KindCase.LEAVE, leave.getKindCase());
            assertEquals(WebSocket.NORMAL_CLOSURE, receiver.closes.poll(5, TimeUnit.SECONDS));
            await(connection::closed, "closed");

            assertNull(receiver.connects.poll(500, TimeUnit.MILLISECONDS), "never reconnects");
            assertNull(receiver.connections.forServer("proxy.a"), "the upload stays off");
            assertFalse(receiver.connections.available());
        }
    }

    @Test
    void aRetiredSocketSendsTheMessageItHoldsOpen() throws Exception {
        CloudConnection.Timing timing = new CloudConnection.Timing(
                CloudConnection.Timing.DEFAULT.quietNanos(),
                CloudConnection.Timing.DEFAULT.idleNanos(),
                System::nanoTime,
                CloudConnection.Timing.DEFAULT.takeoverNanos(),
                Long.MAX_VALUE / 4);
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing)) {
            receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            AtomicLong generation = new AtomicLong(-1);
            connection.register(PLAYER, new CloudConnection.Stream() {
                public void connected(long connected) {
                    generation.set(connected);
                }

                public void disconnected(long ended) {}

                public void reply(long replied, IntakeWire.Reply reply) {}
            });
            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > 0, "connected");

            IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
            builder.add(IntakeWire.SERVERBOUND, 0, new byte[] {1, 2, 3}, 3, 0, 0, 0);
            assertTrue(connection.send(generation.get(), builder.build(PLAYER, 1), 0));
            assertNull(receiver.messages.poll(500, TimeUnit.MILLISECONDS), "the message stays open");
            receiver.connections.retireAll();
            IntakeMessage frame = receiver.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "the open message goes out");
            assertEquals(IntakeMessage.KindCase.FRAME, frame.getKindCase());
            assertEquals(WebSocket.NORMAL_CLOSURE, receiver.closes.poll(5, TimeUnit.SECONDS));
            await(connection::closed, "closed");
        }
    }

    @Test
    void retiringTheOpenSocketsKeepsTheUploadOnForOthers() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            AtomicLong generation = new AtomicLong(-1);
            connection.register(PLAYER, new CloudConnection.Stream() {
                public void connected(long connected) {
                    generation.set(connected);
                }

                public void disconnected(long ended) {}

                public void reply(long replied, IntakeWire.Reply reply) {}
            });
            receiver.allowConnect("\"protocol\":5");
            await(() -> generation.get() > 0, "connected");

            receiver.connections.retireOpen();
            assertEquals(WebSocket.NORMAL_CLOSURE, receiver.closes.poll(5, TimeUnit.SECONDS));
            await(connection::closed, "closed");
            assertTrue(receiver.connections.available());

            CloudConnection draining = receiver.connections.forServer("proxy.a-drain-1a2b3c4d");
            assertNotNull(draining);
            assertNotSame(connection, draining);
            receiver.allowConnect("\"protocol\":5");
            assertTrue(receiver.connectBodies.stream().anyMatch(body -> body.contains("proxy.a-drain-1a2b3c4d")));
            assertFalse(
                    receiver.connectBodies.stream().skip(1).anyMatch(body -> body.contains("\"proxy.a\"")),
                    "the retired socket never reconnects");
        }
    }

    @Test
    void aRetiredSocketWaitingToReconnectStops() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            assertNotNull(receiver.connects.poll(5, TimeUnit.SECONDS), "connect attempted");
            receiver.connections.retireAll();
            await(connection::closed, "closed");
            assertNull(receiver.connects.poll(500, TimeUnit.MILLISECONDS), "never reconnects");
        }
    }

    @Test
    void aFrozenConnectionHandsOutNoSocket() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            await(() -> !receiver.connects.isEmpty(), "connect in flight");
            receiver.connections.freeze();
            assertFalse(receiver.connections.available());
            assertNull(receiver.connections.forServer("proxy.b"), "no socket is handed out");
            receiver.allowConnect("\"protocol\":5");
            assertNull(receiver.handshakes.poll(500, TimeUnit.MILLISECONDS), "the answered connect opens no socket");
            await(connection::closed, "closed");
            assertNull(receiver.connects.poll(500, TimeUnit.MILLISECONDS), "never reconnects");
        }
    }

    @Test
    void aRetiredSocketThatCannotDrainClosesWithinItsDeadline() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            AtomicLong generation = new AtomicLong(-1);
            connection.register(PLAYER, new CloudConnection.Stream() {
                public void connected(long connected) {
                    generation.set(connected);
                }

                public void disconnected(long ended) {}

                public void reply(long replied, IntakeWire.Reply reply) {}
            });
            receiver.allowConnect("\"protocol\":5");
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            await(() -> generation.get() > 0, "connected");
            socket.config().setAutoRead(false);
            Random random = new Random(1);
            for (int i = 0; i < 48; i++) {
                byte[] record = new byte[1024 * 1024];
                random.nextBytes(record);
                if (!connection.send(generation.get(), record, 0)) break;
            }
            long started = System.nanoTime();
            receiver.connections.retireAll();
            while (!connection.closed() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20)) {
                Thread.sleep(10);
            }
            long took = System.nanoTime() - started;
            assertTrue(took < TimeUnit.SECONDS.toNanos(8), TimeUnit.NANOSECONDS.toMillis(took) + " ms");
        }
    }
}
