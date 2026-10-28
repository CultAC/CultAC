package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jedis wiring against a fake RESP server; a real Redis is not exercised here. */
class RedisTransportTest {
    private static final List<String> CHANNELS = List.of("cultac.default.p3", "cultac.default.all");

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
        subscriber = RedisTransport.create(uri);
        publisher = RedisTransport.create(uri);
    }

    @AfterEach
    void tearDown() throws Exception {
        subscriber.close();
        publisher.close();
        server.close();
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

        publisher.publish("cultac.default.p3", "sig.{\"v\":1}");
        publisher.publish("cultac.default.other", "not subscribed");
        publisher.publish("cultac.default.all", "é broadcast");

        assertEquals("cultac.default.p3 sig.{\"v\":1}", received.poll(10, TimeUnit.SECONDS));
        assertEquals("cultac.default.all é broadcast", received.poll(10, TimeUnit.SECONDS));
        assertNull(received.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void resubscribesAfterTheServerDropsTheConnection() throws Exception {
        subscriber.start(CHANNELS, (channel, text) -> received.add(text));
        subscribed();
        publisher.publish("cultac.default.p3", "before");
        assertEquals("before", received.poll(10, TimeUnit.SECONDS));

        server.dropClients();
        subscribed();
        publisher.publish("cultac.default.p3", "after");
        assertEquals("after", received.poll(10, TimeUnit.SECONDS));
    }

    @Test
    void closeLeavesTheChannels() throws Exception {
        subscriber.start(CHANNELS, (channel, text) -> received.add(text));
        subscribed();
        subscriber.close();
        await(() -> server.subscribers("cultac.default.p3") == 0);
        subscriber.publish("cultac.default.p3", "after close");
        Thread.sleep(200);
        assertTrue(received.isEmpty());
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
