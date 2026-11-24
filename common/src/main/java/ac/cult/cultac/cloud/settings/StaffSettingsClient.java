package ac.cult.cultac.cloud.settings;

import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudService;
import ac.cult.cultac.cloud.CloudSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.Nullable;

/**
 * The cloud's staff settings at {@code cloud.url}, called with {@code cloud.api-key}: string values under keys this
 * server names, per player, kept for the key's organization. Every call runs off the calling thread, with timeouts.
 */
public final class StaffSettingsClient {
    static final String PATH = "/api/v1/staff-settings/";
    /** The most bytes of an answer read. */
    static final int MAX_BODY = 16 << 10;

    private final String base;
    private final String apiKey;
    private final HttpClient http;
    private final Executor executor;

    StaffSettingsClient(String base, String apiKey, HttpClient http, Executor executor) {
        this.base = base;
        this.apiKey = apiKey;
        this.http = http;
        this.executor = executor;
    }

    /** The client for this server's cloud settings; null while the upload is not configured. */
    public static @Nullable StaffSettingsClient configured() {
        CloudSettings settings = CloudService.settings();
        if (settings.url().isEmpty() || settings.apiKey().isEmpty()) return null;
        return new StaffSettingsClient(settings.url(), settings.apiKey(), CloudHttp.HTTP, CloudHttp.CALLS);
    }

    /** {@code player}'s settings; null when the cloud gives no readable answer. */
    public CompletableFuture<@Nullable Map<String, String>> read(UUID player) {
        return send(request(player).GET()).handle((response, error) -> error == null ? settings(response) : null);
    }

    /** Sets each key {@code changes} names to its value; completes with whether the cloud took them. */
    public CompletableFuture<Boolean> write(UUID player, Map<String, String> changes) {
        return send(request(player).method("PATCH", HttpRequest.BodyPublishers.ofString(body(changes))))
                .handle((response, error) -> error == null && response.statusCode() / 100 == 2);
    }

    static String body(Map<String, String> changes) {
        JsonObject settings = new JsonObject();
        changes.forEach(settings::addProperty);
        JsonObject body = new JsonObject();
        body.add("settings", settings);
        return body.toString();
    }

    /** The string settings in a 200 answer; null for any other status or a body without them. */
    static @Nullable Map<String, String> settings(HttpResponse<String> response) {
        if (response.statusCode() != 200) return null;
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) return null;
            JsonElement settings = parsed.getAsJsonObject().get("settings");
            if (settings == null || !settings.isJsonObject()) return null;
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : settings.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    values.put(entry.getKey(), value.getAsString());
                }
            }
            return values;
        } catch (JsonParseException | IllegalStateException notSettings) {
            return null;
        }
    }

    private HttpRequest.Builder request(UUID player) {
        return CloudHttp.request(base, PATH + player, apiKey);
    }

    private CompletableFuture<HttpResponse<String>> send(HttpRequest.Builder request) {
        return CloudHttp.send(http, executor, request, MAX_BODY);
    }
}
