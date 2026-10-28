package ac.cult.cultac.cloud.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class ReportClientTest {
    private static final String KEY = "intake-secret-123";
    /** The key as a JSON string can carry it, its first character escaped. */
    private static final String ESCAPED = "\\u0069" + KEY.substring(1);

    private static final UUID REPORTER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final UUID REPORTED = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");

    private record Seen(String method, String uri, String authorization, String body) {}

    private HttpServer server;
    private final ExecutorService calls = Executors.newSingleThreadExecutor(task -> new Thread(task, "report-call"));
    private final List<Seen> seen = new ArrayList<>();
    private int status = 202;
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
    void postsTheReportWithTheKeyAndReadsTheCapture() throws Exception {
        answer = "{\"id\":\"r-1\",\"capture\":{\"id\":\"c-1\",\"state\":\"recording\",\"why\":null}}";
        ReportClient.Report report = report("Bob", "kept teaming", "lobby-2");

        ReportClient.Outcome outcome = client().file(report).get(10, TimeUnit.SECONDS);

        assertEquals(new ReportClient.Filed("r-1", "recording", null), outcome.filed());
        assertNull(outcome.error());
        Seen request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("/api/v1/reports", request.uri());
        assertEquals("Bearer " + KEY, request.authorization());
        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertEquals(report.body(), body);
        assertEquals(REPORTER.toString(), body.get("reporter").getAsString());
        assertEquals("Alice", body.get("reporterName").getAsString());
        assertEquals(REPORTED.toString(), body.get("reported").getAsString());
        assertEquals("Bob", body.get("reportedName").getAsString());
        assertEquals("illegal_crossteaming", body.get("type").getAsString());
        assertEquals("kept teaming", body.get("reason").getAsString());
        assertEquals("lobby-2", body.get("server").getAsString());
        assertEquals(1_700_000_000_000L, body.get("at").getAsLong());
    }

    @Test
    void aReportWithoutANameOrReasonLeavesThemOut() {
        JsonObject body = report(null, "", "").body();
        assertFalse(body.has("reportedName"));
        assertFalse(body.has("reason"));
        assertEquals("", body.get("server").getAsString());
        assertEquals(
                ReportClient.MAX_SERVER,
                report("Bob", "", "s".repeat(200))
                        .body()
                        .get("server")
                        .getAsString()
                        .length());
    }

    @Test
    void aReportWithoutAReplaySaysWhy() throws Exception {
        answer = "{\"id\":\"r-2\",\"capture\":{\"id\":null,\"state\":\"none\",\"why\":\"rate_limited\"}}";
        ReportClient.Outcome outcome = client().file(report("Bob", "", "")).get(10, TimeUnit.SECONDS);
        assertEquals(new ReportClient.Filed("r-2", "none", "rate_limited"), outcome.filed());

        status = 200;
        answer = "not json";
        assertEquals(
                new ReportClient.Filed("?", null, null),
                client().file(report("Bob", "", "")).get(10, TimeUnit.SECONDS).filed());
    }

    @Test
    void failuresNameTheStatusAndNeverTheKey() throws Exception {
        status = 400;
        answer = "{\"status\":400,\"detail\":\"type must be 1-64 of [a-z0-9_.:-]\"}";
        assertEquals(
                "The cloud refused the report: type must be 1-64 of [a-z0-9_.:-]",
                client().file(report("Bob", "", "")).get(10, TimeUnit.SECONDS).error());

        for (String echo : List.of(KEY, ESCAPED)) {
            answer = "{\"detail\":\"Authorization: Bearer " + echo + "\"}";
            String error = client().file(report("Bob", "", ""))
                    .get(10, TimeUnit.SECONDS)
                    .error();
            assertEquals("The cloud refused the report: Authorization: Bearer <api-key>", error);
        }
        status = 202;
        answer = "{\"id\":\"" + ESCAPED + "\",\"capture\":{\"state\":\"none\",\"why\":\"" + KEY + "\"}}";
        ReportClient.Filed filed =
                client().file(report("Bob", "", "")).get(10, TimeUnit.SECONDS).filed();
        assertEquals(new ReportClient.Filed("<api-key>", "none", "<api-key>"), filed);

        assertEquals(
                "The cloud refused this server's key (HTTP 401).",
                ReportClient.failure(new LabelClient.Reply(401, "")));
        assertEquals(
                "The cloud refused this server's key (HTTP 403: nope).",
                ReportClient.failure(new LabelClient.Reply(403, "{\"message\":\"nope\"}")));
        assertEquals(
                "The cloud does not take reports (HTTP 404).", ReportClient.failure(new LabelClient.Reply(404, "")));
        assertEquals(
                "The cloud is taking too many reports; try again shortly (HTTP 429).",
                ReportClient.failure(new LabelClient.Reply(429, "{}")));
        assertEquals("HTTP 500: down", ReportClient.failure(new LabelClient.Reply(500, "down")));
    }

    @Test
    void anUnreachableServiceIsAnErrorWithoutTheKey() throws Exception {
        server.stop(0);
        ReportClient.Outcome outcome = client().file(report("Bob", "", "")).get(20, TimeUnit.SECONDS);
        assertNull(outcome.filed());
        assertNotNull(outcome.error());
        assertTrue(outcome.error().startsWith("The cloud did not answer: "), outcome.error());
        assertFalse(outcome.error().contains(KEY), outcome.error());
    }

    @Test
    void typesAreNormalisedOrRefused() {
        assertEquals("illegal_crossteaming", ReportClient.type(" Illegal Crossteaming "));
        assertEquals("team.kill:v2-x", ReportClient.type("TEAM.KILL:v2-x"));
        assertEquals("x".repeat(64), ReportClient.type("x".repeat(64)));
        for (String bad : List.of("", "  ", "x".repeat(65), "griefing!", "über")) {
            assertThrows(IllegalArgumentException.class, () -> ReportClient.type(bad), bad);
        }
    }

    @Test
    void reasonsAreStrippedAndCapped() {
        assertEquals("", ReportClient.reason(null));
        assertEquals("seen it", ReportClient.reason("  seen it "));
        assertEquals(500, ReportClient.reason("r".repeat(500)).length());
        assertThrows(IllegalArgumentException.class, () -> ReportClient.reason("r".repeat(501)));
    }

    private static ReportClient.Report report(String reportedName, String reason, String server) {
        return new ReportClient.Report(
                REPORTER, "Alice", REPORTED, reportedName, "illegal_crossteaming", reason, server, 1_700_000_000_000L);
    }

    private ReportClient client() {
        return new ReportClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), KEY, HttpClient.newHttpClient(), calls);
    }
}
