package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CloudConnectionHostileIntakeTest {
    @Test
    void anAnsweredSocketUrlMayCarryTheKeyOnlyOverTlsOrToThisMachine() {
        URI https = URI.create("https://intake.example/api/v1/connect");
        URI loopback = URI.create("http://127.0.0.1:8080/api/v1/connect");
        assertTrue(CloudConnection.answerAllowed(https, "wss://replica.example/intake"));
        assertFalse(CloudConnection.answerAllowed(https, "ws://replica.example/intake"));
        assertFalse(CloudConnection.answerAllowed(https, "wss://user:secret@replica.example/intake"));
        assertFalse(CloudConnection.answerAllowed(https, "https://replica.example/intake"));
        assertFalse(CloudConnection.answerAllowed(https, "wss:///intake"));
        assertFalse(CloudConnection.answerAllowed(https, "not a url"));
        assertTrue(CloudConnection.answerAllowed(loopback, "ws://127.0.0.1:8080/intake"));
        assertTrue(CloudConnection.answerAllowed(loopback, "wss://replica.example/intake"));
        assertFalse(CloudConnection.answerAllowed(loopback, "ws://user@127.0.0.1:8080/intake"));
        assertFalse(CloudConnection.answerAllowed(
                URI.create("http://intake.example/api/v1/connect"), "ws://replica.example/intake"));
    }

    @Test
    void loggedUrlsKeepOnlySchemeHostPortAndPath() {
        assertEquals(
                "wss://replica.example:9443/intake",
                CloudConnection.loggable("wss://user:secret@replica.example:9443/intake?token=x#y"));
        assertEquals("an unreadable URL", CloudConnection.loggable("not a url"));
    }

    @Test
    void anOversizedConnectAnswerIsRetriedAndLoggedShort() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            assertNotNull(receiver.connections.forServer("proxy.a"));
            byte[] body = new byte[1024 * 1024];
            Arrays.fill(body, (byte) 'x');
            receiver.answerConnect(HttpResponseStatus.OK, body);
            LogRecord warning = awaitLog(globals, "Cloud connection to");
            assertTrue(warning.getMessage().contains("answer over 65536 bytes"), warning.getMessage());
            assertTrue(warning.getMessage().length() < 300, warning.getMessage().length() + " characters");
            assertFalse(warning.getMessage().contains("xxxxxxxxxx"));
            assertNotNull(receiver.connects.poll(5, TimeUnit.SECONDS), "retried");
            assertTrue(receiver.handshakes.isEmpty());
        }
    }

    @Test
    void aLongErrorBodyIsClipped() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            receiver.connections.forServer("proxy.a");
            String body = "\u001b[31m" + "y".repeat(10_000);
            receiver.answerConnect(HttpResponseStatus.SERVICE_UNAVAILABLE, body.getBytes(StandardCharsets.UTF_8));
            LogRecord warning = awaitLog(globals, "Cloud connection to");
            assertTrue(warning.getMessage().length() < 300, warning.getMessage().length() + " characters");
            assertFalse(warning.getMessage().contains("\u001b"));
        }
    }

    @Test
    void aSocketUrlWithUserInfoIsNeverOpened() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            receiver.connections.forServer("proxy.a");
            String url = receiver.socketUrl().replace("ws://", "ws://user:secret@");
            receiver.answerConnect(
                    HttpResponseStatus.OK,
                    ("{\"url\":\"" + url + "\",\"protocol\":3,\"maxMessageBytes\":2097152}")
                            .getBytes(StandardCharsets.UTF_8));
            LogRecord warning = awaitLog(globals, "Cloud connection to");
            assertTrue(warning.getMessage().contains("may not carry the key"), warning.getMessage());
            assertFalse(warning.getMessage().contains("secret"));
            assertNotNull(receiver.connects.poll(5, TimeUnit.SECONDS), "retried");
            assertTrue(receiver.handshakes.isEmpty());
        }
    }

    @Test
    void aMessageLimitBelow64KiBIsRetriedAndOneAbove64MiBIsClamped() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            receiver.answerConnect(HttpResponseStatus.OK, answer(receiver, 1000));
            assertTrue(awaitLog(globals, "Cloud connection to").getMessage().contains("message limit of 1000"));
            assertEquals(0, connection.maxFrameBytes());
            receiver.answerConnect(HttpResponseStatus.OK, answer(receiver, Integer.MAX_VALUE));
            assertNotNull(receiver.handshakes.poll(5, TimeUnit.SECONDS));
            await(() -> connection.maxFrameBytes() > 0, "connected");
            assertTrue(connection.maxFrameBytes() < CloudConnection.MAX_MESSAGE_BYTES);
        }
    }

    @Test
    void aLateRefusalFromAReplacedConnectionLeavesTheNewSettingsOn() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection old = connections.forServer("proxy.a");
            connections.configure(settings(receiver.url(), "other-key", CloudSettings.SERVER_PROTOCOLS));
            owner(connections).unavailable(old, "late refusal");
            assertTrue(connections.available());
            assertNotNull(connections.forServer("proxy.a"));
        }
    }

    @Test
    void aRefusalTurnsTheUploadOffAndRunsTheHookOnce() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            CloudConnections connections = receiver.connections;
            AtomicInteger turnedOff = new AtomicInteger();
            connections.onTurnedOff(turnedOff::incrementAndGet);
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.PROXY_PROTOCOLS));
            assertTrue(connections.available());
            CloudConnection connection = connections.forServer("proxy.a");
            receiver.refuseConnect();
            await(connection::closed, "socket closed");
            await(() -> turnedOff.get() == 1, "hook ran");
            assertFalse(connections.available());
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.PROXY_PROTOCOLS));
            assertTrue(connections.available());
            assertEquals(1, turnedOff.get());
        }
    }

    @Test
    void aReplyForAnotherSocketStillAsksForTheNextMessage() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            receiver.connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = receiver.connections.forServer("proxy.a");
            WebSocket early = Mockito.mock(WebSocket.class);
            var constructor = Class.forName(CloudConnection.class.getName() + "$Replies")
                    .getDeclaredConstructor(CloudConnection.class, long.class);
            constructor.setAccessible(true);
            WebSocket.Listener listener = (WebSocket.Listener) constructor.newInstance(connection, 1L);
            listener.onBinary(early, ByteBuffer.wrap(new byte[] {1, 2}), true);
            Mockito.verify(early).request(1);
        }
    }

    @Test
    void aTakenOverServerIdConnectsAgainOnlyAfterTheTakeoverWait() throws Exception {
        CloudConnection.Timing timing = new CloudConnection.Timing(
                CloudConnection.Timing.DEFAULT.quietNanos(),
                CloudConnection.Timing.DEFAULT.idleNanos(),
                System::nanoTime,
                TimeUnit.MILLISECONDS.toNanos(1500));
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing)) {
            CloudConnections connections = receiver.connections;
            connections.configure(settings(receiver.url(), "test-key", CloudSettings.SERVER_PROTOCOLS));
            CloudConnection connection = connections.forServer("proxy.a");
            receiver.allowConnect();
            Channel socket = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(socket);
            socket.writeAndFlush(new CloseWebSocketFrame(4000, "replaced"));
            assertNull(receiver.connects.poll(500, TimeUnit.MILLISECONDS), "no reconnect within the wait");
            assertFalse(connection.closed());
            assertSame(connection, connections.forServer("proxy.a"), "the id stays with its connection");
            assertTrue(connections.available());
            receiver.allowConnect();
            assertNotNull(receiver.handshakes.poll(5, TimeUnit.SECONDS), "connects again after the wait");
            synchronized (globals.logs) {
                List<LogRecord> warnings = globals.logs.stream()
                        .filter(record -> record.getMessage().contains("taken over"))
                        .toList();
                assertEquals(1, warnings.size(), String.valueOf(warnings));
            }
        }
    }

    private static byte[] answer(Receiver receiver, int maxMessageBytes) {
        return ("{\"url\":\"" + receiver.socketUrl() + "\",\"protocol\":3,\"maxMessageBytes\":" + maxMessageBytes + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static CloudConnection.Owner owner(CloudConnections connections) throws Exception {
        Field field = CloudConnections.class.getDeclaredField("owner");
        field.setAccessible(true);
        return (CloudConnection.Owner) field.get(connections);
    }

    /** The first log record starting with {@code prefix}, once there is one; it is taken off the log. */
    private static LogRecord awaitLog(Globals globals, String prefix) throws InterruptedException {
        LogRecord[] found = new LogRecord[1];
        await(
                () -> {
                    synchronized (globals.logs) {
                        for (LogRecord record : globals.logs) {
                            if (record.getMessage().startsWith(prefix)) {
                                found[0] = record;
                                globals.logs.remove(record);
                                return true;
                            }
                        }
                        return false;
                    }
                },
                "a log line starting " + prefix);
        return found[0];
    }
}
