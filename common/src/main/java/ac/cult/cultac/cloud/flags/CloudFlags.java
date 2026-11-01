package ac.cult.cultac.cloud.flags;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.cloud.CloudFlagCheck;
import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.netty.util.concurrent.EventExecutor;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Raises the cloud flags the service sends: each is one violation of the {@link CloudFlagCheck} of the broad category
 * the service flags a player under, so this server's punishment groups decide what follows. While the upload is
 * configured it polls the service's pending flags, claims each one whose player is online on this instance, raises it
 * on the player's packet thread and reports the result. The key goes only into the {@code Authorization} header.
 */
public final class CloudFlags {
    static final long DEFAULT_POLL_SECONDS = 10;
    static final long MIN_POLL_SECONDS = 5;
    static final long MAX_POLL_SECONDS = 60;
    /** How long to wait before asking a service without cloud flags again. */
    static final long ABSENT_POLL_SECONDS = 300;

    /** How long a claimed flag may wait for the player's packet thread before it is let go unraised. */
    static final Duration RAISE_TIMEOUT = Duration.ofSeconds(30);

    static final long RESULT_RETRY_SECONDS = 10;

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BODY = 256 << 10;
    static final int MAX_HANDLED = 1000;
    /** How much of a flag name a log line shows. */
    static final int MAX_NAME = 64;

    static final String RAISED = "flagged";
    static final String UNKNOWN = "unknown flag";
    static final String FAILED = "the flag failed";
    static final String REFUSED = "the check took no violation";

    /** Bumped on every configure, so only the latest poller claims and raises. */
    private static final Generation GENERATION = new Generation();
    /** Flag ids this instance claimed and results it still owes, across reloads. */
    private static final Handled HANDLED = new Handled(MAX_HANDLED);

    static final Pattern ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** One pending flag: its id, the player and the flag's check name. */
    public record Flag(String id, UUID player, String flag) {}

    /** Where flags are raised: whether a player is connected here, and their check on their packet thread. */
    interface Host {
        boolean online(UUID player);

        /**
         * Runs {@code flag} with {@code player}'s check of {@code type} on the player's packet thread; false when the
         * player or the check is not here to take it.
         *
         * @throws RejectedExecutionException when the packet thread no longer takes tasks
         */
        boolean schedule(UUID player, Class<? extends CloudFlagCheck> type, Consumer<CloudFlagCheck> flag);
    }

    private final String base;
    private final String apiKey;
    private final String serverId;
    private final HttpClient http;
    private final Executor executor;
    private final Host host;
    private final Generation generation;
    private final Handled handled;
    private final LongFunction<Executor> after;
    /** The kinds of problem already logged by this poller. */
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    private long chain;

    CloudFlags(
            String base,
            String apiKey,
            String serverId,
            HttpClient http,
            Executor executor,
            Host host,
            Generation generation,
            Handled handled,
            LongFunction<Executor> after) {
        this.base = base;
        this.apiKey = apiKey;
        this.serverId = serverId;
        this.http = http;
        this.executor = executor;
        this.host = host;
        this.generation = generation;
        this.handled = handled;
        this.after = after;
    }

    /**
     * Applies a configure: polling starts when the upload is configured and this instance has a {@code serverId}; any
     * chain an earlier configure started stops.
     */
    public static void configure(CloudSettings settings, @Nullable String serverId) {
        if (!settings.enabled() || serverId == null) {
            stop();
            return;
        }
        new CloudFlags(
                        settings.url(),
                        settings.apiKey(),
                        serverId,
                        CloudHttp.HTTP,
                        CloudHttp.CALLS,
                        new PlatformHost(),
                        GENERATION,
                        HANDLED,
                        seconds -> CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS, CloudHttp.CALLS))
                .start();
    }

    /**
     * Stops the running poller: it claims and raises nothing more, a claim not yet raised is let go for its lease to
     * run out, and results already owed are still delivered.
     */
    public static void stop() {
        GENERATION.next();
    }

    void start() {
        chain = generation.next();
        poll();
    }

    private void poll() {
        if (!generation.latest(chain)) return;
        pollOnce()
                .whenComplete((next, error) ->
                        after.apply(error == null ? next : DEFAULT_POLL_SECONDS).execute(this::poll));
    }

    /** One round: fetches the pending flags and raises each this instance should; completes with the seconds to wait. */
    CompletableFuture<Long> pollOnce() {
        return send(request("/api/v1/cloud-flags/pending?server=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8))
                        .GET())
                .handle((reply, error) -> {
                    if (!generation.latest(chain)) return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
                    if (error != null) {
                        problem(
                                "pending " + LabelClient.unanswered(error),
                                "Cloud flags: no answer from the cloud service (" + LabelClient.unanswered(error)
                                        + ")");
                        return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
                    }
                    if (reply.status() == 404) {
                        if (logged.add("absent")) {
                            LogUtil.debug("Cloud flags: the cloud service has none; asking again every "
                                    + ABSENT_POLL_SECONDS / 60 + " minutes");
                        }
                        return CompletableFuture.completedFuture(ABSENT_POLL_SECONDS);
                    }
                    if (reply.status() != 200) {
                        problem(
                                "pending " + reply.status(),
                                "Cloud flags: the cloud service answered HTTP " + reply.status()
                                        + " for pending flags");
                        return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
                    }
                    return round(reply.body());
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<Long> round(String body) {
        JsonObject pending;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw new JsonParseException("not an object");
            pending = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException unreadable) {
            problem("pending body", "Cloud flags: the cloud service's pending flags are unreadable");
            return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
        }
        long next =
                Math.clamp(number(pending, "pollSeconds", DEFAULT_POLL_SECONDS), MIN_POLL_SECONDS, MAX_POLL_SECONDS);
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        JsonElement flags = pending.get("flags");
        if (flags != null && flags.isJsonArray()) {
            for (JsonElement item : flags.getAsJsonArray()) {
                Flag flag = item.isJsonObject() ? flag(item.getAsJsonObject()) : null;
                if (flag != null
                        && generation.whileLatest(
                                chain,
                                () -> !handled.contains(flag.id())
                                        && host.online(flag.player())
                                        && handled.add(flag.id()))) {
                    runs.add(claim(flag));
                }
            }
        }
        return CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).handle((done, error) -> next);
    }

    private CompletableFuture<Void> claim(Flag flag) {
        JsonObject body = new JsonObject();
        body.addProperty("server", serverId);
        return send(request("/api/v1/cloud-flags/" + flag.id() + "/claim")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())))
                .handle((reply, error) -> {
                    if ((error != null || reply.status() != 200) && !generation.latest(chain)) {
                        handled.remove(flag.id());
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (error != null) {
                        handled.remove(flag.id());
                        problem(
                                "claim " + LabelClient.unanswered(error),
                                "Cloud flags: no answer from the cloud service to a claim ("
                                        + LabelClient.unanswered(error) + ")");
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (reply.status() != 200) {
                        handled.remove(flag.id());
                        if (reply.status() != 409) {
                            problem(
                                    "claim " + reply.status(),
                                    "Cloud flags: the cloud service answered HTTP " + reply.status() + " to a claim");
                        }
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return raise(claimed(reply.body(), flag));
                })
                .thenCompose(Function.identity());
    }

    /** The flag a won claim answered with, else {@code pending}. */
    private static Flag claimed(String body, Flag pending) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("flag")) return pending;
            JsonElement flag = parsed.getAsJsonObject().get("flag");
            Flag answered = flag.isJsonObject() ? flag(flag.getAsJsonObject()) : null;
            return answered != null
                            && answered.id().equals(pending.id())
                            && answered.player().equals(pending.player())
                    ? answered
                    : pending;
        } catch (JsonParseException | IllegalStateException unreadable) {
            return pending;
        }
    }

    private CompletableFuture<Void> raise(Flag flag) {
        Class<? extends CloudFlagCheck> type = CloudFlagCheck.of(flag.flag());
        if (type == null) {
            problem(
                    "unknown " + flag.flag(),
                    "Cloud flags: the cloud service sent a flag this version does not know: "
                            + LogThrottle.oneLine(flag.flag(), MAX_NAME));
            return report(flag, false, UNKNOWN);
        }
        CompletableFuture<Boolean> raised = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean refused = new AtomicBoolean();
        after.apply(RAISE_TIMEOUT.toSeconds()).execute(() -> {
            if (started.compareAndSet(false, true)) raised.complete(false);
        });
        boolean queued;
        try {
            queued = generation.latest(chain)
                    && host.schedule(flag.player(), type, check -> {
                        if (!generation.whileLatest(chain, () -> started.compareAndSet(false, true))) {
                            raised.complete(false);
                            return;
                        }
                        if (!host.online(flag.player())) {
                            raised.complete(false);
                            return;
                        }
                        try {
                            refused.set(!check.raise());
                            raised.complete(true);
                        } catch (RuntimeException failure) {
                            raised.completeExceptionally(failure);
                            throw failure;
                        }
                    });
        } catch (RejectedExecutionException closed) {
            queued = false;
        } catch (RuntimeException failure) {
            queued = true;
            raised.completeExceptionally(failure);
        }
        if (!queued && started.compareAndSet(false, true)) raised.complete(false);
        return raised.handle((done, error) -> {
                    if (error != null) {
                        problem("raise", "Cloud flags: raising a flag failed: " + error);
                        return report(flag, false, FAILED);
                    }
                    if (!done) {
                        handled.remove(flag.id());
                        LogUtil.info("Cloud flag " + flag.id() + " was not raised: the player left, or CultAC stopped"
                                + " or reloaded; it can be claimed again once its lease runs out");
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (refused.get()) return report(flag, false, REFUSED);
                    return report(flag, true, RAISED);
                })
                .thenCompose(Function.identity());
    }

    /** Owes the service {@code flag}'s result until it takes it; completes after the first attempt. */
    private CompletableFuture<Void> report(Flag flag, boolean ok, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("server", serverId);
        body.addProperty("ok", ok);
        body.addProperty("message", message);
        handled.owe(flag.id());
        return deliver(flag.id(), body.toString());
    }

    /**
     * Posts a result, again every {@link #RESULT_RETRY_SECONDS} after no answer or a 5xx, whatever configure comes
     * meanwhile, until the service takes it (200) or refuses it for good (any 4xx).
     */
    private CompletableFuture<Void> deliver(String id, String body) {
        return send(request("/api/v1/cloud-flags/" + id + "/result").POST(HttpRequest.BodyPublishers.ofString(body)))
                .handle((reply, error) -> {
                    if (error == null && (reply.status() == 200 || reply.status() / 100 == 4)) {
                        handled.paid(id);
                        if (reply.status() != 200) {
                            problem(
                                    "result " + reply.status(),
                                    "Cloud flags: the cloud service answered HTTP " + reply.status() + " to a result");
                        }
                        return null;
                    }
                    if (error != null) {
                        problem(
                                "result " + LabelClient.unanswered(error),
                                "Cloud flags: no answer from the cloud service to a result ("
                                        + LabelClient.unanswered(error) + "); retrying every "
                                        + RESULT_RETRY_SECONDS + " s");
                    } else {
                        problem(
                                "result " + reply.status(),
                                "Cloud flags: the cloud service answered HTTP " + reply.status()
                                        + " to a result; retrying every " + RESULT_RETRY_SECONDS + " s");
                    }
                    after.apply(RESULT_RETRY_SECONDS).execute(() -> deliver(id, body));
                    return null;
                });
    }

    private void problem(String kind, String message) {
        if (logged.add(kind)) LogUtil.warn(message);
    }

    /** A pending flag; null when its id, player or flag is not usable. */
    static @Nullable Flag flag(JsonObject flag) {
        String id = text(flag, "id");
        String player = text(flag, "player");
        String name = text(flag, "flag");
        if (id == null
                || !ID.matcher(id).matches()
                || player == null
                || !ID.matcher(player).matches()
                || name == null) return null;
        return new Flag(id, UUID.fromString(player), name);
    }

    private static @Nullable String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static long number(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) return fallback;
        return value.getAsLong();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private CompletableFuture<LabelClient.Reply> send(HttpRequest.Builder request) {
        return CompletableFuture.supplyAsync(
                        () -> http.sendAsync(request.build(), info -> new CappedBody(MAX_BODY)), executor)
                .thenCompose(Function.identity())
                .thenApply(response -> new LabelClient.Reply(response.statusCode(), response.body()));
    }

    /**
     * The ids this instance claimed, the oldest forgotten past {@code max}, and those whose result it still owes, kept
     * until the service takes it.
     */
    static final class Handled {
        private final Map<String, Boolean> ids;
        private final Set<String> owed = new HashSet<>();

        Handled(int max) {
            ids = new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > max;
                }
            };
        }

        /** Whether {@code id} was not handled yet; it is from here on. */
        synchronized boolean add(String id) {
            return !owed.contains(id) && ids.putIfAbsent(id, Boolean.TRUE) == null;
        }

        synchronized boolean contains(String id) {
            return ids.containsKey(id) || owed.contains(id);
        }

        /** Forgets a claim that was never raised. */
        synchronized void remove(String id) {
            ids.remove(id);
        }

        synchronized void owe(String id) {
            owed.add(id);
        }

        synchronized void paid(String id) {
            owed.remove(id);
        }

        synchronized boolean owes(String id) {
            return owed.contains(id);
        }
    }

    /** The configure generation: a poller claims and raises only while its own is the latest. */
    static final class Generation {
        private long latest;

        synchronized long next() {
            return ++latest;
        }

        synchronized boolean latest(long generation) {
            return generation == latest;
        }

        /** {@code action}'s answer while {@code generation} is the latest, no configure between; else false. */
        synchronized boolean whileLatest(long generation, BooleanSupplier action) {
            return generation == latest && action.getAsBoolean();
        }
    }

    /** The running platform: a player is online here while CultAC tracks them on a connection this instance holds. */
    static final class PlatformHost implements Host {
        @Override
        public boolean online(UUID player) {
            CultPlayer cult = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
            return cult != null && !cult.user.getCultConnection().disconnected();
        }

        @Override
        public boolean schedule(UUID player, Class<? extends CloudFlagCheck> type, Consumer<CloudFlagCheck> flag) {
            CultPlayer cult = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
            return cult != null && !cult.user.getCultConnection().disconnected() && schedule(cult, type, flag);
        }

        /** Runs {@code flag} with {@code player}'s check of {@code type} on the player's packet thread. */
        static boolean schedule(
                CultPlayer player, Class<? extends CloudFlagCheck> type, Consumer<CloudFlagCheck> flag) {
            CloudFlagCheck check = player.checkManager.getCheck(type);
            if (check == null) return false;
            Runnable task = () -> flag.accept(check);
            EventExecutor owner = player.user.getPacketExecutor();
            if (owner.inEventLoop()) {
                task.run();
            } else {
                owner.execute(task);
            }
            return true;
        }
    }
}
