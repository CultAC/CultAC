package ac.cult.cultac.cloud.labels;

import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudService;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The cloud's label API at {@code cloud.url}, called with {@code cloud.api-key}. The service takes the key there only
 * while its organization is trusted, and otherwise answers 403 {@value #NO_LABEL}, which staff see as
 * {@link #NOT_ALLOWED}. Every call runs once, off the calling thread, with timeouts. The key goes only into the
 * {@code Authorization} header; it is never logged, shown or put in an error.
 */
public final class LabelClient {
    /** The service's problem detail for a key whose organization is not trusted. */
    static final String NO_LABEL = "labels are for trusted organizations";
    /** {@link #NO_LABEL} as older service builds word it. */
    static final String NO_LABEL_KEY = "this key may not label";
    /** The service's problem detail for a labelling player who is not one of the organization's moderators. */
    static final String NOT_A_LABELLER =
            "this player is not a moderator of the organization with this Minecraft account on their profile";
    /** The service's problem detail for a moderator's clean label. */
    static final String CLEAN_ADMINS = "only admins can mark a player clean";

    public static final String ONLY_ADMINS = "Only admins can mark a player clean.";
    public static final String NOT_A_MODERATOR =
            "Your Minecraft account isn't a moderator of this server's cloud organization.";
    public static final String NOT_ALLOWED = "This organization can't label yet: labels are for trusted organizations.";

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ERROR = 160;
    /** The most bytes of an answer read, whatever its status; past it the call fails. */
    static final int MAX_BODY = 256 << 10;
    /** The most labels or ids taken from one answer. */
    public static final int MAX_RESULTS = 50;
    /** The most characters of one label field shown. */
    public static final int MAX_FIELD = 64;
    /** How many wrapping objects and lists {@link #ids} looks into. */
    private static final int MAX_DEPTH = 4;

    private static final Pattern ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * A finished call: the HTTP status, the body, and what masks the caller's key in text read from the body before it
     * is shown.
     */
    public record Reply(int status, String body, UnaryOperator<String> mask) {
        /** A reply to a call that sent no key. */
        public Reply(int status, String body) {
            this(status, body, UnaryOperator.identity());
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final String base;
    private final String apiKey;
    /** The key as it appears inside a JSON string. */
    private final String writtenKey;

    private final HttpClient http;
    private final Executor executor;

    LabelClient(String base, String apiKey, HttpClient http, Executor executor) {
        this.base = base;
        this.apiKey = apiKey;
        String written = new JsonPrimitive(apiKey).toString();
        this.writtenKey = written.substring(1, written.length() - 1);
        this.http = http;
        this.executor = executor;
    }

    /** The client for this server's cloud settings; null while the upload is not configured. */
    public static @Nullable LabelClient configured() {
        CloudSettings settings = CloudService.settings();
        if (settings.url().isEmpty() || settings.apiKey().isEmpty()) return null;
        return new LabelClient(settings.url(), settings.apiKey(), CloudHttp.HTTP, CloudHttp.CALLS);
    }

    public CompletableFuture<Reply> post(JsonObject label) {
        return send(request("/api/v1/labels").POST(HttpRequest.BodyPublishers.ofString(label.toString())));
    }

    public CompletableFuture<Reply> list(UUID player) {
        return send(request("/api/v1/labels?player=" + URLEncoder.encode(player.toString(), StandardCharsets.UTF_8))
                .GET());
    }

    /**
     * How many times each cheat flagged {@code player} from {@code from} to {@code to} (epoch millis), keyed by label
     * cheat; empty when the service answers anything but 200 with a readable body, or not at all.
     */
    public CompletableFuture<Map<String, Integer>> flags(UUID player, long from, long to) {
        return send(request("/api/v1/labels/flags?player="
                                + URLEncoder.encode(player.toString(), StandardCharsets.UTF_8) + "&from=" + from
                                + "&to=" + to)
                        .GET())
                .handle((reply, error) -> error == null ? flagCounts(reply) : Map.of());
    }

    /** The counts in a {@code GET /flags} answer; empty for any status but 200 or a body without them. */
    static Map<String, Integer> flagCounts(Reply reply) {
        if (reply.status() != 200) return Map.of();
        try {
            JsonElement parsed = JsonParser.parseString(reply.body());
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("flags")) return Map.of();
            JsonElement flags = parsed.getAsJsonObject().get("flags");
            if (!flags.isJsonObject()) return Map.of();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : flags.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    counts.put(entry.getKey(), Math.max(0, value.getAsInt()));
                }
            }
            return counts;
        } catch (JsonParseException | IllegalStateException | NumberFormatException notCounts) {
            return Map.of();
        }
    }

    /** Revokes {@code label}; {@code labellerId} is the revoking player's UUID. */
    public CompletableFuture<Reply> revoke(UUID label, String labeller, UUID labellerId, String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("labeller", labeller);
        body.addProperty("labellerId", labellerId.toString());
        body.addProperty("reason", reason);
        return send(request("/api/v1/labels/" + label + "/revoke")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private CompletableFuture<Reply> send(HttpRequest.Builder request) {
        return CompletableFuture.supplyAsync(
                        () -> http.sendAsync(request.build(), info -> new CappedBody(MAX_BODY)), executor)
                .thenCompose(Function.identity())
                .thenApply(response -> new Reply(response.statusCode(), response.body(), this::mask));
    }

    /** {@code text} with this client's key masked, as it is and as a JSON string writes it. */
    private String mask(String text) {
        if (apiKey.isEmpty()) return text;
        String masked = text.replace(apiKey, "<api-key>");
        return writtenKey.equals(apiKey) ? masked : masked.replace(writtenKey, "<api-key>");
    }

    /**
     * What went wrong, for the staff member: {@link #NOT_ALLOWED} when the organization is not trusted,
     * {@link #NOT_A_MODERATOR} when the player may not, {@link #ONLY_ADMINS} for a moderator's clean label, else the
     * status and the service's own message when it gave one, the key masked.
     */
    public static String failure(Reply reply) {
        String said = said(reply.body());
        String message = LogThrottle.oneLine(reply.mask().apply(said), MAX_ERROR);
        return switch (reply.status()) {
            case 401 -> "The cloud refused this server's key (HTTP 401).";
            case 403 ->
                switch (said) {
                    case NO_LABEL, NO_LABEL_KEY -> NOT_ALLOWED;
                    case NOT_A_LABELLER -> NOT_A_MODERATOR;
                    case CLEAN_ADMINS -> ONLY_ADMINS;
                    default ->
                        "The cloud refused this server's key (HTTP 403" + (message.isEmpty() ? "" : ": " + message)
                                + ").";
                };
            case 503 -> "The cloud's label archive is not configured (HTTP 503).";
            default -> "HTTP " + reply.status() + (message.isEmpty() ? "" : ": " + message);
        };
    }

    /** Why a call got no answer: {@code timed out}, {@code could not connect}, or the failure's type. */
    public static String unanswered(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        if (cause instanceof HttpTimeoutException) return "timed out";
        if (cause instanceof ConnectException) return "could not connect";
        return cause.getClass().getSimpleName();
    }

    /**
     * What the service said in {@code body}, decoded: a JSON object's message, detail or error, a JSON string, or a
     * body that is not JSON; empty for any other JSON.
     */
    static String said(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonPrimitive()) return parsed.getAsString();
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                for (String key : List.of("message", "detail", "error")) {
                    JsonElement message = object.get(key);
                    if (message != null && message.isJsonPrimitive()) return message.getAsString();
                }
            }
            return "";
        } catch (JsonParseException | IllegalStateException notJson) {
            return body.strip();
        }
    }

    /**
     * The label ids in a {@code POST} answer: one label, a list of them, or a list of ids. Only UUIDs count, at most
     * {@link #MAX_RESULTS}, inside at most {@link #MAX_DEPTH} wrappers.
     */
    public static List<String> ids(String body) {
        List<String> ids = new ArrayList<>();
        try {
            collectIds(JsonParser.parseString(body), ids, 0);
        } catch (JsonParseException | IllegalStateException notJson) {
            return List.of();
        }
        return ids;
    }

    private static void collectIds(JsonElement element, List<String> ids, int depth) {
        if (depth > MAX_DEPTH) return;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (ids.size() >= MAX_RESULTS) return;
                if (item.isJsonPrimitive()) addId(item, ids);
                else collectIds(item, ids, depth + 1);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("id") && object.get("id").isJsonPrimitive()) {
            addId(object.get("id"), ids);
            return;
        }
        for (String key : List.of("labelIds", "ids", "labels")) {
            if (object.has(key)) collectIds(object.get(key), ids, depth + 1);
        }
    }

    private static void addId(JsonElement id, List<String> ids) {
        String text = id.getAsString();
        if (ids.size() < MAX_RESULTS && isId(text)) ids.add(text);
    }

    /** Whether {@code text} is a label id: a dashed UUID. */
    public static boolean isId(String text) {
        return ID.matcher(text).matches();
    }

    /** The labels in a {@code GET ?player=} answer, at most {@link #MAX_RESULTS}: a list, or an object holding them. */
    public static List<JsonObject> labels(String body) {
        List<JsonObject> labels = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("labels")) {
                parsed = parsed.getAsJsonObject().get("labels");
            }
            if (parsed.isJsonArray()) {
                JsonArray array = parsed.getAsJsonArray();
                for (JsonElement item : array) {
                    if (labels.size() >= MAX_RESULTS) break;
                    if (item.isJsonObject()) labels.add(item.getAsJsonObject());
                }
            }
        } catch (JsonParseException | IllegalStateException notJson) {
            return List.of();
        }
        return labels;
    }

    /** A field of a label in {@code reply}, the key masked, as one line of at most {@link #MAX_FIELD} characters. */
    public static String field(JsonObject label, String name, Reply reply) {
        JsonElement value = label.get(name);
        if (value == null || value.isJsonNull()) return "?";
        String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
        return LogThrottle.oneLine(reply.mask().apply(text), MAX_FIELD);
    }
}
