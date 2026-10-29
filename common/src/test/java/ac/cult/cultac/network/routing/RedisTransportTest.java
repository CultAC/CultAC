package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jedis wiring against a fake RESP server; a real Redis is not exercised here. */
class RedisTransportTest {
    /** Connects and reads give up after 4 s, and closing drains for 200 ms. */
    private static final RedisTransport.Timings TIMINGS = new RedisTransport.Timings(4_000, 4_000, 10_000, 30_000, 200);

    /** Well under the connect and read timeouts, so a close that waits for either still fails. */
    private static final long CLOSE_SLACK_MILLIS = 2_000;

    private static final List<String> CHANNELS = List.of("cultac.0123abcd.p3", "cultac.0123abcd.all");

    private FakeRedisServer server;
    private RedisTransport subscriber;
    private RedisTransport publisher;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeRedisServer();
        String uri = "redis://127.0.0.1:" + server.port() + "/0";
        subscriber = transport(uri);
        publisher = transport(uri);
    }

    @AfterEach
    void tearDown() throws Exception {
        subscriber.close();
        publisher.close();
        server.close();
    }

    private static RedisTransport transport(String uri) {
        return transport(uri, TIMINGS);
    }

    private static RedisTransport transport(String uri, RedisTransport.Timings timings) {
        return new RedisTransport(uri, Redaction.of(List.of(uri), List.of()), timings);
    }

    /** Fails unless closing {@code transport} returns well before a connect or read in progress would give up. */
    private static void closesPromptly(RedisTransport transport) {
        long started = System.nanoTime();
        transport.close();
        long closing = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(closing < TIMINGS.drainMillis() + CLOSE_SLACK_MILLIS, closing + " ms");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    private void subscribed() throws InterruptedException {
        await(() -> CHANNELS.stream().allMatch(channel -> server.subscribers(channel) == 1));
    }

    @Test
    void aPublishReachesTheSubscriberOnItsChannel() throws Exception {
        subscriber.start(CHANNELS, (channel, text) -> received.add(channel + " " + text));
        subscribed();

        publisher.publish("cultac.0123abcd.p3", "sig.{\"v\":1}");
        publisher.publish("cultac.0123abcd.other", "not subscribed");
        publisher.publish("cultac.0123abcd.all", "é broadcast");

        assertEquals("cultac.0123abcd.p3 sig.{\"v\":1}", received.poll(10, TimeUnit.SECONDS));
        assertEquals("cultac.0123abcd.all é broadcast", received.poll(10, TimeUnit.SECONDS));
        assertNull(received.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void resubscribesAfterTheServerDropsTheConnection() throws Exception {
        subscriber.start(CHANNELS, (channel, text) -> received.add(text));
        subscribed();
        publisher.publish("cultac.0123abcd.p3", "before");
        assertEquals("before", received.poll(10, TimeUnit.SECONDS));

        server.dropClients();
        subscribed();
        publisher.publish("cultac.0123abcd.p3", "after");
        assertEquals("after", received.poll(10, TimeUnit.SECONDS));
    }

    @Test
    void closeLeavesTheChannels() throws Exception {
        subscriber.start(CHANNELS, (channel, text) -> received.add(text));
        subscribed();
        subscriber.close();
        await(() -> server.subscribers("cultac.0123abcd.p3") == 0);
        subscriber.publish("cultac.0123abcd.p3", "after close");
        Thread.sleep(200);
        assertTrue(received.isEmpty());
    }

    @Test
    void closingClosesThePublishingConnection() throws Exception {
        publisher.publish("cultac.0123abcd.p3", "one");
        await(() -> server.publishes() == 1 && server.connections() == 1);
        publisher.close();
        await(() -> server.connections() == 0);
    }

    @Test
    void closingCutsOffAPublishTheServerNeverAnswers() throws Exception {
        server.stalled = true;
        publisher.publish("cultac.0123abcd.p3", "unanswered");
        publisher.publish("cultac.0123abcd.p3", "queued behind it");
        await(() -> server.publishes() == 1 && server.connections() == 1);

        closesPromptly(publisher);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMINGS.readMillis() + CLOSE_SLACK_MILLIS);
        while (server.connections() > 0) {
            if (System.nanoTime() > deadline) fail("the publishing connection outlasted close");
            Thread.sleep(20);
        }
        Thread.sleep(200);
        assertEquals(1, server.publishes());
        assertEquals(0, server.connections());
    }

    @Test
    void closingCutsOffAConnectionStillAuthenticatingAndNothingIsPublishedOnIt() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            silent.setSoTimeout(10_000);
            RedisTransport stalled = transport("redis://:secret@127.0.0.1:" + silent.getLocalPort());
            stalled.publish("cultac.0123abcd.p3", "unsent");
            try (Socket accepted = silent.accept()) {
                accepted.setSoTimeout(10_000);
                InputStream in = accepted.getInputStream();
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                received.write(in.read());

                closesPromptly(stalled);
                try {
                    accepted.getOutputStream().write("+OK\r\n".repeat(4).getBytes(StandardCharsets.UTF_8));
                } catch (IOException closedByTheClient) {
                    // The client is gone; nothing reads the replies.
                }
                accepted.setSoTimeout((int) CLOSE_SLACK_MILLIS);
                try {
                    in.transferTo(received);
                } catch (SocketTimeoutException stillOpen) {
                    fail("the connection still authenticating outlasted close");
                } catch (IOException reset) {
                    // The client reset the connection.
                }
                String sent = received.toString(StandardCharsets.UTF_8);
                assertFalse(sent.contains("PUBLISH"), sent);
            }
        }
    }

    @Test
    void closingAbortsAConnectThatNeverCompletesAndNothingIsPublished() throws Exception {
        try (CapturedLog failures = new CapturedLog(Level.WARNING, "Network transport Redis: cannot publish")) {
            RedisTransport stalled = transport("redis://10.255.255.1:6379");
            try {
                stalled.publish("cultac.0123abcd.p3", "unsent");
                Thread.sleep(300);
                assumeTrue(
                        failures.messages().isEmpty(),
                        "10.255.255.1 refuses connections here instead of ignoring them");

                closesPromptly(stalled);
                Thread.sleep(TIMINGS.connectMillis() + 500);
                assertTrue(failures.messages().isEmpty(), failures.messages().toString());
            } finally {
                stalled.close();
            }
        }
    }

    @Test
    void closingCutsOffATlsHandshakeTheServerNeverAnswers() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            silent.setSoTimeout(10_000);
            RedisTransport stalled = transport("rediss://127.0.0.1:" + silent.getLocalPort());
            stalled.publish("cultac.0123abcd.p3", "unsent");
            try (Socket accepted = silent.accept()) {
                accepted.setSoTimeout(10_000);
                InputStream in = accepted.getInputStream();
                assertEquals(0x16, in.read(), "a TLS handshake record");

                closesPromptly(stalled);
                // A handshake that times out waits one more read timeout for the server's close_notify.
                accepted.setSoTimeout((int) (2 * TIMINGS.readMillis() + CLOSE_SLACK_MILLIS));
                try {
                    in.transferTo(OutputStream.nullOutputStream());
                } catch (SocketTimeoutException stillOpen) {
                    fail("the connection still shaking hands outlasted its read timeout");
                } catch (IOException reset) {
                    // The client reset the connection.
                }
            }
        }
    }

    @Test
    void theWatchdogPingsWhilePublishesBlock() throws Exception {
        RedisTransport watched = transport(
                "redis://127.0.0.1:" + server.port(), new RedisTransport.Timings(4_000, 4_000, 50, 10_000, 200));
        try {
            watched.start(CHANNELS, (channel, text) -> received.add(text));
            subscribed();
            server.stalled = true;
            watched.publish("cultac.0123abcd.p3", "unanswered");
            await(() -> server.publishes() == 1);
            int before = server.pings();
            await(() -> server.pings() >= before + 5);
            assertEquals(1, server.publishes());
        } finally {
            watched.close();
        }
    }

    @Test
    void theUriMustBeRedisWithAHost() {
        assertThrows(IllegalArgumentException.class, () -> RedisTransport.parse("http://cache:6379"));
        assertThrows(IllegalArgumentException.class, () -> RedisTransport.parse("redis:///0"));
        assertThrows(IllegalArgumentException.class, () -> RedisTransport.parse("not a uri"));
        assertEquals(URI.create("redis://:p%40ss@cache:6379/2"), RedisTransport.parse("redis://:p%40ss@cache/2"));
        assertEquals(URI.create("rediss://cache:6380"), RedisTransport.parse("rediss://cache:6380"));
    }
}
