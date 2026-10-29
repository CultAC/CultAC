package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener.Events;
import io.nats.client.Dispatcher;
import io.nats.client.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The connection events jnats reports, from a stand-in opener; a real NATS server is not exercised here. */
class NatsTransportTest {
    private static final List<String> CHANNELS = List.of("cultac.0123abcd.p3", "cultac.0123abcd.all");
    private static final NetworkSettings SETTINGS =
            new NetworkSettings("nats://user:pw@127.0.0.1:4222, tls://tok3n@127.0.0.2:4443", "", "p3", "", "");

    @BeforeAll
    static void logging() {
        OfflineCultTestBootstrap.installConfig();
    }

    /** Records the options it is handed instead of connecting. */
    private static final class Opener implements NatsTransport.Opener {
        @Nullable
        Options options;

        @Override
        public void open(Options options) {
            this.options = options;
        }

        Options opened() {
            Options opened = options;
            assertNotNull(opened, "not opened");
            return opened;
        }

        void event(Connection connection, Events type) {
            opened().getConnectionListener().connectionEvent(connection, type);
        }

        void failed(@Nullable Connection connection, Exception failure) {
            opened().getErrorListener().exceptionOccurred(connection, failure);
        }
    }

    private final Opener opener = new Opener();

    private NatsTransport transport() {
        return new NatsTransport(SETTINGS, Redaction.of(SETTINGS), opener);
    }

    private static Connection connection(Dispatcher dispatcher) {
        Connection connection = mock(Connection.class);
        when(connection.createDispatcher(any())).thenReturn(dispatcher);
        when(connection.getConnectedUrl()).thenReturn("nats://user:pw@127.0.0.1:4222");
        return connection;
    }

    @Test
    void theOptionsNameEveryServerAndRetryForever() {
        NatsTransport transport = transport();
        transport.start(CHANNELS, (channel, text) -> {});
        Options options = opener.opened();
        assertEquals(2, options.getServers().size());
        assertEquals(-1, options.getMaxReconnect());
        assertTrue(options.isNoEcho());
        transport.close();
    }

    @Test
    void subscribesOnceOnConnectAndLogsTheUrlWithoutItsUserInfo() throws Exception {
        Dispatcher dispatcher = mock(Dispatcher.class);
        Connection connection = connection(dispatcher);
        try (CapturedLog log = new CapturedLog(Level.INFO, "Network transport NATS")) {
            NatsTransport transport = transport();
            transport.start(CHANNELS, (channel, text) -> {});
            opener.event(connection, Events.CONNECTED);
            opener.event(connection, Events.RECONNECTED);
            transport.close();
            assertEquals(
                    List.of(
                            "Network transport NATS: connected to nats://***@127.0.0.1:4222",
                            "Network transport NATS: reconnected to nats://***@127.0.0.1:4222"),
                    log.messages());
        }
        for (String channel : CHANNELS) verify(dispatcher).subscribe(channel);
    }

    @Test
    void aConnectionThatSucceedsOnlyOnARetryStillSubscribes() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        Connection connection = connection(dispatcher);
        NatsTransport transport = transport();
        transport.start(CHANNELS, (channel, text) -> {});
        opener.failed(connection, new IOException("refused"));
        opener.event(connection, Events.RECONNECTED);
        for (String channel : CHANNELS) verify(dispatcher).subscribe(channel);
        transport.close();
    }

    @Test
    void publishesOnTheConnectionOnceThereIsOne() {
        Connection connection = connection(mock(Dispatcher.class));
        NatsTransport transport = transport();
        transport.start(CHANNELS, (channel, text) -> {});
        transport.publish("cultac.0123abcd.p7", "dropped");
        opener.event(connection, Events.CONNECTED);
        transport.publish("cultac.0123abcd.p7", "é sent");
        verify(connection).publish("cultac.0123abcd.p7", "é sent".getBytes(StandardCharsets.UTF_8));
        verify(connection, never()).publish("cultac.0123abcd.p7", "dropped".getBytes(StandardCharsets.UTF_8));
        transport.close();
    }

    @Test
    void aFailedConnectIsLoggedOnceWithoutItsUserInfo() {
        try (CapturedLog log = new CapturedLog(Level.WARNING, "Network transport NATS")) {
            NatsTransport transport = transport();
            transport.start(CHANNELS, (channel, text) -> {});
            Connection connection = mock(Connection.class);
            IOException failure = new IOException(
                    "Unable to connect to NATS servers: [nats://user:pw@127.0.0.1:4222, tls://tok3n@b]");
            opener.failed(connection, failure);
            opener.failed(connection, failure);
            transport.close();
            assertEquals(1, log.messages().size(), log.messages().toString());
            String warning = log.messages().getFirst();
            assertFalse(warning.contains("pw@"), warning);
            assertFalse(warning.contains("tok3n"), warning);
            assertTrue(warning.contains("nats://***@127.0.0.1:4222"), warning);
        }
    }

    @Test
    void closingFlushesAndClosesTheConnection() throws Exception {
        Connection connection = connection(mock(Dispatcher.class));
        NatsTransport transport = transport();
        transport.start(CHANNELS, (channel, text) -> {});
        opener.event(connection, Events.CONNECTED);
        transport.close();
        verify(connection).flush(any());
        verify(connection).close();
        transport.close();
        verify(connection).close();
    }

    @Test
    void aConnectionThatArrivesAfterCloseIsClosedUnsubscribed() throws Exception {
        Dispatcher dispatcher = mock(Dispatcher.class);
        Connection connection = connection(dispatcher);
        NatsTransport transport = transport();
        transport.start(CHANNELS, (channel, text) -> {});
        transport.close();
        opener.event(connection, Events.CONNECTED);
        opener.event(connection, Events.CLOSED);
        verify(connection, timeout(5_000)).close();
        verify(connection, never()).createDispatcher(any());
    }

    @Test
    void aConnectionThatCannotSubscribeIsClosed() throws Exception {
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(dispatcher.subscribe(anyString())).thenThrow(new IllegalArgumentException("Subject is not valid"));
        Connection connection = connection(dispatcher);
        try (CapturedLog log = new CapturedLog(Level.SEVERE, "Network transport NATS: cannot subscribe")) {
            NatsTransport transport = transport();
            transport.start(CHANNELS, (channel, text) -> {});
            opener.event(connection, Events.CONNECTED);
            verify(connection, timeout(5_000)).close();
            assertEquals(1, log.messages().size());
            transport.close();
        }
    }

    @Test
    void anUnreadableCredentialsFileIsRejected() {
        String missing = Path.of("build", "no-such.creds").toString();
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> new NatsTransport(
                        new NetworkSettings("nats://127.0.0.1:4222", "", "p3", missing, ""),
                        Redaction.of(SETTINGS),
                        opener));
        assertTrue(rejected.getMessage().startsWith("network.nats-credentials-file"), rejected.getMessage());
    }
}
