package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A compressed message's array goes back to the frame arrays only once the socket has completed its send normally,
 * once; a send that fails, or is pending when the connection closes, lets its array go.
 */
class CloudConnectionMessageArrayTest {
    private static final UUID PLAYER = new UUID(3, 4);
    private static final long NEVER = Long.MAX_VALUE / 4;

    @Test
    void aSentMessageGivesItsArrayBackOnceAfterTheSendCompletes() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing())) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            HeldSocket held = hold(connection);
            FrameBuffers frames = frames(connection);

            assertTrue(connection.send(generation.get(), frame(1), 0));
            Send send = held.next();
            awaitWriting(send);
            assertEquals(0, kept(frames, send.array), "the array is not given back while the send is in flight");

            send.sending.complete(held);
            await(() -> kept(frames, send.array) == 1, "the sent message's array is given back");

            receiver.connections.closeAll();
            joinSender(connection);
            assertEquals(1, kept(frames, send.array), "the array is given back once");
        }
    }

    @Test
    void aFailedSendLetsItsArrayGo() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing())) {
            AtomicLong generation = new AtomicLong(-1);
            AtomicLong disconnected = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation, disconnected);
            HeldSocket held = hold(connection);
            FrameBuffers frames = frames(connection);

            assertTrue(connection.send(generation.get(), frame(1), 0));
            Send send = held.next();
            awaitWriting(send);
            assertEquals(0, kept(frames, send.array), "the array is not given back while the send is in flight");

            send.sending.completeExceptionally(new IOException("closed mid-send"));
            await(() -> disconnected.get() == generation.get(), "the socket is lost");
            receiver.connections.closeAll();
            joinSender(connection);
            assertEquals(0, kept(frames, send.array), "a failed send's array is never given back");
        }
    }

    @Test
    void aSendPendingAtCloseLetsItsArrayGo() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing())) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            HeldSocket held = hold(connection);
            FrameBuffers frames = frames(connection);

            assertTrue(connection.send(generation.get(), frame(1), 0));
            Send send = held.next();
            awaitWriting(send);
            assertEquals(0, kept(frames, send.array), "the array is not given back while the send is in flight");

            receiver.connections.closeAll();
            joinSender(connection);
            assertEquals(0, kept(frames, send.array), "a send pending at close lets its array go");

            send.sending.complete(held);
            assertEquals(0, kept(frames, send.array), "a send completed after close lets its array go");
        }
    }

    /** One message handed to the socket: its array and the send the test completes. */
    private record Send(byte[] array, CompletableFuture<WebSocket> sending) {}

    /**
     * The connection's socket with each binary send held until the test completes it; everything else goes to the
     * socket it stands in for.
     */
    private static final class HeldSocket implements WebSocket {
        private final WebSocket socket;
        private final BlockingQueue<Send> sends = new LinkedBlockingQueue<>();

        HeldSocket(WebSocket socket) {
            this.socket = socket;
        }

        Send next() throws InterruptedException {
            Send send = sends.poll(5, TimeUnit.SECONDS);
            assertNotNull(send, "a message is sent");
            return send;
        }

        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            Send send = new Send(data.array(), new CompletableFuture<>());
            sends.add(send);
            return send.sending;
        }

        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return socket.sendText(data, last);
        }

        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return socket.sendPing(message);
        }

        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return socket.sendPong(message);
        }

        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return socket.sendClose(statusCode, reason);
        }

        public void request(long n) {
            socket.request(n);
        }

        public String getSubprotocol() {
            return socket.getSubprotocol();
        }

        public boolean isOutputClosed() {
            return socket.isOutputClosed();
        }

        public boolean isInputClosed() {
            return socket.isInputClosed();
        }

        public void abort() {
            socket.abort();
        }
    }

    /** Waits until the sender waits on {@code send}'s future: past everything it does before the send completes. */
    private static void awaitWriting(Send send) throws InterruptedException {
        await(() -> send.sending.getNumberOfDependents() > 0, "the sender waits on the send");
    }

    /** Stands a {@link HeldSocket} in for the connection's socket once nothing is queued or in flight. */
    private static HeldSocket hold(CloudConnection connection) throws Exception {
        await(() -> queued(connection) == 0, "nothing queued before the socket is held");
        Field field = CloudConnection.class.getDeclaredField("socket");
        field.setAccessible(true);
        HeldSocket held = new HeldSocket((WebSocket) field.get(connection));
        field.set(connection, held);
        return held;
    }

    private static FrameBuffers frames(CloudConnection connection) throws ReflectiveOperationException {
        Field field = CloudConnection.class.getDeclaredField("shared");
        field.setAccessible(true);
        return ((SharedTransport) field.get(connection)).frames;
    }

    /** How many times {@code array} is kept for reuse on {@code frames}. */
    private static int kept(FrameBuffers frames, byte[] array) {
        try {
            Field shelvesField = FrameBuffers.class.getDeclaredField("shelves");
            shelvesField.setAccessible(true);
            int found = 0;
            for (Object shelf : (Object[]) shelvesField.get(frames)) {
                Field arraysField = shelf.getClass().getDeclaredField("arrays");
                Field countField = shelf.getClass().getDeclaredField("count");
                arraysField.setAccessible(true);
                countField.setAccessible(true);
                synchronized (shelf) {
                    byte[][] arrays = (byte[][]) arraysField.get(shelf);
                    int count = countField.getInt(shelf);
                    for (int i = 0; i < count; i++) if (arrays[i] == array) found++;
                }
            }
            return found;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void joinSender(CloudConnection connection) throws Exception {
        Field field = CloudConnection.class.getDeclaredField("sender");
        field.setAccessible(true);
        Thread sender = (Thread) field.get(connection);
        assertTrue(sender.join(Duration.ofSeconds(5)), "the sender stops");
    }

    private static long queued(CloudConnection connection) {
        try {
            Field field = CloudConnection.class.getDeclaredField("queuedBytes");
            field.setAccessible(true);
            return ((QueuedBytes) field.get(connection)).get();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Timing in which no quiet or idle period passes and every message ends at once. */
    private static CloudConnection.Timing timing() {
        return new CloudConnection.Timing(
                NEVER, NEVER, System::nanoTime, CloudConnection.Timing.DEFAULT.takeoverNanos(), 0);
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

    private static byte[] frame(long seq) {
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        builder.add(IntakeWire.SERVERBOUND, 0, new byte[] {1, 2, 3}, 3, 0, 0, 0);
        return builder.build(PLAYER, seq);
    }
}
