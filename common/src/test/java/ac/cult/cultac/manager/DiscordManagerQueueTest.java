package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.data.webhook.discord.Embed;
import ac.cult.cultac.utils.data.webhook.discord.EmbedAuthor;
import ac.cult.cultac.utils.data.webhook.discord.EmbedFooter;
import ac.cult.cultac.utils.data.webhook.discord.WebhookMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordManagerQueueTest {
    private static final long START = 1_700_000_000_000L;
    private static final URI WEBHOOK = URI.create("https://discord.com/api/webhooks/1/token");

    private final List<Long> sentAt = new ArrayList<>();
    private final List<String> sentBodies = new ArrayList<>();
    private final Deque<Supplier<CompletableFuture<HttpResponse<String>>>> script = new ArrayDeque<>();
    private final List<LogRecord> logs = new ArrayList<>();
    private long now = START;
    private DiscordManager.WebhookQueue queue;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        queue = new DiscordManager.WebhookQueue(
                (uri, body) -> {
                    sentAt.add(now - START);
                    sentBodies.add(body);
                    Supplier<CompletableFuture<HttpResponse<String>>> next = script.poll();
                    return next == null ? respond(204, Map.of()) : next.get();
                },
                () -> now,
                () -> logger);
    }

    @Test
    void transportFailureBacksOffAndLogsOneErrorUntilDelivered() {
        script.add(() -> CompletableFuture.failedFuture(new IOException("connect refused")));
        script.add(() -> CompletableFuture.failedFuture(new IOException("connect refused")));
        CompletableFuture<Boolean> result = queue.add(WEBHOOK, alert("Alice"));

        runUntil(10_000);

        assertEquals(List.of(0L, 1_000L, 3_000L), sentAt);
        assertTrue(result.join());
        assertEquals(1, count(Level.SEVERE));
        assertEquals(1, count(Level.INFO));
        assertTrue(messages(Level.INFO).contains("1 alerts"));
    }

    @Test
    void backoffDoublesToSixtySecondsAndResetsOnSuccess() {
        for (int i = 0; i < 8; i++) script.add(() -> respond(502, Map.of()));
        queue.add(WEBHOOK, alert("Alice"));
        runUntil(400_000);
        queue.add(WEBHOOK, alert("Bob"));
        script.add(() -> respond(503, Map.of()));
        runUntil(500_000);

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sentAt.size(); i++) gaps.add(sentAt.get(i) - sentAt.get(i - 1));
        assertEquals(List.of(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), gaps.subList(0, 8));
        assertEquals(1_000L, gaps.get(9));
        assertEquals(2, count(Level.SEVERE));
    }

    @Test
    void serverErrorIsRetried() {
        script.add(() -> respond(503, Map.of()));
        CompletableFuture<Boolean> result = queue.add(WEBHOOK, alert("Alice"));

        runUntil(5_000);

        assertEquals(List.of(0L, 1_000L), sentAt);
        assertTrue(result.join());
    }

    @Test
    void rateLimitWaitsForRetryAfter() {
        script.add(() -> respond(429, Map.of("Retry-After", "2.5")));
        CompletableFuture<Boolean> result = queue.add(WEBHOOK, alert("Alice"));

        runUntil(5_000);

        assertEquals(List.of(0L, 2_500L), sentAt);
        assertTrue(result.join());
        assertEquals(0, count(Level.SEVERE));
    }

    @Test
    void rateLimitWaitsForResetAfterOrReset() {
        script.add(() -> respond(429, Map.of("X-RateLimit-Reset-After", "1.2")));
        script.add(() -> respond(429, Map.of("X-RateLimit-Reset", Long.toString(START / 1000 + 4))));
        queue.add(WEBHOOK, alert("Alice"));

        runUntil(10_000);

        assertEquals(List.of(0L, 1_200L, 4_000L), sentAt);
    }

    @Test
    void rateLimitWithoutHeadersBacksOff() {
        script.add(() -> respond(429, Map.of()));
        script.add(() -> respond(429, Map.of("Retry-After", "soon")));
        CompletableFuture<Boolean> result = queue.add(WEBHOOK, alert("Alice"));

        runUntil(10_000);

        assertEquals(List.of(0L, 1_000L, 3_000L), sentAt);
        assertTrue(result.join());
    }

    @Test
    void rejectedAlertCompletesFalseLogsItsTextAndDoesNotBlockTheNext() {
        script.add(() -> respond(404, Map.of()));
        CompletableFuture<Boolean> rejected = queue.add(WEBHOOK, alert("Alice"));
        queue.tick();
        CompletableFuture<Boolean> next = queue.add(WEBHOOK, alert("Bob"));
        queue.tick();

        assertFalse(rejected.join());
        assertTrue(next.join());
        assertEquals(List.of(0L, 0L), sentAt);
        assertEquals(1, count(Level.SEVERE));
        assertTrue(messages(Level.SEVERE).contains("Alice"));
    }

    @Test
    void consecutiveEmbedAlertsAreMergedIntoBatchesOfTen() {
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 25; i++) results.add(queue.add(WEBHOOK, alert("Player" + i)));

        runUntil(1_000);

        assertEquals(List.of(10, 10, 5), sentBodies.stream().map(this::embeds).toList());
        results.forEach(result -> assertTrue(result.join()));
    }

    @Test
    void mergingRespectsEmbedTextLimitAndSendsContentAlone() {
        queue.add(WEBHOOK, new WebhookMessage().addEmbeds(new Embed("a".repeat(2_500))));
        queue.add(WEBHOOK, new WebhookMessage().addEmbeds(new Embed("b".repeat(2_500))));
        queue.add(WEBHOOK, new WebhookMessage().addEmbeds(new Embed("c".repeat(2_500))));
        queue.add(WEBHOOK, new WebhookMessage().content("test"));
        queue.add(WEBHOOK, alert("Alice"));

        runUntil(1_000);

        assertEquals(List.of(2, 1, 0, 1), sentBodies.stream().map(this::embeds).toList());
    }

    @Test
    void alertsPastTheCapAreWrittenToTheLog() {
        for (int i = 0; i < DiscordManager.WebhookQueue.MAX_QUEUED; i++) queue.add(WEBHOOK, alert("Player" + i));
        CompletableFuture<Boolean> overflow = queue.add(WEBHOOK, alert("Zed"));

        assertTrue(overflow.isDone());
        assertFalse(overflow.join());
        assertEquals(3, count(Level.WARNING));
        assertTrue(messages(Level.WARNING).contains("Zed"));

        runUntil(DiscordManager.WebhookQueue.MAX_QUEUED * 10L);

        assertEquals(MAX_REQUESTS, sentBodies.size());
        assertEquals(1, count(Level.INFO));
        assertTrue(messages(Level.INFO).contains("1 alerts"));
    }

    @Test
    void stopWritesUnsentAlertsToTheLog() {
        CompletableFuture<Boolean> result = queue.add(WEBHOOK, alert("Alice"));

        queue.stop();

        assertFalse(result.join());
        assertTrue(messages(Level.WARNING).contains("Alice"));
    }

    @Test
    void anAnswerArrivingAfterStopIsIgnored() {
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        script.add(() -> pending);
        CompletableFuture<Boolean> result = queue.add(WEBHOOK, alert("Alice"));
        queue.tick();

        queue.stop();
        int warnings = logs.size();
        pending.completeExceptionally(new IOException("client closed"));
        runUntil(60_000);

        assertFalse(result.join());
        assertEquals(1, sentBodies.size());
        assertEquals(warnings, logs.size());
    }

    @Test
    void anAlertThatCannotBeRequestedIsLoggedAndDoesNotBlockTheNext() {
        script.add(() -> {
            throw new IllegalArgumentException("unsupported URI");
        });
        CompletableFuture<Boolean> broken = queue.add(WEBHOOK, alert("Alice"));
        queue.tick();
        CompletableFuture<Boolean> next = queue.add(WEBHOOK, alert("Bob"));
        runUntil(1_000);

        assertFalse(broken.join());
        assertTrue(next.join());
        assertEquals(1, count(Level.SEVERE));
        assertTrue(messages(Level.SEVERE).contains("Alice"));
    }

    @Test
    void loggedAlertsKeepTheirFooterAuthorAndTime() {
        Instant at = Instant.parse("2026-10-17T12:00:00Z");
        Embed embed = new Embed("**Check**: Reach")
                .author(new EmbedAuthor("Alice"))
                .footer(new EmbedFooter("UUID: 0f7c3a52-1d4e-4f6a-9a51-2b7f3c9d8e10"))
                .timestamp(at);
        queue.add(WEBHOOK, new WebhookMessage().addEmbeds(embed));

        queue.stop();

        String logged = messages(Level.WARNING);
        assertTrue(logged.contains("UUID: 0f7c3a52-1d4e-4f6a-9a51-2b7f3c9d8e10"), logged);
        assertTrue(logged.contains("Alice"), logged);
        assertTrue(logged.contains(at.toString()), logged);
    }

    private static final int MAX_REQUESTS = DiscordManager.WebhookQueue.MAX_QUEUED / 10;

    private void runUntil(long elapsed) {
        while (now - START <= elapsed) {
            queue.tick();
            now += 50;
        }
    }

    private static WebhookMessage alert(String player) {
        return new WebhookMessage().addEmbeds(new Embed("**Player**: `" + player + "`\n**Check**: Reach"));
    }

    private int embeds(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        return json.has("embeds") ? json.getAsJsonArray("embeds").size() : 0;
    }

    private long count(Level level) {
        return logs.stream().filter(record -> record.getLevel() == level).count();
    }

    private String messages(Level level) {
        return logs.stream()
                .filter(record -> record.getLevel() == level)
                .map(LogRecord::getMessage)
                .collect(Collectors.joining("\n"));
    }

    private static CompletableFuture<HttpResponse<String>> respond(int status, Map<String, String> headers) {
        return CompletableFuture.completedFuture(new FakeResponse(status, headers));
    }

    private record FakeResponse(int statusCode, Map<String, String> headerValues) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(WEBHOOK).build();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(
                    headerValues.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))),
                    (name, value) -> true);
        }

        @Override
        public String body() {
            return "{\"message\": \"Unknown Webhook\"}";
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return WEBHOOK;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
