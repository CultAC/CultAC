package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.CHUNK;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.DATA;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.LOGIN;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.SPAWN;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.TICK_END;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.compressThreads;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.capture.CaptureStream;
import ac.cult.cultac.cloud.capture.PlayerText;
import ac.cult.cultac.cloud.intake.v3.Capture;
import ac.cult.cultac.cloud.intake.v3.Event;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Reply;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.codec.entity.AddEntityCodec;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.lang.reflect.Field;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CloudConnectionLoginTest {
    private static final UUID PLAYER = new UUID(3, 4);
    private static final CaptureStream.Session SESSION = new CaptureStream.Session(7, 0, "minecraft:overworld", true);

    @Test
    void aSocketOpensOnlyOnceAServerIdIsAskedFor() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            assertNull(receiver.connects.poll(300, TimeUnit.MILLISECONDS));
            assertTrue(connections.summaries().isEmpty());
            assertNotNull(connections.forServer("proxy.a"));
            receiver.allowConnect();
            assertNotNull(receiver.handshakes.poll(5, TimeUnit.SECONDS));
            assertTrue(receiver.messages.isEmpty());
            assertEquals(1, connections.summaries().size());
        }
    }

    @Test
    void connectOffersTheConfiguredProtocolsAndSaysTextIsStripped() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            connections.forServer("proxy.a");
            receiver.allowConnect();
            JsonObject connect = JsonParser.parseString(receiver.connectBodies.poll(5, TimeUnit.SECONDS))
                    .getAsJsonObject();
            assertEquals("proxy.a", connect.get("serverId").getAsString());
            assertTrue(connect.get("textStripped").getAsBoolean());
            assertFalse(connect.has("entityFilter"));
            assertTrue(connect.has("diag"));
            assertEquals("[3,5]", connect.getAsJsonArray("protocols").toString());
            JsonObject agent = connect.getAsJsonObject("agent");
            assertEquals("cultac", agent.get("name").getAsString());
            assertEquals("enforcing", agent.get("mode").getAsString());

            connections.configure(settings(receiver.url(), "test-key", CloudSettings.PROXY_PROTOCOLS));
            connections.forServer("proxy.a");
            receiver.allowConnect("\"protocol\":5");
            connect = JsonParser.parseString(receiver.connectBodies.poll(5, TimeUnit.SECONDS))
                    .getAsJsonObject();
            assertEquals("[5]", connect.getAsJsonArray("protocols").toString());
        }
    }

    @Test
    void aMissingCodecReconnectsOnceSayingTextIsNotStripped() throws Exception {
        Set<Integer> unstripped = unstripped();
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = connections.forServer("proxy.a");
            receiver.allowConnect();
            assertTrue(textStripped(receiver));
            await(() -> receiver.handshakes.size() == 1, "first handshake");

            unstripped.add(777);
            receiver.allowConnect();
            assertFalse(textStripped(receiver));
            await(() -> receiver.handshakes.size() == 2, "second handshake");
            await(() -> !connection.textStripped(2), "second socket says text is not stripped");
            assertTrue(connection.textStripped(1));
            assertNull(receiver.connectBodies.poll(1500, TimeUnit.MILLISECONDS));
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

    @SuppressWarnings("unchecked")
    private static Set<Integer> unstripped() throws ReflectiveOperationException {
        Field field = PlayerText.class.getDeclaredField("UNSTRIPPED");
        field.setAccessible(true);
        return (Set<Integer>) field.get(null);
    }

    private static boolean textStripped(Receiver receiver) throws InterruptedException {
        String body = receiver.connectBodies.poll(5, TimeUnit.SECONDS);
        assertNotNull(body, "connect body");
        return JsonParser.parseString(body)
                .getAsJsonObject()
                .get("textStripped")
                .getAsBoolean();
    }

    @Test
    void sameSettingsKeepTheSocketAndChangedOnesReplaceIt() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection original = connections.forServer("proxy.a");
            receiver.allowConnect();
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            connections.configure(settings(receiver.url() + "/", "test-key", CloudSettings.SERVER_PROTOCOLS));
            assertSame(original, connections.forServer("proxy.a"));
            assertFalse(original.closed());
            connections.configure(settings(receiver.url(), "other-key", CloudSettings.SERVER_PROTOCOLS));
            assertTrue(original.closed());
            assertTrue(socket.closeFuture().await(5, TimeUnit.SECONDS));
            CloudConnection replaced = connections.forServer("proxy.a");
            assertNotNull(replaced);
            assertNotSame(original, replaced);
            assertEquals(1, connections.size());
        }
    }

    @Test
    void repliesCarryTheirGeneration() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            Recorder player = new Recorder();
            connection.register(PLAYER, player);
            byte[] reply = Replies.newBuilder()
                    .addReplies(Reply.newBuilder()
                            .setCapture(Capture.newBuilder().setPlayer(ByteString.copyFrom(IntakeWire.uuid(PLAYER)))))
                    .build()
                    .toByteArray();

            receiver.allowConnect();
            Channel first = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            long one = player.next(player.connected);
            first.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(reply)));
            assertEquals(one, player.next(player.replies));

            WebSocket open = socket(connection);
            listener(connection, one - 1).onBinary(open, ByteBuffer.wrap(reply), true);
            listener(connection, one).onBinary(Mockito.mock(WebSocket.class), ByteBuffer.wrap(reply), true);
            assertTrue(player.replies.isEmpty(), "a reply off the open socket or its generation is dropped");
            listener(connection, one).onBinary(open, ByteBuffer.wrap(reply), true);
            assertEquals(one, player.next(player.replies));

            first.close().sync();
            assertEquals(one, player.next(player.disconnected));
            receiver.allowConnect();
            Channel second = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(second);
            long two = player.next(player.connected);
            assertEquals(one + 1, two);
            listener(connection, one).onBinary(socket(connection), ByteBuffer.wrap(reply), true);
            second.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(reply)));
            assertEquals(two, player.next(player.replies));
            assertTrue(player.replies.isEmpty());
        }
    }

    @Test
    void findingsAreCountedAndNeverReachAStream() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            Recorder player = new Recorder();
            connection.register(PLAYER, player);
            byte[] event = Replies.newBuilder()
                    .addReplies(Reply.newBuilder()
                            .setEvent(Event.newBuilder()
                                    .setPlayer(ByteString.copyFrom(IntakeWire.uuid(PLAYER)))
                                    .setCheckId("speed")))
                    .build()
                    .toByteArray();
            receiver.allowConnect();
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            player.next(player.connected);
            socket.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(event)));
            socket.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(event)));
            await(() -> connection.summary().endsWith(", 2 findings received"), "both findings counted");
            assertTrue(player.replies.isEmpty());
        }
    }

    @Test
    void aReplySentAsTheSocketOpensIsRead() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.greeting = Replies.newBuilder()
                    .addReplies(Reply.newBuilder()
                            .setEvent(Event.newBuilder()
                                    .setPlayer(ByteString.copyFrom(IntakeWire.uuid(PLAYER)))
                                    .setCheckId("speed")))
                    .build()
                    .toByteArray();
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            assertNotNull(connection);
            receiver.allowConnect();
            await(() -> connection.summary().endsWith(", 1 findings received"), "the first reply read");
        }
    }

    @Test
    void missingKeyIsInertAndSilent() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            for (String key : List.of("", "  ")) {
                receiver.connections.configure(settings(receiver.url(), key, CloudSettings.SERVER_PROTOCOLS));
                assertNull(receiver.connections.forServer("proxy.a"));
                assertTrue(receiver.connections.summaries().isEmpty());
            }
            assertTrue(receiver.connects.isEmpty());
            assertTrue(globals.logs.isEmpty());
        }
    }

    @Test
    void anOfferTheServiceCannotTakeTurnsTheUploadOffWithOneWarning() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.PROXY_PROTOCOLS));
            CloudConnection a = connections.forServer("proxy.a");
            CloudConnection b = connections.forServer("proxy.b");
            receiver.refuseConnect();
            await(() -> a.closed() && b.closed(), "both sockets closed");
            receiver.connects.clear();
            assertNull(connections.forServer("proxy.c"));
            assertNull(receiver.connects.poll(300, TimeUnit.MILLISECONDS));
            synchronized (globals.logs) {
                assertEquals(
                        1,
                        globals.logs.stream()
                                .filter(record -> record.getMessage().startsWith("Cloud upload off"))
                                .count());
            }
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            assertNotNull(connections.forServer("proxy.c"));
        }
    }

    @Test
    void disablingClosesEverySocketAndStopsTheSharedThreads() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            connections.forServer("proxy.a");
            receiver.allowConnect();
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            connections.closeAll();
            assertTrue(connections.summaries().isEmpty());
            assertNull(connections.forServer("proxy.a"));
            assertTrue(socket.closeFuture().await(5, TimeUnit.SECONDS));
            await(() -> compressThreads() == 0, "compression threads stopped");
        }
    }

    /** The socket {@code connection} has open. */
    private static WebSocket socket(CloudConnection connection) throws Exception {
        Field socket = CloudConnection.class.getDeclaredField("socket");
        socket.setAccessible(true);
        return (WebSocket) socket.get(connection);
    }

    /** The reply listener {@code connection} opens a socket of {@code generation} with. */
    private static WebSocket.Listener listener(CloudConnection connection, long generation) throws Exception {
        var constructor = Class.forName(CloudConnection.class.getName() + "$Replies")
                .getDeclaredConstructor(CloudConnection.class, long.class);
        constructor.setAccessible(true);
        return (WebSocket.Listener) constructor.newInstance(connection, generation);
    }

    @Test
    void firstPlayerCarriesLoginChunkAndZombie() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            PlayerStream player = new PlayerStream(connection);
            synchronized (player) {
                player.stream.prepare(player.sink(-1));
                connection.register(PLAYER, player);
                player.stream.observe(SESSION, -64, 384, 1_000);
                player.stream.tap(IntakeWire.CLIENTBOUND, LOGIN, new byte[] {(byte) LOGIN}, 1_000, -64, 384, SESSION);
                player.stream.tap(IntakeWire.CLIENTBOUND, CHUNK, new byte[] {(byte) CHUNK}, 2_000, -64, 384, SESSION);
                player.stream.tap(IntakeWire.CLIENTBOUND, SPAWN, zombie(), 3_000, -64, 384, SESSION);
                player.stream.tap(IntakeWire.SERVERBOUND, TICK_END, new byte[] {(byte) TICK_END}, 4_000, 0, 0, null);
                player.stream.cut(50_001_000);
                player.stream.verdict(1, 1, 1L << IntakeWire.FLYING, List.of(), null, List.of(), null);
            }
            receiver.allowConnect();
            IntakeMessage join = receiver.take(IntakeMessage.KindCase.JOIN);
            IntakeMessage frame = receiver.take(IntakeMessage.KindCase.FRAME);
            assertFalse(join.getJoin().getResumed());
            assertEquals(0, frame.getFrame().getSeq());
            assertEquals(
                    List.of(3, 6, 2, 2, 1, 5),
                    frame.getFrame().getRecordsList().stream()
                            .map(record -> record.getBodyCase().getNumber())
                            .toList());
            byte[] spawn = frame.getFrame().getRecords(3).getClientbound().toByteArray();
            assertArrayEquals(zombie(), spawn);
            ByteBuf body = Unpooled.wrappedBuffer(spawn);
            try {
                assertEquals(SPAWN, Wire.readVarInt(body));
                var entity = new AddEntityCodec()
                        .read(
                                body,
                                new ProtocolContext(
                                        ClientboundPackets.ADD_ENTITY,
                                        DATA,
                                        ConnectionPhase.PLAY,
                                        "minecraft:add_entity",
                                        256,
                                        0));
                assertEquals("minecraft:zombie", entity.entityType());
                assertEquals(42, entity.entityId());
                assertEquals(new Vec3d(2, 64, 3), entity.position());
            } finally {
                body.release();
            }
            assertEquals(
                    1L << IntakeWire.FLYING,
                    frame.getFrame().getRecords(5).getVerdict().getConditions());
            assertEquals(49_997_000, frame.getFrame().getRecords(5).getAdvanceNanos());
            synchronized (player) {
                player.stream.leave(100_001_000);
            }
            receiver.take(IntakeMessage.KindCase.LEAVE);
            assertEquals(0, player.held.get());
        }
    }

    @Test
    void socketReconnectStartsAFreshSessionForAnOnlinePlayer() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            PlayerStream player = new PlayerStream(connection);
            synchronized (player) {
                player.stream.prepare(player.sink(-1));
                player.stream.tap(IntakeWire.CLIENTBOUND, SPAWN, zombie(), 1_000, -64, 384, SESSION);
                player.stream.cut(2_000);
                connection.register(PLAYER, player);
            }
            receiver.allowConnect();
            receiver.take(IntakeMessage.KindCase.JOIN);
            assertNotNull(player.captures.poll(5, TimeUnit.SECONDS));
            Channel old = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(old);
            old.close().sync();
            assertNotNull(player.disconnects.poll(5, TimeUnit.SECONDS));
            receiver.allowConnect();
            receiver.take(IntakeMessage.KindCase.JOIN);
            assertNotNull(player.captures.poll(5, TimeUnit.SECONDS));
            synchronized (player) {
                player.stream.tap(IntakeWire.CLIENTBOUND, CHUNK, new byte[] {(byte) CHUNK}, 40_000, -64, 384, SESSION);
                player.stream.cut(50_000);
                player.stream.verdict(2, 2, 0, List.of(), null, List.of(), null);
            }
            IntakeMessage frame = receiver.take(IntakeMessage.KindCase.FRAME);
            assertEquals(0, frame.getFrame().getSeq());
            assertEquals(2, frame.getFrame().getRecordsCount());
            assertArrayEquals(
                    new byte[] {(byte) CHUNK},
                    frame.getFrame().getRecords(0).getClientbound().toByteArray());
            assertEquals(0, player.held.get());
        }
    }

    static byte[] zombie() {
        ByteBuf packet = Unpooled.buffer();
        try {
            Wire.writeVarInt(packet, SPAWN);
            Wire.writeVarInt(packet, 42);
            Wire.writeUuid(packet, new UUID(5, 6));
            Wire.writeVarInt(packet, DATA.registry("minecraft:entity_type").id("minecraft:zombie"));
            packet.writeDouble(2).writeDouble(64).writeDouble(3);
            Wire.writeLpVec3(packet, new Vec3d(0, 0, 0));
            packet.writeByte(0).writeByte(0).writeByte(0);
            Wire.writeVarInt(packet, 0);
            return ByteBufUtil.getBytes(packet);
        } finally {
            packet.release();
        }
    }

    private static final class PlayerStream implements CloudConnection.Stream {
        final CaptureStream stream = new CaptureStream(PLAYER);
        final CloudConnection connection;
        final BlockingQueue<Long> disconnects = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> captures = new LinkedBlockingQueue<>();
        final AtomicLong held = new AtomicLong();
        long generation = -1;

        PlayerStream(CloudConnection connection) {
            this.connection = connection;
        }

        CaptureStream.Sink sink(long generation) {
            return new CaptureStream.Sink() {
                public boolean send(byte[] record) {
                    return connection.send(generation, record, 0);
                }

                public boolean hold(long bytes) {
                    held.addAndGet(bytes);
                    return true;
                }

                public void release(long bytes) {
                    held.addAndGet(-bytes);
                }

                public int maxFrameBytes() {
                    return connection.maxFrameBytes();
                }
            };
        }

        public synchronized void connected(long generation) {
            if (this.generation == generation) return;
            this.generation = generation;
            stream.join(
                    sink(generation),
                    IntakeWire.join(PLAYER, 777, "vanilla", -64, 384, null, null, 0),
                    30_000,
                    SESSION);
        }

        public synchronized void disconnected(long generation) {
            stream.reset();
            disconnects.add(generation);
        }

        public synchronized void reply(long generation, IntakeWire.Reply reply) {
            if (generation != this.generation) return;
            if (reply instanceof IntakeWire.Capture capture) {
                stream.capture(capture.serverbound(), capture.clientbound(), capture.dimension());
                captures.add(true);
            }
        }
    }

    /** A player's stream that records each generation it is told of. */
    static final class Recorder implements CloudConnection.Stream {
        final BlockingQueue<Long> connected = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> disconnected = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> replies = new LinkedBlockingQueue<>();

        public void connected(long generation) {
            connected.add(generation);
        }

        public void disconnected(long generation) {
            disconnected.add(generation);
        }

        public void reply(long generation, IntakeWire.Reply reply) {
            replies.add(generation);
        }

        long next(BlockingQueue<Long> queue) throws InterruptedException {
            Long generation = queue.poll(10, TimeUnit.SECONDS);
            assertNotNull(generation);
            return generation;
        }
    }
}
