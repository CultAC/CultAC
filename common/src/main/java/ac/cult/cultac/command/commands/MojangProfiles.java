package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient.Reply;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Mojang's profile API by exact name. Found names are kept in memory for {@link #TTL}. A call goes through the given
 * transport, which must not block its caller.
 */
final class MojangProfiles {
    static final String URL = "https://api.mojang.com/users/profiles/minecraft/";
    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Built on the first lookup, so a server that never looks a name up starts no HTTP client. */
    private static final class Shared {
        static final HttpClient HTTP =
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    private record Entry(CloudArguments.Target target, long expiresAtNanos) {}

    private final Function<String, CompletableFuture<Reply>> transport;
    private final LongSupplier nanos;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    MojangProfiles(Function<String, CompletableFuture<Reply>> transport, LongSupplier nanos) {
        this.transport = transport;
        this.nanos = nanos;
    }

    static MojangProfiles live() {
        return new MojangProfiles(MojangProfiles::get, System::nanoTime);
    }

    private static CompletableFuture<Reply> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return Shared.HTTP
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new Reply(response.statusCode(), response.body()));
    }

    /** A remembered answer for {@code name}, from memory only. */
    CloudArguments.@Nullable Target cached(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry entry = cache.get(key);
        if (entry == null) return null;
        if (nanos.getAsLong() - entry.expiresAtNanos() >= 0) {
            cache.remove(key, entry);
            return null;
        }
        return entry.target();
    }

    /** Asks Mojang about {@code name}, which must already match {@link TargetLookup#NAME}. */
    CompletableFuture<TargetLookup.Outcome> lookup(String name) {
        return transport.apply(URL + name).thenApply(reply -> read(name, reply));
    }

    private TargetLookup.Outcome read(String name, Reply reply) {
        if (reply.status() == 404 || reply.status() == 204) {
            return new TargetLookup.Outcome(
                    null, "Mojang has no player named " + name + " (use their UUID if the name changed).");
        }
        if (reply.status() == 429) {
            return new TargetLookup.Outcome(
                    null, "Mojang is rate limiting name lookups; wait a minute, or use their UUID.");
        }
        if (!reply.ok()) return new TargetLookup.Outcome(null, "Mojang lookup failed (HTTP " + reply.status() + ").");
        CloudArguments.Target target = parse(reply.body());
        if (target == null) return new TargetLookup.Outcome(null, "Mojang lookup failed: unreadable answer.");
        cache.put(name.toLowerCase(Locale.ROOT), new Entry(target, nanos.getAsLong() + TTL.toNanos()));
        return new TargetLookup.Outcome(target, null);
    }

    private static CloudArguments.@Nullable Target parse(String body) {
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            String id = object.get("id").getAsString();
            if (id.length() != 32) return null;
            UUID uuid = new UUID(
                    Long.parseUnsignedLong(id.substring(0, 16), 16), Long.parseUnsignedLong(id.substring(16), 16));
            return new CloudArguments.Target(uuid, object.get("name").getAsString());
        } catch (JsonParseException | IllegalStateException | NumberFormatException | NullPointerException bad) {
            return null;
        }
    }
}
