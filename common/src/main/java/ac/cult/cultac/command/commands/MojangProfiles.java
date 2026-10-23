package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient.Reply;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Mojang's profile API by exact name. Found names are kept in memory for {@link #TTL}, at most {@link #MAX_ENTRIES}
 * of them. A call goes through the given
 * transport, which must not block its caller.
 */
final class MojangProfiles {
    static final String URL = "https://api.mojang.com/users/profiles/minecraft/";
    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** The most bytes of an answer read; past it the lookup fails. */
    static final int MAX_BODY = 16 << 10;

    static final int MAX_ENTRIES = 1024;
    static final int MAX_LOOKUPS = 8;
    static final String BUSY = "Too many name lookups are running; try again shortly, or use their UUID.";

    /** Built on the first lookup, so a server that never looks a name up starts no HTTP client. */
    private static final class Shared {
        static final HttpClient HTTP =
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    private record Entry(CloudArguments.Target target, long expiresAtNanos) {}

    private final Function<String, CompletableFuture<Reply>> transport;
    private final LongSupplier nanos;
    /** Oldest first: every entry lives {@link #TTL}, so insertion order is expiry order. Guarded by itself. */
    private final Map<String, Entry> cache = new LinkedHashMap<>();

    private final AtomicInteger inFlight = new AtomicInteger();

    MojangProfiles(Function<String, CompletableFuture<Reply>> transport, LongSupplier nanos) {
        this.transport = transport;
        this.nanos = nanos;
    }

    static MojangProfiles live() {
        return new MojangProfiles(MojangProfiles::get, System::nanoTime);
    }

    int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private static CompletableFuture<Reply> get(String url) {
        return get(Shared.HTTP, url);
    }

    static CompletableFuture<Reply> get(HttpClient http, String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(request, info -> new CappedBody(MAX_BODY))
                .thenApply(response -> new Reply(response.statusCode(), response.body()));
    }

    /** A remembered answer for {@code name}, from memory only. */
    CloudArguments.@Nullable Target cached(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry == null) return null;
            if (nanos.getAsLong() - entry.expiresAtNanos() >= 0) {
                cache.remove(key);
                return null;
            }
            return entry.target();
        }
    }

    /**
     * Asks Mojang about {@code name}, which must already match {@link TargetLookup#NAME}; with {@link #MAX_LOOKUPS}
     * already asking, answers {@link #BUSY} at once instead.
     */
    CompletableFuture<TargetLookup.Outcome> lookup(String name) {
        if (inFlight.incrementAndGet() > MAX_LOOKUPS) {
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(new TargetLookup.Outcome(null, BUSY));
        }
        CompletableFuture<Reply> call;
        try {
            call = transport.apply(URL + name);
        } catch (RuntimeException failed) {
            inFlight.decrementAndGet();
            throw failed;
        }
        call.whenComplete((reply, error) -> inFlight.decrementAndGet());
        return call.thenApply(reply -> read(name, reply));
    }

    /** Keeps {@code target} for {@link #TTL}, after dropping what has expired and, at the bound, the oldest. */
    private void remember(String name, CloudArguments.Target target) {
        long now = nanos.getAsLong();
        synchronized (cache) {
            cache.remove(name);
            Iterator<Entry> oldest = cache.values().iterator();
            while (oldest.hasNext()) {
                Entry entry = oldest.next();
                if (now - entry.expiresAtNanos() < 0 && cache.size() < MAX_ENTRIES) break;
                oldest.remove();
            }
            cache.put(name, new Entry(target, now + TTL.toNanos()));
        }
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
        remember(name.toLowerCase(Locale.ROOT), target);
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
