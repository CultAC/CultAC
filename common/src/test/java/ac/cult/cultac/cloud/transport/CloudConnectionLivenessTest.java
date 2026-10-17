package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CloudConnectionLivenessTest {
    private static final long QUIET_MILLIS = 200;
    private static final long WAIT_MILLIS = 3_000;

    private static final long QUIET = TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS);
    /** Where the hand-stepped clock starts; a socket heard at it is open. */
    private static final long START = 1_000;

    private static final CloudConnection.Timing REAL_CLOCK =
            new CloudConnection.Timing(QUIET, CloudConnection.Timing.DEFAULT.idleNanos(), System::nanoTime);

    /** Liveness time for the tests that step it by hand; the others read the real clock. */
    private final AtomicLong clock = new AtomicLong();
    /** How often the connection has read the hand-stepped clock. */
    private final AtomicLong reads = new AtomicLong();

    private final CloudConnection.Timing handClock =
            new CloudConnection.Timing(QUIET, CloudConnection.Timing.DEFAULT.idleNanos(), () -> {
                reads.incrementAndGet();
                return clock.get();
            });

    @Test
    void answeredPingsKeepAQuietSocket() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(handClock)) {
            Channel socket = openAtStart(globals, receiver);
            CloudConnection connection = receiver.connections.forServer("login-test");
            // Each step leaves the socket quiet past the period: it is pinged, answers, and is heard at that time.
            for (int step = 1; step <= 3; step++) {
                long now = START + step * (QUIET + 1);
                int pinged = step;
                clock.set(now);
                await(() -> receiver.pings.get() >= pinged, "ping " + pinged);
                await(() -> connection.lastHeardNanos() == now, "pong " + pinged + " heard");
            }
            assertKept(receiver, socket);
        }
    }

    @Test
    void aPathThatNeverPongsIsDroppedAfterThreeQuietPeriods() throws Exception {
        long quiet = TimeUnit.SECONDS.toNanos(30);
        CloudConnection.Timing timing = new CloudConnection.Timing(quiet, Long.MAX_VALUE / 4, clock::get);
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing)) {
            receiver.answeredPings.set(0);
            Channel socket = openAtStart(globals, receiver);
            clock.set(START + quiet + 1);
            await(() -> receiver.pings.get() >= 1, "first ping");
            clock.set(START + 2 * quiet + 2);
            await(() -> receiver.pings.get() >= 2, "second ping");
            assertTrue(socket.isActive(), "kept through two quiet periods");
            clock.set(START + 3 * quiet + 3);
            assertTrue(socket.closeFuture().await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "dropped after the third");
        }
    }

    @Test
    void answeredThenSilentReconnects() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(REAL_CLOCK)) {
            reconnectAfterSilence(globals, receiver);
        }
    }

    @Test
    void binaryRepliesCountAsHeard() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(handClock)) {
            Channel socket = openAtStart(globals, receiver);
            CloudConnection connection = receiver.connections.forServer("login-test");
            // Half a period apart, each reply is heard before time moves on: four of them span two periods.
            for (int step = 1; step <= 4; step++) {
                long now = START + step * QUIET / 2;
                clock.set(now);
                socket.writeAndFlush(new BinaryWebSocketFrame(Unpooled.EMPTY_BUFFER));
                await(() -> connection.lastHeardNanos() == now, "reply " + now + " heard");
            }
            // Time stands still while the sender loop reads the clock many times, so it has every chance to ping.
            long read = reads.get();
            await(() -> reads.get() >= read + 20, "sender turns");
            assertEquals(0, receiver.pings.get());
            assertKept(receiver, socket);
        }
    }

    @Test
    void aNewSocketThatNeverAnswersIsPingedTwiceBeforeItIsDropped() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(REAL_CLOCK)) {
            Channel socket = reconnectAfterSilence(globals, receiver);
            int pings = receiver.pings.get();
            assertTrue(socket.closeFuture().await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "silent new socket dropped");
            assertTrue(receiver.pings.get() >= pings + 2, "pings on the new socket");
        }
    }

    /** The server answers one ping and swallows the rest: the socket is dropped and a second one opens. */
    private static Channel reconnectAfterSilence(Globals globals, Receiver receiver) throws Exception {
        receiver.answeredPings.set(1);
        Channel first = connect(globals, receiver);
        assertTrue(first.closeFuture().await(WAIT_MILLIS, TimeUnit.MILLISECONDS), "silent socket dropped");
        assertTrue(receiver.pings.get() >= 2);
        synchronized (globals.logs) {
            assertTrue(
                    globals.logs.stream().anyMatch(record -> record.getMessage().contains("no reply to a ping")));
        }
        receiver.allowConnect();
        Channel second = receiver.handshakes.poll(5, TimeUnit.SECONDS);
        assertNotNull(second, "second socket");
        return second;
    }

    private static Channel connect(Globals globals, Receiver receiver) throws Exception {
        open(globals, receiver);
        Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
        assertNotNull(socket, "socket");
        return socket;
    }

    /** With the clock at {@link #START}: the socket, once the connection has opened it and heard it at that time. */
    private Channel openAtStart(Globals globals, Receiver receiver) throws Exception {
        clock.set(START);
        CloudConnection connection = open(globals, receiver);
        Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
        assertNotNull(socket, "socket");
        await(() -> connection.lastHeardNanos() == START, "socket opened");
        return socket;
    }

    private static CloudConnection open(Globals globals, Receiver receiver) throws InterruptedException {
        receiver.connections.configure(
                ConnectionFixture.settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
        CloudConnection connection = receiver.connections.forServer("login-test");
        receiver.allowConnect();
        return connection;
    }

    private static void assertKept(Receiver receiver, Channel socket) {
        assertTrue(socket.isActive(), "socket still open");
        assertTrue(receiver.connects.isEmpty(), "no reconnect");
        assertTrue(receiver.handshakes.isEmpty(), "no second socket");
    }
}
