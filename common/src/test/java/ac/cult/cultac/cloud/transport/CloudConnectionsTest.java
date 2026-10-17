package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.intake.v3.Event;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Reply;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CloudConnectionsTest {
    private static final CloudConnection.Stream IDLE_STREAM = new CloudConnection.Stream() {
        public void connected(long generation) {}

        public void disconnected(long generation) {}

        public void reply(long generation, IntakeWire.Reply reply) {}
    };

    @Test
    void twoServerIdsOpenTwoSockets() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection a = connections.forServer("proxy.a");
            CloudConnection b = connections.forServer("proxy.b");
            assertNotSame(a, b);
            assertSame(a, connections.forServer("proxy.a"));
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                String body = receiver.connectBodies.poll(5, TimeUnit.SECONDS);
                assertNotNull(body);
                ids.add(JsonParser.parseString(body)
                        .getAsJsonObject()
                        .get("serverId")
                        .getAsString());
                receiver.allowConnect();
            }
            assertEquals(Set.of("proxy.a", "proxy.b"), ids);
            assertNotNull(receiver.handshakes.poll(5, TimeUnit.SECONDS));
            assertNotNull(receiver.handshakes.poll(5, TimeUnit.SECONDS));
            assertEquals(2, connections.summaries().size());
        }
    }

    @Test
    void anIdleSocketClosesAndFreesItsZstdContext() throws Exception {
        AtomicLong clock = new AtomicLong();
        long idle = TimeUnit.SECONDS.toNanos(60);
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(new CloudConnection.Timing(idle, idle, clock::get))) {
            receiver.unread = true;
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.PROXY_PROTOCOLS));
            CloudConnection connection = connections.forServer("proxy.a");
            UUID player = new UUID(1, 2);
            connection.register(player, IDLE_STREAM);
            receiver.allowConnect("\"protocol\":5");
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            await(connection::streaming, "protocol 5's zstd context");
            clock.addAndGet(2 * idle);
            assertFalse(connection.idle(), "a socket with a stream stays open");

            connection.unregister(player, IDLE_STREAM);
            connections.release("proxy.a");
            assertFalse(connection.idle(), "released just now");
            clock.addAndGet(idle);
            await(connection::closed, "idle socket closed");
            await(() -> !connection.streaming(), "zstd context freed");
            assertTrue(socket.closeFuture().await(5, TimeUnit.SECONDS));
            assertEquals(0, connections.size());

            CloudConnection reopened = connections.forServer("proxy.a");
            assertNotSame(connection, reopened);
            assertNotNull(receiver.connects.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aReconfigureReplacesEverySocket() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection a = connections.forServer("proxy.a");
            CloudConnection b = connections.forServer("proxy.b");
            connections.configure(settings(receiver.url(), "other-key", CloudSettings.SERVER_PROTOCOLS));
            assertTrue(a.closed());
            assertTrue(b.closed());
            CloudConnection a2 = connections.forServer("proxy.a");
            assertNotSame(a, a2);
            assertFalse(a2.closed());
            assertEquals(1, connections.size());
        }
    }

    @Test
    void twentySocketsShareOneSelectorAndOneCompressionPool() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            long before = transportThreads();
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            receiver.unread = true;
            for (int i = 0; i < 20; i++) {
                CloudConnection connection = connections.forServer("proxy.s" + i);
                connection.register(new UUID(0, i), new CloudConnection.Stream() {
                    public void connected(long generation) {
                        for (int record = 0; record < 8; record++) connection.send(generation, new byte[4096], 0);
                    }

                    public void disconnected(long generation) {}

                    public void reply(long generation, IntakeWire.Reply reply) {}
                });
            }
            for (int i = 0; i < 20; i++) receiver.allowConnect();
            for (int i = 0; i < 20; i++) assertNotNull(receiver.handshakes.poll(5, TimeUnit.SECONDS), "socket " + i);
            await(() -> wireMessages(receiver) >= 20, "a message on every socket");
            assertEquals(20, connections.size());
            long added = transportThreads() - before;
            assertTrue(added <= 5, added + " platform threads for 20 sockets");
        }
    }

    /** Platform threads the transport may start: senders, compression and the HTTP client's. */
    private static long transportThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("cult-cloud") || name.startsWith("HttpClient"))
                .count();
    }

    private static int wireMessages(Receiver receiver) {
        synchronized (receiver.wire) {
            return receiver.wire.size();
        }
    }

    @Test
    void findingsGoToTheHook() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            List<IntakeWire.Event> findings = new CopyOnWriteArrayList<>();
            connections.onFinding(findings::add);
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = connections.forServer("proxy.a");
            assertNotNull(connection);
            CloudConnectionLoginTest.Recorder recorder = new CloudConnectionLoginTest.Recorder();
            connection.register(new UUID(3, 4), recorder);
            receiver.allowConnect();
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            recorder.next(recorder.connected);
            UUID player = new UUID(1, 2);
            byte[] event = Replies.newBuilder()
                    .addReplies(Reply.newBuilder()
                            .setEvent(Event.newBuilder()
                                    .setPlayer(ByteString.copyFrom(IntakeWire.uuid(player)))
                                    .setTick(42)
                                    .setCheckId("aim.snap")
                                    .setCategory("AIM")
                                    .setConfidence(0.5)
                                    .setDetail("d")))
                    .build()
                    .toByteArray();
            socket.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(event)));
            await(() -> !findings.isEmpty(), "finding handed to the hook");
            assertEquals(new IntakeWire.Event(player, 42, "aim.snap", "AIM", 0.5, "d"), findings.get(0));
        }
    }
}
