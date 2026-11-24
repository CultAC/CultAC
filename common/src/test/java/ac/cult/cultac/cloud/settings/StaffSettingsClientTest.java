package ac.cult.cultac.cloud.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaffSettingsClientTest {
    private static final String KEY = "server-key";
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");

    private record Seen(String method, String uri, String authorization, String body) {}

    private HttpServer server;
    private final ExecutorService calls = Executors.newSingleThreadExecutor();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    body));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        calls.shutdownNow();
    }

    private StaffSettingsClient client() {
        return new StaffSettingsClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), KEY, HttpClient.newHttpClient(), calls);
    }

    @Test
    void readsThePlayersStringSettingsWithTheKey() throws Exception {
        answer = "{\"player\":\"" + PLAYER + "\",\"settings\":{\"alerts\":\"false\",\"alert-scope\":\"global\","
                + "\"odd\":1},\"updatedAt\":5}";

        Map<String, String> settings = client().read(PLAYER).get(10, TimeUnit.SECONDS);

        assertEquals(Map.of("alerts", "false", "alert-scope", "global"), settings);
        assertEquals(new Seen("GET", "/api/v1/staff-settings/" + PLAYER, "Bearer " + KEY, ""), seen.get(0));

        status = 404;
        assertNull(client().read(PLAYER).get(10, TimeUnit.SECONDS));
        status = 200;
        answer = "not json";
        assertNull(client().read(PLAYER).get(10, TimeUnit.SECONDS));
    }

    @Test
    void writesChangesAsOnePatch() throws Exception {
        assertTrue(client().write(PLAYER, Map.of("verbose", "true")).get(10, TimeUnit.SECONDS));

        Seen request = seen.get(0);
        assertEquals("PATCH", request.method());
        assertEquals("/api/v1/staff-settings/" + PLAYER, request.uri());
        assertEquals(
                JsonParser.parseString("{\"settings\":{\"verbose\":\"true\"}}"), JsonParser.parseString(request.body()));

        status = 400;
        assertFalse(client().write(PLAYER, Map.of("verbose", "true")).get(10, TimeUnit.SECONDS));
    }
}
