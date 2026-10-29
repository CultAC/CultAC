package ac.cult.cultac.cloud.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HistoryClientTest {
    private static final String KEY = "intake-secret-123";
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final UUID SESSION = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final UUID OPEN = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private record Seen(String method, String uri, String authorization) {}

    private HttpServer server;
    private final ExecutorService calls = Executors.newSingleThreadExecutor(task -> new Thread(task, "history-call"));
    private final List<Seen> seen = new ArrayList<>();
    private volatile int status = 200;
    private volatile String answer = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            synchronized (seen) {
                seen.add(new Seen(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Authorization")));
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
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
    void readsASessionPageWithItsPagingFields() throws Exception {
        answer = "{\"player\":\"" + PLAYER + "\",\"total\":42,\"region\":\"eu\",\"sessions\":["
                + "{\"session\":\"" + OPEN + "\",\"started\":1793150000000,\"ended\":null,\"endReason\":\"\","
                + "\"lastSeen\":1793150060000,\"server\":\"lobby-2\",\"name\":\"Steve\",\"brand\":\"fabric\","
                + "\"protocol\":774,\"agentVersion\":\"2.4.0\",\"findings\":0,\"checks\":[],\"colour\":\"blue\"},"
                + "{\"session\":\"" + SESSION + "\",\"started\":1793140000000,\"ended\":1793143600000,"
                + "\"endReason\":\"lost\",\"lastSeen\":1793143600000,\"server\":\"survival-1\",\"name\":\"Steve\","
                + "\"brand\":\"vanilla\",\"protocol\":773,\"agentVersion\":\"\",\"findings\":17,"
                + "\"checks\":[{\"source\":\"engine\",\"check\":\"Reach\",\"flags\":12,"
                + "\"peak\":4.0,\"first\":1793140100000,\"last\":1793143500000,\"weight\":3},"
                + "{\"source\":\"cloud-model\",\"check\":\"killaura\",\"flags\":5,\"peak\":0.9,"
                + "\"first\":1,\"last\":2}]}],"
                + "\"next\":\"s/2+=?&x\"}";

        HistoryClient.Sessions page = client().sessions(PLAYER, "s/1+=?&x", 10).get(10, TimeUnit.SECONDS);

        assertEquals(42, page.total());
        assertEquals("s/2+=?&x", page.next());
        assertEquals(2, page.sessions().size());
        HistoryClient.Session open = page.sessions().get(0);
        assertEquals(OPEN, open.id());
        assertNull(open.ended());
        assertEquals("", open.endReason());
        assertEquals("2.4.0", open.agentVersion());
        assertEquals(1793150060000L, open.lastSeen());
        assertEquals("lobby-2", open.server());
        HistoryClient.Session closed = page.sessions().get(1);
        assertEquals(SESSION, closed.id());
        assertEquals(1793143600000L, closed.ended());
        assertEquals("lost", closed.endReason());
        assertEquals("", closed.agentVersion());
        assertEquals("survival-1", closed.server());
        assertEquals("vanilla", closed.brand());
        assertEquals(773, closed.protocol());
        assertEquals(17, closed.findings());
        assertEquals(
                new HistoryClient.Check("engine", "Reach", 12, 4.0, 1793140100000L, 1793143500000L),
                closed.checks().get(0));
        assertEquals(
                new HistoryClient.Check("cloud-model", "killaura", 5, 0.9, 1, 2),
                closed.checks().get(1));

        Seen request = seen.get(0);
        assertEquals("GET", request.method());
        assertEquals("/api/v1/players/" + PLAYER + "/sessions?limit=10&before=s%2F1%2B%3D%3F%26x", request.uri());
        assertEquals("Bearer " + KEY, request.authorization());
    }

    @Test
    void theNextCursorGoesBackUnchanged() throws Exception {
        answer = "{\"total\":3,\"sessions\":[],\"next\":\"eyJ0IjoxNzkzfQ==\"}";
        HistoryClient.Sessions first = client().sessions(PLAYER, null, 10).get(10, TimeUnit.SECONDS);

        answer = "{\"total\":3,\"sessions\":[],\"next\":null}";
        client().sessions(PLAYER, first.next(), 10).get(10, TimeUnit.SECONDS);

        assertEquals("eyJ0IjoxNzkzfQ==", first.next());
        assertEquals(
                "/api/v1/players/" + PLAYER + "/sessions?limit=10&before=eyJ0IjoxNzkzfQ%3D%3D",
                seen.get(1).uri());
    }

    @Test
    void theLastSessionPageHasNoNextAndTheFirstNoBefore() throws Exception {
        answer = "{\"total\":0,\"sessions\":[],\"next\":null}";

        HistoryClient.Sessions page = client().sessions(PLAYER, null, 500).get(10, TimeUnit.SECONDS);

        assertEquals(0, page.total());
        assertTrue(page.sessions().isEmpty());
        assertNull(page.next());
        assertEquals(
                "/api/v1/players/" + PLAYER + "/sessions?limit=50", seen.get(0).uri());
    }

    @Test
    void readsAFindingPageWithItsCursor() throws Exception {
        answer = "{\"player\":\"" + PLAYER + "\",\"session\":\"" + SESSION + "\",\"findings\":["
                + "{\"at\":1793140100000,\"tick\":1234,\"source\":\"engine\",\"check\":\"Reach\",\"value\":3.0,"
                + "\"detail\":\"distance=3.41\",\"extra\":[1]},"
                + "{\"at\":1793140200000,\"check\":\"killaura\"}],\"next\":\"c/2+=\"}";

        HistoryClient.Findings page =
                client().findings(PLAYER, SESSION, "a b&c", 200).get(10, TimeUnit.SECONDS);

        assertEquals("c/2+=", page.next());
        assertEquals(
                new HistoryClient.Finding(1793140100000L, 1234, "engine", "Reach", 3.0, "distance=3.41"),
                page.findings().get(0));
        assertEquals(
                new HistoryClient.Finding(1793140200000L, 0, "", "killaura", 0, ""),
                page.findings().get(1));
        assertEquals(
                "/api/v1/players/" + PLAYER + "/sessions/" + SESSION + "/findings?limit=200&after=a+b%26c",
                seen.get(0).uri());
        assertEquals("Bearer " + KEY, seen.get(0).authorization());

        answer = "{\"findings\":[],\"next\":null}";
        HistoryClient.Findings last =
                client().findings(PLAYER, SESSION, null, 9999).get(10, TimeUnit.SECONDS);
        assertNull(last.next());
        assertEquals(
                "/api/v1/players/" + PLAYER + "/sessions/" + SESSION + "/findings?limit=500",
                seen.get(1).uri());
    }

    @Test
    void anyStatusButOkIsAFailureCarryingIt() {
        for (int code : new int[] {400, 401, 403, 404, 500, 502, 503}) {
            status = code;
            answer = code == 503 ? "" : "{\"detail\":\"no\",\"total\":1,\"sessions\":[]}";
            HistoryClient.Failure failure =
                    failure(() -> client().sessions(PLAYER, null, 10).get(10, TimeUnit.SECONDS));
            assertEquals(code, failure.status());
            assertFalse(failure.getMessage().contains(KEY));
            HistoryClient.Failure findings =
                    failure(() -> client().findings(PLAYER, SESSION, null, 10).get(10, TimeUnit.SECONDS));
            assertEquals(code, findings.status());
        }
    }

    @Test
    void aForbiddenAnswerHoldsWithoutAskingUntilItsMemoryPasses() throws Exception {
        AtomicLong now = new AtomicLong();
        HistoryClient client = new HistoryClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                KEY,
                HttpClient.newHttpClient(),
                calls,
                now::get);
        status = 403;

        assertEquals(403, failure(() -> client.sessions(PLAYER, null, 10).get(10, TimeUnit.SECONDS)).status());
        assertTrue(client.refused());
        status = 200;
        answer = "{\"total\":0,\"sessions\":[]}";
        now.addAndGet(HistoryClient.REFUSAL_MEMORY.toNanos() - 1);
        assertEquals(403, failure(() -> client.findings(PLAYER, SESSION, null, 10).get(10, TimeUnit.SECONDS)).status());
        assertEquals(1, seen.size());
        assertFalse(client().refused());

        now.incrementAndGet();
        assertFalse(client.refused());
        assertEquals(0, client.sessions(PLAYER, null, 10).get(10, TimeUnit.SECONDS).total());
        assertEquals(2, seen.size());
    }

    @Test
    void anUnreadableAnswerIsAFailureWithStatusOk() {
        List<String> sessionAnswers = List.of(
                "<html>oops</html>",
                "[]",
                "{\"sessions\":[]}",
                "{\"total\":1}",
                "{\"total\":1,\"sessions\":{}}",
                "{\"total\":\"x\",\"sessions\":[]}",
                "{\"total\":1,\"sessions\":[{\"session\":\"not-a-uuid\",\"started\":1}]}",
                "{\"total\":1,\"sessions\":[{\"session\":\"" + SESSION + "\"}]}",
                "{\"total\":1,\"sessions\":[1]}",
                "[".repeat(100_000));
        for (String body : sessionAnswers) {
            answer = body;
            HistoryClient.Failure failure =
                    failure(() -> client().sessions(PLAYER, null, 10).get(10, TimeUnit.SECONDS));
            assertEquals(200, failure.status(), body.substring(0, Math.min(40, body.length())));
        }
        for (String body : List.of("{}", "{\"findings\":[{\"check\":\"Reach\"}]}", "{\"findings\":[{\"at\":1}]}")) {
            answer = body;
            HistoryClient.Failure failure =
                    failure(() -> client().findings(PLAYER, SESSION, null, 10).get(10, TimeUnit.SECONDS));
            assertEquals(200, failure.status(), body);
        }
    }

    @Test
    void missingOptionalFieldsTakeTheirDefaults() {
        HistoryClient.Sessions page = HistoryClient.readSessions("{\"total\":3,\"sessions\":[{\"session\":\"" + SESSION
                + "\",\"started\":100,\"ended\":250,\"checks\":[{\"check\":\"Timer\",\"flags\":4},"
                + "{\"check\":\"Reach\",\"flags\":2}]}]}");

        HistoryClient.Session session = page.sessions().get(0);
        assertNull(page.next());
        assertEquals(250, session.lastSeen());
        assertEquals("", session.endReason());
        assertEquals("", session.agentVersion());
        assertNull(session.server());
        assertNull(session.brand());
        assertEquals(0, session.protocol());
        assertEquals(6, session.findings());
        assertEquals("", session.checks().get(0).source());
    }

    @Test
    void anUnreachableCloudIsNotAFailureWithAStatus() {
        HistoryClient unreachable = new HistoryClient("http://127.0.0.1:1", KEY, HttpClient.newHttpClient(), calls);
        ExecutionException failed = assertThrows(
                ExecutionException.class,
                () -> unreachable.sessions(PLAYER, null, 10).get(20, TimeUnit.SECONDS));
        assertFalse(failed.getCause() instanceof HistoryClient.Failure);
    }

    @Test
    void anOversizedAnswerFails() {
        answer = "{\"total\":1,\"sessions\":[],\"pad\":\"" + "x".repeat(HistoryClient.MAX_BODY) + "\"}";
        ExecutionException failed = assertThrows(
                ExecutionException.class,
                () -> client().sessions(PLAYER, null, 10).get(20, TimeUnit.SECONDS));
        assertFalse(failed.getCause() instanceof HistoryClient.Failure);
    }

    private interface Call {
        Object get() throws Exception;
    }

    private static HistoryClient.Failure failure(Call call) {
        ExecutionException failed = assertThrows(ExecutionException.class, call::get);
        return assertInstanceOf(HistoryClient.Failure.class, failed.getCause());
    }

    private HistoryClient client() {
        return new HistoryClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), KEY, HttpClient.newHttpClient(), calls);
    }
}
