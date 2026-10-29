package ac.cult.cultac.cloud.history;

import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudService;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * The cloud's history of a player at {@code cloud.url}, called with {@code cloud.api-key}: their sessions on every
 * server of the organization, newest first, and each session's findings, oldest first. Every call runs once, off the
 * calling thread, with timeouts. A call completes exceptionally with a {@link Failure} when the cloud answers anything
 * but 200 with a readable body; fields it does not know are ignored. The key goes only into the {@code Authorization}
 * header.
 */
public final class HistoryClient {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** The most bytes of an answer read, whatever its status; past it the call fails. */
    static final int MAX_BODY = 4 << 20;
    /** The most sessions one call asks for. */
    public static final int MAX_SESSIONS = 50;
    /** The most findings one call asks for. */
    public static final int MAX_FINDINGS = 500;

    /** A check that flagged in a session: how often, its highest value, and its first and last flag (epoch millis). */
    public record Check(String source, String check, long flags, double peak, long first, long last) {}

    /**
     * A session on one of the organization's servers. {@code ended} is null while it is open; {@code endReason} is
     * empty while it is open and {@code lost} when its stream ended without a close; {@code agentVersion} is the
     * version of this anticheat that recorded it, or empty; {@code findings} counts every finding in it.
     */
    public record Session(
            UUID id,
            long started,
            @Nullable Long ended,
            String endReason,
            long lastSeen,
            @Nullable String server,
            @Nullable String name,
            @Nullable String brand,
            int protocol,
            String agentVersion,
            long findings,
            List<Check> checks) {}

    /**
     * One page of a player's sessions; {@code total} counts all of them, {@code next} is the opaque cursor that asks
     * for the following page, null on the last.
     */
    public record Sessions(
            long total, List<Session> sessions, @Nullable String next) {}

    /** A finding: {@code value} is its violation level and {@code detail} its verbose. */
    public record Finding(long at, long tick, String source, String check, double value, String detail) {}

    /** One page of a session's findings; {@code next} is null on the last page. */
    public record Findings(List<Finding> findings, @Nullable String next) {}

    /** The cloud answered, but not with 200 and a readable body; {@code status} is 200 for an unreadable one. */
    public static final class Failure extends RuntimeException {
        private final int status;

        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** How long a 403 holds: until it passes, calls with the same url and key fail with it without asking. */
    static final Duration REFUSAL_MEMORY = Duration.ofMinutes(10);
    /** The last 403 of the clients {@link #configured()} gives. */
    private static final AtomicReference<Refusal> CONFIGURED_REFUSAL = new AtomicReference<>();

    private record Refusal(String base, String apiKey, long untilNanos) {}

    private final String base;
    private final String apiKey;
    private final HttpClient http;
    private final Executor executor;
    private final AtomicReference<Refusal> refusal;
    private final LongSupplier nanos;

    HistoryClient(String base, String apiKey, HttpClient http, Executor executor) {
        this(base, apiKey, http, executor, System::nanoTime);
    }

    HistoryClient(String base, String apiKey, HttpClient http, Executor executor, LongSupplier nanos) {
        this(base, apiKey, http, executor, new AtomicReference<>(), nanos);
    }

    private HistoryClient(
            String base,
            String apiKey,
            HttpClient http,
            Executor executor,
            AtomicReference<Refusal> refusal,
            LongSupplier nanos) {
        this.base = base;
        this.apiKey = apiKey;
        this.http = http;
        this.executor = executor;
        this.refusal = refusal;
        this.nanos = nanos;
    }

    /** The client for this server's cloud settings; null while the upload is not configured. */
    public static @Nullable HistoryClient configured() {
        CloudSettings settings = CloudService.settings();
        if (settings.url().isEmpty() || settings.apiKey().isEmpty()) return null;
        return new HistoryClient(
                settings.url(), settings.apiKey(), CloudHttp.HTTP, CloudHttp.CALLS, CONFIGURED_REFUSAL, System::nanoTime);
    }

    /** Whether the cloud answered this url and key with 403 within {@link #REFUSAL_MEMORY}. */
    public boolean refused() {
        Refusal last = refusal.get();
        return last != null
                && last.base().equals(base)
                && last.apiKey().equals(apiKey)
                && nanos.getAsLong() - last.untilNanos() < 0;
    }

    /** Up to {@code limit} of {@code player}'s sessions from the cursor {@code before} a page gave, or the newest. */
    public CompletableFuture<Sessions> sessions(UUID player, @Nullable String before, int limit) {
        String query = "?limit=" + Math.clamp(limit, 1, MAX_SESSIONS)
                + (before == null ? "" : "&before=" + URLEncoder.encode(before, StandardCharsets.UTF_8));
        return get("/api/v1/players/" + player + "/sessions" + query, HistoryClient::readSessions);
    }

    /** Up to {@code limit} of the findings in {@code session} after the cursor {@code after}, or the first. */
    public CompletableFuture<Findings> findings(UUID player, UUID session, @Nullable String after, int limit) {
        String query = "?limit=" + Math.clamp(limit, 1, MAX_FINDINGS)
                + (after == null ? "" : "&after=" + URLEncoder.encode(after, StandardCharsets.UTF_8));
        return get(
                "/api/v1/players/" + player + "/sessions/" + session + "/findings" + query,
                HistoryClient::readFindings);
    }

    private <T> CompletableFuture<T> get(String path, Function<String, T> read) {
        if (refused()) return CompletableFuture.failedFuture(new Failure(403, "HTTP 403"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .GET()
                .build();
        return CompletableFuture.supplyAsync(() -> http.sendAsync(request, info -> new CappedBody(MAX_BODY)), executor)
                .thenCompose(Function.identity())
                .thenApply(response -> {
                    if (response.statusCode() == 403) {
                        refusal.set(new Refusal(base, apiKey, nanos.getAsLong() + REFUSAL_MEMORY.toNanos()));
                    }
                    if (response.statusCode() != 200) {
                        throw new Failure(response.statusCode(), "HTTP " + response.statusCode());
                    }
                    return read.apply(response.body());
                });
    }

    /** A {@code GET …/sessions} answer; throws a {@link Failure} with status 200 when it cannot be read. */
    static Sessions readSessions(String body) {
        try {
            JsonObject answer = object(JsonParser.parseString(body));
            long total = number(answer, "total");
            List<Session> sessions = new ArrayList<>();
            for (JsonElement item : array(answer, "sessions")) {
                if (sessions.size() >= MAX_SESSIONS) break;
                sessions.add(session(object(item)));
            }
            return new Sessions(total, List.copyOf(sessions), optionalText(answer, "next"));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException unreadable) {
            throw unreadable("sessions", unreadable);
        }
    }

    /** A {@code GET …/findings} answer; throws a {@link Failure} with status 200 when it cannot be read. */
    static Findings readFindings(String body) {
        try {
            JsonObject answer = object(JsonParser.parseString(body));
            List<Finding> findings = new ArrayList<>();
            for (JsonElement item : array(answer, "findings")) {
                if (findings.size() >= MAX_FINDINGS) break;
                JsonObject finding = object(item);
                findings.add(new Finding(
                        number(finding, "at"),
                        orZero(optionalNumber(finding, "tick")),
                        orEmpty(optionalText(finding, "source")),
                        text(finding, "check"),
                        decimal(finding, "value"),
                        orEmpty(optionalText(finding, "detail"))));
            }
            return new Findings(List.copyOf(findings), optionalText(answer, "next"));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException unreadable) {
            throw unreadable("findings", unreadable);
        }
    }

    private static Session session(JsonObject session) {
        long started = number(session, "started");
        Long ended = optionalNumber(session, "ended");
        Long lastSeen = optionalNumber(session, "lastSeen");
        List<Check> checks = new ArrayList<>();
        JsonElement listed = session.get("checks");
        if (listed != null && listed.isJsonArray()) {
            for (JsonElement item : listed.getAsJsonArray()) {
                JsonObject check = object(item);
                checks.add(new Check(
                        orEmpty(optionalText(check, "source")),
                        text(check, "check"),
                        orZero(optionalNumber(check, "flags")),
                        decimal(check, "peak"),
                        orZero(optionalNumber(check, "first")),
                        orZero(optionalNumber(check, "last"))));
            }
        }
        Long findings = optionalNumber(session, "findings");
        Long protocol = optionalNumber(session, "protocol");
        return new Session(
                UUID.fromString(text(session, "session")),
                started,
                ended,
                orEmpty(optionalText(session, "endReason")),
                lastSeen != null ? lastSeen : ended != null ? ended : started,
                optionalText(session, "server"),
                optionalText(session, "name"),
                optionalText(session, "brand"),
                protocol == null ? 0 : Math.clamp(protocol, 0, Integer.MAX_VALUE),
                orEmpty(optionalText(session, "agentVersion")),
                findings != null
                        ? findings
                        : checks.stream().mapToLong(Check::flags).sum(),
                List.copyOf(checks));
    }

    private static Failure unreadable(String what, RuntimeException cause) {
        Failure failure = new Failure(200, "unreadable " + what + " answer");
        failure.initCause(cause);
        return failure;
    }

    private static JsonObject object(JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalStateException("not an object");
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) throw new IllegalStateException(name + " is not a list");
        return value.getAsJsonArray();
    }

    private static long number(JsonObject object, String name) {
        Long value = optionalNumber(object, name);
        if (value == null) throw new IllegalStateException(name + " is missing");
        return value;
    }

    private static @Nullable Long optionalNumber(JsonObject object, String name) {
        JsonPrimitive value = primitive(object, name);
        if (value == null) return null;
        if (!value.isNumber()) throw new IllegalStateException(name + " is not a number");
        return value.getAsLong();
    }

    private static double decimal(JsonObject object, String name) {
        JsonPrimitive value = primitive(object, name);
        if (value == null) return 0;
        if (!value.isNumber()) throw new IllegalStateException(name + " is not a number");
        return value.getAsDouble();
    }

    private static String text(JsonObject object, String name) {
        String value = optionalText(object, name);
        if (value == null) throw new IllegalStateException(name + " is missing");
        return value;
    }

    private static @Nullable String optionalText(JsonObject object, String name) {
        JsonPrimitive value = primitive(object, name);
        return value == null ? null : value.getAsString();
    }

    /** The field as a primitive; null when it is absent or JSON null, and unreadable when it is a list or object. */
    private static @Nullable JsonPrimitive primitive(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive()) throw new IllegalStateException(name + " is not a value");
        return value.getAsJsonPrimitive();
    }

    private static long orZero(@Nullable Long value) {
        return value == null ? 0 : value;
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
