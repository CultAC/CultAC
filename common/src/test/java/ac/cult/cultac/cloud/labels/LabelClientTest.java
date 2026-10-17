package ac.cult.cultac.cloud.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabelClientTest {
    private static final String KEY = "intake-secret-123";
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final UUID MOD = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    private record Seen(String method, String uri, String authorization, String body) {}

    private HttpServer server;
    private final ExecutorService calls = Executors.newSingleThreadExecutor(task -> new Thread(task, "label-call"));
    private final List<Seen> seen = new ArrayList<>();
    private int status = 201;
    private String answer = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (seen) {
                seen.add(new Seen(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        body));
            }
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

    @Test
    void aKeyThatMayNotLabelIsToldSoOnceAndNeverRetried() throws Exception {
        status = 403;
        answer = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                + "\"detail\":\"this key may not label\"}";
        JsonObject body = LabelRequest.cheat("other", "high", "24h", "n").body(PLAYER, 500_000_000, "Mod", MOD);

        LabelClient.Reply reply = client().post(body).get(10, TimeUnit.SECONDS);

        assertFalse(reply.ok());
        assertEquals(LabelClient.NOT_ALLOWED, LabelClient.failure(reply));
        Thread.sleep(200);
        assertEquals(1, seen.size());
        assertEquals(
                "The cloud refused this server's key (HTTP 403: organization suspended).",
                LabelClient.failure(new LabelClient.Reply(403, "{\"detail\":\"organization suspended\"}")));
        assertEquals(
                "The cloud refused this server's key (HTTP 403).", LabelClient.failure(new LabelClient.Reply(403, "")));
        assertEquals(
                LabelClient.ONLY_ADMINS,
                LabelClient.failure(
                        new LabelClient.Reply(403, "{\"detail\":\"only admins can mark a player clean\"}")));
        assertEquals(
                LabelClient.NOT_A_MODERATOR,
                LabelClient.failure(new LabelClient.Reply(403, "{\"detail\":\"" + LabelClient.NOT_A_LABELLER + "\"}")));
    }

    @Test
    void postsTheLabelWithTheKey() throws Exception {
        answer = "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"cheat\":\"other\"}";
        JsonObject body = LabelRequest.cheat("other", "high", "24h", "n").body(PLAYER, 500_000_000, "Mod", MOD);

        LabelClient.Reply reply = client().post(body).get(10, TimeUnit.SECONDS);

        assertTrue(reply.ok());
        Seen request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("/api/v1/labels", request.uri());
        assertEquals("Bearer " + KEY, request.authorization());
        assertEquals(body, JsonParser.parseString(request.body()));
        assertEquals(List.of("11111111-1111-1111-1111-111111111111"), LabelClient.ids(reply.body()));
    }

    @Test
    void listsAndRevokes() throws Exception {
        answer = "[{\"id\":\"a\",\"cheat\":\"other\"},{\"id\":\"b\"}]";
        LabelClient client = client();

        LabelClient.Reply listed = client.list(PLAYER).get(10, TimeUnit.SECONDS);
        UUID label = new UUID(3, 4);
        client.revoke(label, "Mod", MOD, "mistake").get(10, TimeUnit.SECONDS);

        assertEquals(2, LabelClient.labels(listed.body()).size());
        assertEquals("GET", seen.get(0).method());
        assertEquals("/api/v1/labels?player=" + PLAYER, seen.get(0).uri());
        assertEquals("POST", seen.get(1).method());
        assertEquals("/api/v1/labels/" + label + "/revoke", seen.get(1).uri());
        JsonObject revoke = JsonParser.parseString(seen.get(1).body()).getAsJsonObject();
        assertEquals("Mod", revoke.get("labeller").getAsString());
        assertEquals("mistake", revoke.get("reason").getAsString());
        assertEquals(MOD.toString(), revoke.get("labellerId").getAsString());
        assertEquals("Bearer " + KEY, seen.get(1).authorization());
    }

    @Test
    void failuresNameTheStatusAndNeverTheKey() throws Exception {
        status = 400;
        answer = "{\"status\":400,\"message\":\"cheat is required\"}";
        LabelClient.Reply reply = client().post(new JsonObject()).get(10, TimeUnit.SECONDS);

        assertFalse(reply.ok());
        assertEquals("HTTP 400: cheat is required", LabelClient.failure(reply));
        assertEquals(
                "The cloud refused this server's key (HTTP 401).", LabelClient.failure(new LabelClient.Reply(401, "")));

        server.stop(0);
        Exception unreachable = null;
        try {
            client().list(PLAYER).get(20, TimeUnit.SECONDS);
        } catch (Exception failed) {
            unreachable = failed;
        }
        assertNotNull(unreachable);
        String why = LabelClient.unanswered(unreachable);
        assertFalse(why.isEmpty());
        assertFalse(why.contains(KEY), why);
    }

    @Test
    void readsLabelIdsInEveryShape() {
        assertEquals(List.of("x"), LabelClient.ids("{\"id\":\"x\"}"));
        assertEquals(List.of("x", "y"), LabelClient.ids("{\"labelIds\":[\"x\",\"y\"],\"sessions\":[]}"));
        assertEquals(List.of("x", "y"), LabelClient.ids("{\"labels\":[{\"id\":\"x\"},{\"id\":\"y\"}]}"));
        assertEquals(List.of("x"), LabelClient.ids("[{\"id\":\"x\"}]"));
        assertEquals(List.of(), LabelClient.ids("not json"));
    }

    private LabelClient client() {
        return new LabelClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), KEY, HttpClient.newHttpClient(), calls);
    }
}
