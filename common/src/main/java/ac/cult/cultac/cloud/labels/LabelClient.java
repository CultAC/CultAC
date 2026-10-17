package ac.cult.cultac.cloud.labels;

import ac.cult.cultac.cloud.CloudService;
import ac.cult.cultac.cloud.CloudSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * The cloud's label API at {@code cloud.url}, called with {@code cloud.api-key}. The service takes the key there only
 * while it may label, and otherwise answers 403 {@value #NO_LABEL}, which staff see as {@link #NOT_ALLOWED}. Every call
 * runs once, off the calling thread, with timeouts. The key goes only into the {@code Authorization} header; it is
 * never logged, shown or put in an error.
 */
public final class LabelClient {
    /** The service's problem detail for a key that may not label. */
    static final String NO_LABEL = "this key may not label";
    /** The service's problem detail for a labelling player who is not one of the organization's moderators. */
    static final String NOT_A_LABELLER =
            "this player is not a moderator of the organization with this Minecraft account on their profile";
    /** The service's problem detail for a moderator's clean label. */
    static final String CLEAN_ADMINS = "only admins can mark a player clean";

    public static final String ONLY_ADMINS = "Only admins can mark a player clean.";
    public static final String NOT_A_MODERATOR =
            "Your Minecraft account isn't a moderator of this server's cloud organization.";
    public static final String NOT_ALLOWED = "This server's key isn't allowed to label.";

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ERROR = 160;

    /** Built on the first call, so a server that never labels starts no HTTP client. */
    private static final class Shared {
        static final Executor CALLS = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cult-cloud-label-", 0).factory());
        static final HttpClient HTTP = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(CALLS)
                .build();
    }

    /** A finished call: the HTTP status and the body. */
    public record Reply(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final String base;
    private final String apiKey;
    private final HttpClient http;
    private final Executor executor;

    LabelClient(String base, String apiKey, HttpClient http, Executor executor) {
        this.base = base;
        this.apiKey = apiKey;
        this.http = http;
        this.executor = executor;
    }

    /** The client for this server's cloud settings; null while the upload is not configured. */
    public static @Nullable LabelClient configured() {
        CloudSettings settings = CloudService.settings();
        if (settings.url().isEmpty() || settings.apiKey().isEmpty()) return null;
        return new LabelClient(settings.url(), settings.apiKey(), Shared.HTTP, Shared.CALLS);
    }

    public CompletableFuture<Reply> post(JsonObject label) {
        return send(request("/api/v1/labels").POST(HttpRequest.BodyPublishers.ofString(label.toString())));
    }

    public CompletableFuture<Reply> list(UUID player) {
        return send(request("/api/v1/labels?player=" + URLEncoder.encode(player.toString(), StandardCharsets.UTF_8))
                .GET());
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
                        () -> http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()), executor)
                .thenCompose(Function.identity())
                .thenApply(response -> new Reply(response.statusCode(), response.body()));
    }

    /**
     * What went wrong, for the staff member: {@link #NOT_ALLOWED} when the key may not label, {@link #NOT_A_MODERATOR}
     * when the player may not, {@link #ONLY_ADMINS} for a moderator's clean label, else the status and the service's
     * own message when it gave one.
     */
    public static String failure(Reply reply) {
        String message = message(reply.body());
        return switch (reply.status()) {
            case 401 -> "The cloud refused this server's key (HTTP 401).";
            case 403 ->
                switch (message) {
                    case NO_LABEL -> NOT_ALLOWED;
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

    static String message(String body) {
        if (body == null || body.isBlank()) return "";
        String message = body.strip();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                for (String key : List.of("message", "detail", "error")) {
                    if (object.has(key) && object.get(key).isJsonPrimitive()) {
                        message = object.get(key).getAsString();
                        break;
                    }
                }
            }
        } catch (JsonParseException | IllegalStateException notJson) {
        }
        return message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR - 1) + "…" : message;
    }

    /** The label ids in a {@code POST} answer: one label, a list of them, or a list of ids. */
    public static List<String> ids(String body) {
        List<String> ids = new ArrayList<>();
        try {
            collectIds(JsonParser.parseString(body), ids);
        } catch (JsonParseException | IllegalStateException notJson) {
            return List.of();
        }
        return ids;
    }

    private static void collectIds(JsonElement element, List<String> ids) {
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) ids.add(item.getAsString());
                else collectIds(item, ids);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("id") && object.get("id").isJsonPrimitive()) {
            ids.add(object.get("id").getAsString());
            return;
        }
        for (String key : List.of("labelIds", "ids", "labels")) {
            if (object.has(key)) collectIds(object.get(key), ids);
        }
    }

    /** The labels in a {@code GET ?player=} answer: a list, or an object holding {@code labels}. */
    public static List<JsonObject> labels(String body) {
        List<JsonObject> labels = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("labels")) {
                parsed = parsed.getAsJsonObject().get("labels");
            }
            if (parsed.isJsonArray()) {
                JsonArray array = parsed.getAsJsonArray();
                for (JsonElement item : array) if (item.isJsonObject()) labels.add(item.getAsJsonObject());
            }
        } catch (JsonParseException | IllegalStateException notJson) {
            return List.of();
        }
        return labels;
    }
}
