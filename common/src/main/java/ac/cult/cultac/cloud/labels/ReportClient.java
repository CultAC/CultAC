package ac.cult.cultac.cloud.labels;

import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudService;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.labels.LabelClient.Reply;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The cloud's report API at {@code cloud.url}, called with {@code cloud.api-key}: one player's report of another, which
 * the service keeps with a replay of the reported player from a minute before it to a minute after, when it can. Every
 * call runs once, off the calling thread, with timeouts. The key goes only into the {@code Authorization} header; it is
 * never logged, shown or put in an error.
 */
public final class ReportClient {
    public static final int MAX_TYPE = 64;
    public static final int MAX_REASON = 500;
    public static final int MAX_SERVER = 128;
    /** The most characters of the service's error text shown. */
    private static final int MAX_ERROR = 160;
    /** The most characters of a report id or replay state shown. */
    static final int MAX_FIELD = 64;

    private static final Pattern TYPE = Pattern.compile("[a-z0-9_.:-]{1," + MAX_TYPE + "}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * A report as it is filed: who reported whom, for what, on which server, at {@code at} (epoch millis). A name is
     * null when it is not known; {@code reason} and {@code server} are empty when there is none.
     */
    public record Report(
            UUID reporter,
            @Nullable String reporterName,
            UUID reported,
            @Nullable String reportedName,
            String type,
            String reason,
            String server,
            long at) {
        /** The {@code POST /api/v1/reports} body. */
        public JsonObject body() {
            JsonObject body = new JsonObject();
            body.addProperty("reporter", reporter.toString());
            if (reporterName != null) body.addProperty("reporterName", reporterName);
            body.addProperty("reported", reported.toString());
            if (reportedName != null) body.addProperty("reportedName", reportedName);
            body.addProperty("type", type);
            if (!reason.isEmpty()) body.addProperty("reason", reason);
            body.addProperty("server", server.length() > MAX_SERVER ? server.substring(0, MAX_SERVER) : server);
            body.addProperty("at", at);
            return body;
        }
    }

    /**
     * The service's answer: the report's id, the replay's state ({@code recording}, {@code none}, or null when the
     * answer did not say) and why there is none, each one masked line.
     */
    public record Filed(
            String id, @Nullable String replay, @Nullable String why) {}

    /** A filed report, or the line saying why it was not filed: exactly one is set. */
    public record Outcome(@Nullable Filed filed, @Nullable String error) {}

    private final String base;
    private final String apiKey;
    /** The key as it appears inside a JSON string. */
    private final String writtenKey;

    private final HttpClient http;
    private final Executor executor;

    ReportClient(String base, String apiKey, HttpClient http, Executor executor) {
        this.base = base;
        this.apiKey = apiKey;
        String written = new JsonPrimitive(apiKey).toString();
        this.writtenKey = written.substring(1, written.length() - 1);
        this.http = http;
        this.executor = executor;
    }

    /** The client for this server's cloud settings; null while the upload is not configured. */
    public static @Nullable ReportClient configured() {
        CloudSettings settings = CloudService.settings();
        if (settings.url().isEmpty() || settings.apiKey().isEmpty()) return null;
        return new ReportClient(settings.url(), settings.apiKey(), CloudHttp.HTTP, CloudHttp.CALLS);
    }

    /**
     * {@code input} as the service takes a report type: trimmed, lower case, spaces as {@code _}, 1 to
     * {@value #MAX_TYPE} of {@code a-z 0-9 _ . : -}; throws {@link IllegalArgumentException} naming the rule otherwise.
     */
    public static String type(String input) {
        String type = SPACES.matcher(input.strip().toLowerCase(Locale.ROOT)).replaceAll("_");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("A report type is 1 to " + MAX_TYPE
                    + " letters, digits, _ . : or -, such as illegal_crossteaming.");
        }
        return type;
    }

    /** {@code input} stripped, empty for none; throws {@link IllegalArgumentException} past {@value #MAX_REASON}. */
    public static String reason(@Nullable String input) {
        String reason = input == null ? "" : input.strip();
        if (reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("A reason is at most " + MAX_REASON + " characters.");
        }
        return reason;
    }

    /** Files {@code report}; the future always completes normally. */
    public CompletableFuture<Outcome> file(Report report) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/reports"))
                .timeout(LabelClient.REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(report.body().toString()))
                .build();
        return CompletableFuture.supplyAsync(
                        () -> http.sendAsync(request, info -> new CappedBody(LabelClient.MAX_BODY)), executor)
                .thenCompose(Function.identity())
                .handle((response, error) -> error == null
                        ? outcome(new Reply(response.statusCode(), response.body(), this::mask))
                        : new Outcome(null, "The cloud did not answer: " + LabelClient.unanswered(error) + "."));
    }

    /** {@code text} with this client's key masked, as it is and as a JSON string writes it. */
    private String mask(String text) {
        if (apiKey.isEmpty()) return text;
        String masked = text.replace(apiKey, "<api-key>");
        return writtenKey.equals(apiKey) ? masked : masked.replace(writtenKey, "<api-key>");
    }

    static Outcome outcome(Reply reply) {
        return reply.ok() ? new Outcome(filed(reply), null) : new Outcome(null, failure(reply));
    }

    /** The id and capture in a 2xx answer; {@code ?} for an id it does not carry. */
    static Filed filed(Reply reply) {
        try {
            JsonElement parsed = JsonParser.parseString(reply.body());
            if (!parsed.isJsonObject()) return new Filed("?", null, null);
            JsonObject answer = parsed.getAsJsonObject();
            String id = text(answer, "id", reply);
            JsonElement capture = answer.get("capture");
            if (capture == null || !capture.isJsonObject()) return new Filed(id == null ? "?" : id, null, null);
            JsonObject replay = capture.getAsJsonObject();
            return new Filed(id == null ? "?" : id, text(replay, "state", reply), text(replay, "why", reply));
        } catch (JsonParseException | IllegalStateException notJson) {
            return new Filed("?", null, null);
        }
    }

    private static @Nullable String text(JsonObject object, String name, Reply reply) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return null;
        return LogThrottle.oneLine(reply.mask().apply(value.getAsString()), MAX_FIELD);
    }

    /** Why the service did not take a report, with its own message when it gave one, the key masked. */
    static String failure(Reply reply) {
        String message = LogThrottle.oneLine(reply.mask().apply(LabelClient.said(reply.body())), MAX_ERROR);
        String said = message.isEmpty() ? "" : ": " + message;
        return switch (reply.status()) {
            case 400 -> "The cloud refused the report" + (message.isEmpty() ? " (HTTP 400)." : said);
            case 401 -> "The cloud refused this server's key (HTTP 401).";
            case 403 -> "The cloud refused this server's key (HTTP 403" + said + ").";
            case 404 -> "The cloud does not take reports (HTTP 404).";
            case 429 -> "The cloud is taking too many reports; try again shortly (HTTP 429).";
            default -> "HTTP " + reply.status() + said;
        };
    }
}
