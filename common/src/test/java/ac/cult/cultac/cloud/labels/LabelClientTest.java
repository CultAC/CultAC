package ac.cult.cultac.cloud.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabelClientTest {
    private static final String KEY = "intake-secret-123";
    /** The key as a JSON string can carry it, its first character escaped. */
    private static final String ESCAPED = "\\u0069" + KEY.substring(1);

    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final UUID MOD = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    private record Seen(String method, String uri, String authorization, String body) {}

    private HttpServer server;
    private final ExecutorService calls = Executors.newSingleThreadExecutor(task -> new Thread(task, "label-call"));
    private final List<Seen> seen = new ArrayList<>();
    private int status = 201;
    private String answer = "{}";
    private volatile Flood flood;

    /** An answer far past the label cap, chunked or with its length declared; {@code sent} is what got out. */
    static final class Flood {
        static final int BYTES = 32 << 20;
        final boolean chunked;
        final CompletableFuture<Long> sent = new CompletableFuture<>();

        Flood(boolean chunked) {
            this.chunked = chunked;
        }

        void send(HttpExchange exchange, int status) {
            long written = 0;
            byte[] block = new byte[64 << 10];
            Arrays.fill(block, (byte) '[');
            try {
                exchange.sendResponseHeaders(status, chunked ? 0 : BYTES);
                OutputStream out = exchange.getResponseBody();
                while (written < BYTES) {
                    out.write(block);
                    written += block.length;
                }
                out.close();
            } catch (IOException cutOff) {
            } finally {
                sent.complete(written);
                exchange.close();
            }
        }
    }

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
            Flood flooding = flood;
            if (flooding != null) {
                flooding.send(exchange, status);
                return;
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
    void anUntrustedOrganizationIsToldSoOnceAndNeverRetried() throws Exception {
        status = 403;
        answer = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                + "\"detail\":\"labels are for trusted organizations\"}";
        JsonObject body = LabelRequest.cheat("other", "high", "24h", "n").body(PLAYER, 500_000_000, "Mod", MOD);

        LabelClient.Reply reply = client().post(body).get(10, TimeUnit.SECONDS);

        assertFalse(reply.ok());
        assertEquals(LabelClient.NOT_ALLOWED, LabelClient.failure(reply));
        assertEquals(
                "This organization can't label yet: labels are for trusted organizations.", LabelClient.NOT_ALLOWED);
        assertEquals(
                LabelClient.NOT_ALLOWED,
                LabelClient.failure(new LabelClient.Reply(403, "{\"detail\":\"this key may not label\"}")));
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
    void anAnswerThatEchoesTheKeyNeverShowsIt() throws Exception {
        status = 400;
        for (String echo : List.of(KEY, ESCAPED)) {
            for (String detail : List.of("Authorization: Bearer " + echo, "x".repeat(150) + echo)) {
                answer = "{\"detail\":\"" + detail + "\"}";
                String failure =
                        LabelClient.failure(client().post(new JsonObject()).get(10, TimeUnit.SECONDS));
                assertFalse(failure.contains(KEY.substring(0, 6)), failure);
            }
        }
        answer = "{\"detail\":\"Authorization: Bearer " + ESCAPED + "\"}";
        assertEquals(
                "HTTP 400: Authorization: Bearer <api-key>",
                LabelClient.failure(client().list(PLAYER).get(10, TimeUnit.SECONDS)));
        answer = "Authorization: Bearer " + KEY;
        assertEquals(
                "HTTP 400: Authorization: Bearer <api-key>",
                LabelClient.failure(client().list(PLAYER).get(10, TimeUnit.SECONDS)));
    }

    @Test
    void aListedLabelNeverShowsTheKey() throws Exception {
        status = 200;
        answer = "[{\"id\":\"" + PLAYER + "\",\"cheat\":\"" + ESCAPED + "\",\"labeller\":\"" + "x".repeat(60) + ESCAPED
                + "\",\"source\":{\"by\":\"" + ESCAPED + "\"}}]";
        LabelClient.Reply reply = client().list(PLAYER).get(10, TimeUnit.SECONDS);
        JsonObject label = LabelClient.labels(reply.body()).get(0);

        assertEquals("<api-key>", LabelClient.field(label, "cheat", reply));
        for (String name : List.of("cheat", "labeller", "source")) {
            String shown = LabelClient.field(label, name, reply);
            assertFalse(shown.contains(KEY.substring(0, 6)), shown);
            assertTrue(shown.length() <= LabelClient.MAX_FIELD, shown);
        }
        assertEquals(PLAYER.toString(), LabelClient.field(label, "id", reply));
    }

    @Test
    void aShortKeyStillReadsTheAnswer() throws Exception {
        status = 200;
        answer = "{\"labels\":[{\"id\":\"" + PLAYER + "\",\"cheat\":\"Reach\"}]}";
        for (String key : List.of("a", "id")) {
            LabelClient.Reply reply = client(key).list(PLAYER).get(10, TimeUnit.SECONDS);
            assertEquals(1, LabelClient.labels(reply.body()).size(), key);
        }
        LabelClient.Reply listed = client("id").list(PLAYER).get(10, TimeUnit.SECONDS);
        JsonObject label = LabelClient.labels(listed.body()).get(0);
        assertEquals(PLAYER.toString(), LabelClient.field(label, "id", listed));
        assertEquals("Reach", LabelClient.field(label, "cheat", listed));

        status = 201;
        answer = "{\"id\":\"" + PLAYER + "\"}";
        LabelClient.Reply posted = client("id").post(new JsonObject()).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(PLAYER.toString()), LabelClient.ids(posted.body()));
    }

    @Test
    void anOversizedAnswerIsCutOffWhateverItsStatusOrFraming() throws Exception {
        for (int code : new int[] {200, 403}) {
            for (boolean chunked : new boolean[] {true, false}) {
                status = code;
                Flood flooding = new Flood(chunked);
                flood = flooding;
                ExecutionException failed = assertThrows(
                        ExecutionException.class, () -> client().list(PLAYER).get(20, TimeUnit.SECONDS));
                String why = LabelClient.unanswered(failed);
                assertFalse(why.contains(KEY), why);
                long sent = flooding.sent.get(20, TimeUnit.SECONDS);
                assertTrue(sent < Flood.BYTES, code + (chunked ? " chunked" : " declared") + " sent " + sent);
            }
        }
    }

    @Test
    void readsLabelIdsInEveryShape() {
        String x = PLAYER.toString();
        String y = MOD.toString();
        assertEquals(List.of(x), LabelClient.ids("{\"id\":\"" + x + "\"}"));
        assertEquals(List.of(x, y), LabelClient.ids("{\"labelIds\":[\"" + x + "\",\"" + y + "\"],\"sessions\":[]}"));
        assertEquals(List.of(x, y), LabelClient.ids("{\"labels\":[{\"id\":\"" + x + "\"},{\"id\":\"" + y + "\"}]}"));
        assertEquals(List.of(x), LabelClient.ids("[{\"id\":\"" + x + "\"}]"));
        assertEquals(List.of(), LabelClient.ids("not json"));
    }

    @Test
    void onlyUuidIdsAreTaken() {
        String x = PLAYER.toString();
        assertEquals(
                List.of(x),
                LabelClient.ids("[\"" + x + "\",\"x\",\"" + x + " /op me\",\"1-1-1-1-1\",{\"id\":\"/op me\"}]"));
    }

    @Test
    void anAnswerYieldsAtMostTheCapOfLabelsAndIds() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            many.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"")
                    .append(new UUID(0, i))
                    .append("\"}");
        }
        many.append(']');
        assertEquals(
                LabelClient.MAX_RESULTS, LabelClient.labels(many.toString()).size());
        assertEquals(LabelClient.MAX_RESULTS, LabelClient.ids(many.toString()).size());
        assertEquals(
                LabelClient.MAX_RESULTS,
                LabelClient.ids("{\"labels\":" + many + "}").size());
    }

    @Test
    void deeplyNestedIdsAreNotFollowed() {
        String id = "\"" + PLAYER + "\"";
        assertEquals(List.of(), LabelClient.ids("{\"ids\":".repeat(50) + id + "}".repeat(50)));
        assertEquals(List.of(), LabelClient.ids("[".repeat(200) + id + "]".repeat(200)));
        assertEquals(List.of(), LabelClient.ids("[".repeat(100_000) + id + "]".repeat(100_000)));
        assertEquals(List.of(), LabelClient.labels("[".repeat(100_000) + "]".repeat(100_000)));
    }

    @Test
    void theServicesMessageIsOneLine() {
        String failure = LabelClient.failure(new LabelClient.Reply(
                400, "{\"message\":\"refused\\n[12:00:00 INFO]: approved\\r\\u2028\\u0085done\"}"));
        assertTrue(failure.startsWith("HTTP 400: refused"), failure);
        assertTrue(failure.chars().noneMatch(c -> c == '\n' || c == '\r' || c == 0x2028 || c == 0x85), failure);
        String plain = LabelClient.failure(new LabelClient.Reply(500, "line one\nline two"));
        assertFalse(plain.contains("\n"), plain);
    }

    @Test
    void flagsAreCountedPerCheatOverTheWindow() throws Exception {
        status = 200;
        answer = "{\"player\":\"" + PLAYER + "\",\"from\":1000,\"to\":2000,"
                + "\"flags\":{\"triggerbot\":3,\"killaura\":14,\"autoclicker\":0}}";

        Map<String, Integer> flags = client().flags(PLAYER, 1000, 2000).get(10, TimeUnit.SECONDS);

        assertEquals(Map.of("triggerbot", 3, "killaura", 14, "autoclicker", 0), flags);
        Seen request = seen.get(0);
        assertEquals("GET", request.method());
        assertEquals("/api/v1/labels/flags?player=" + PLAYER + "&from=1000&to=2000", request.uri());
        assertEquals("Bearer " + KEY, request.authorization());
    }

    @Test
    void flagsThatDidNotArriveAreNone() throws Exception {
        status = 404;
        answer = "{\"detail\":\"not found\"}";
        assertEquals(Map.of(), client().flags(PLAYER, 1, 2).get(10, TimeUnit.SECONDS));
        status = 200;
        answer = "<html>oops</html>";
        assertEquals(Map.of(), client().flags(PLAYER, 1, 2).get(10, TimeUnit.SECONDS));
        assertEquals(Map.of(), LabelClient.flagCounts(new LabelClient.Reply(503, "{\"flags\":{\"killaura\":1}}")));
        assertEquals(Map.of(), LabelClient.flagCounts(new LabelClient.Reply(200, "{\"flags\":[1,2]}")));
        assertEquals(Map.of(), LabelClient.flagCounts(new LabelClient.Reply(200, "[]")));
        assertEquals(
                Map.of("killaura", 2),
                LabelClient.flagCounts(new LabelClient.Reply(200, "{\"flags\":{\"killaura\":2,\"other\":\"x\"}}")));
        LabelClient unreachable = new LabelClient("http://127.0.0.1:1", KEY, HttpClient.newHttpClient(), calls);
        assertEquals(Map.of(), unreachable.flags(PLAYER, 1, 2).get(10, TimeUnit.SECONDS));
    }

    private LabelClient client() {
        return client(KEY);
    }

    private LabelClient client(String key) {
        return new LabelClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), key, HttpClient.newHttpClient(), calls);
    }
}
