package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The connect loop against a stand-in connection; a real NATS server is not exercised here. */
class NatsTransportTest {
    private static final List<String> CHANNELS = List.of("cultac.default.p3", "cultac.default.all");
    private static final NetworkSettings SETTINGS =
            new NetworkSettings("nats", "p3", "default", "", "", List.of("nats://user:pw@127.0.0.1:4222"), "", "", "");

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void everyConnectionThatCannotSubscribeIsClosed() throws Exception {
        Connection connection = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(connection.createDispatcher(any())).thenReturn(dispatcher);
        when(dispatcher.subscribe(anyString())).thenThrow(new IllegalArgumentException("Subject is not valid"));
        AtomicInteger closes = new AtomicInteger();
        doAnswer(invocation -> closes.incrementAndGet()).when(connection).close();
        AtomicInteger opens = new AtomicInteger();
        NatsTransport transport = NatsTransport.create(SETTINGS, options -> {
            opens.incrementAndGet();
            return connection;
        });

        transport.start(CHANNELS, (channel, text) -> {});
        await(() -> closes.get() >= 2);
        transport.close();
        await(() -> closes.get() == opens.get());
        Thread.sleep(100);
        assertEquals(opens.get(), closes.get());
    }

    @Test
    void theConnectedUrlIsLoggedWithoutItsUserInfo() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.createDispatcher(any())).thenReturn(mock(Dispatcher.class));
        when(connection.getConnectedUrl()).thenReturn("nats://user:pw@127.0.0.1:4222");
        try (CapturedLog log = new CapturedLog(Level.INFO, "Network transport NATS")) {
            NatsTransport transport = NatsTransport.create(SETTINGS, options -> connection);
            transport.start(CHANNELS, (channel, text) -> {});
            await(() -> !log.messages().isEmpty());
            transport.close();
            assertEquals(List.of("Network transport NATS: connected to nats://***@127.0.0.1:4222"), log.messages());
        }
    }

    @Test
    void aFailureNamingTheServerIsLoggedWithoutItsUserInfo() throws Exception {
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Network transport NATS")) {
            NatsTransport transport = NatsTransport.create(SETTINGS, options -> {
                throw new IOException("Unable to connect to NATS servers: [nats://user:pw@127.0.0.1:4222]");
            });
            transport.start(CHANNELS, (channel, text) -> {});
            await(() -> !log.messages().isEmpty());
            transport.close();
            String warning = log.messages().getFirst();
            assertFalse(warning.contains("pw"), warning);
            assertTrue(warning.contains("nats://***@127.0.0.1:4222"), warning);
        }
    }
}
