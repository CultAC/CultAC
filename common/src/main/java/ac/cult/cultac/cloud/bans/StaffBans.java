package ac.cult.cultac.cloud.bans;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Runs the bans staff issue from the cloud panel with this server's own {@code cloud.staff-ban} commands, apart from
 * automatic punishments and observe-only. While the upload is configured and a command is set, it polls the cloud
 * service's pending bans, claims each one this instance should run, dispatches it as console and reports the result.
 * The key goes only into the {@code Authorization} header.
 */
public final class StaffBans {
    static final long DEFAULT_POLL_SECONDS = 10;
    static final long MIN_POLL_SECONDS = 5;
    static final long MAX_POLL_SECONDS = 60;
    /** How long to wait before asking a service without staff bans again. */
    static final long ABSENT_POLL_SECONDS = 300;

    static final long DEFAULT_GRACE_SECONDS = 15;
    /** How long a dispatch may wait for the server's thread before it counts as failed, inside the claim's lease. */
    static final Duration DISPATCH_TIMEOUT = Duration.ofSeconds(30);

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BODY = 256 << 10;
    static final int MAX_HANDLED = 1000;
    static final String DISPATCHED = "dispatched";

    /** Bumped on every configure, so only the latest poll chain keeps running. */
    private static final AtomicLong CHAIN = new AtomicLong();
    /** Ban ids this instance claimed, across reloads. */
    private static final Handled HANDLED = new Handled(MAX_HANDLED);

    private static final Pattern ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** The commands {@code cloud.staff-ban.command} and {@code cloud.staff-ban.temp-command}; empty never runs. */
    public record Commands(String command, String tempCommand) {
        public static final Commands NONE = new Commands("", "");

        public Commands {
            command = command == null ? "" : command.strip();
            tempCommand = tempCommand == null ? "" : tempCommand.strip();
        }

        public static Commands read(ConfigManager config) {
            return new Commands(
                    config.getStringElse("cloud.staff-ban.command", ""),
                    config.getStringElse("cloud.staff-ban.temp-command", ""));
        }

        boolean any() {
            return !command.isEmpty() || !tempCommand.isEmpty();
        }

        /** The command for {@code ban}: {@link #command} when it is permanent, else {@link #tempCommand}. */
        String of(Ban ban) {
            return ban.durationSeconds() == null ? command : tempCommand;
        }
    }

    /** One pending ban; a null {@code durationSeconds} is permanent, a zero {@code createdAtMillis} unknown. */
    public record Ban(
            String id,
            UUID player,
            @Nullable String name,
            String reason,
            String cheat,
            @Nullable String cheatName,
            @Nullable Long durationSeconds,
            String staff,
            long createdAtMillis,
            String link) {}

    /** Where bans run: whether a player is connected here, and the dispatch of a command as console. */
    interface Host {
        boolean online(UUID player);

        /** Completes once {@code command} ran, exceptionally when it threw. */
        CompletableFuture<Void> dispatch(String command, UUID player);
    }

    private final String base;
    private final String apiKey;
    private final String serverId;
    private final Commands commands;
    private final HttpClient http;
    private final Executor executor;
    private final Host host;
    private final LongSupplier clock;
    private final AtomicLong chains;
    private final Handled handled;
    private final LongFunction<Executor> after;
    /** The kinds of problem already logged by this poller. */
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    private long chain;

    StaffBans(
            String base,
            String apiKey,
            String serverId,
            Commands commands,
            HttpClient http,
            Executor executor,
            Host host,
            LongSupplier clock,
            AtomicLong chains,
            Handled handled,
            LongFunction<Executor> after) {
        this.base = base;
        this.apiKey = apiKey;
        this.serverId = serverId;
        this.commands = commands;
        this.http = http;
        this.executor = executor;
        this.host = host;
        this.clock = clock;
        this.chains = chains;
        this.handled = handled;
        this.after = after;
    }

    /**
     * Applies a configure: polling starts when the upload is configured, a command is set and this instance has a
     * {@code serverId}; any chain an earlier configure started stops.
     */
    public static void configure(CloudSettings settings, Commands commands, @Nullable String serverId) {
        if (!settings.enabled() || !commands.any()) {
            stop();
            return;
        }
        if (serverId == null) {
            stop();
            LogUtil.warn("Staff bans from the cloud panel are off: this instance has no server id; set cloud.proxy-id");
            return;
        }
        new StaffBans(
                        settings.url(),
                        settings.apiKey(),
                        serverId,
                        commands,
                        CloudHttp.HTTP,
                        CloudHttp.CALLS,
                        new PlatformHost(),
                        System::currentTimeMillis,
                        CHAIN,
                        HANDLED,
                        seconds -> CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS, CloudHttp.CALLS))
                .start();
    }

    /** Stops the running chain; a claimed ban still finishes. */
    public static void stop() {
        CHAIN.incrementAndGet();
    }

    void start() {
        chain = chains.incrementAndGet();
        poll();
    }

    private void poll() {
        if (chains.get() != chain) return;
        pollOnce()
                .whenComplete((next, error) ->
                        after.apply(error == null ? next : DEFAULT_POLL_SECONDS).execute(this::poll));
    }

    /** One round: fetches the pending bans and runs each this instance should; completes with the seconds to wait. */
    CompletableFuture<Long> pollOnce() {
        return send(request("/api/v1/bans/pending?server=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8))
                        .GET())
                .handle((reply, error) -> {
                    if (error != null) {
                        problem(
                                "pending " + LabelClient.unanswered(error),
                                "Staff bans: no answer from the cloud" + " service (" + LabelClient.unanswered(error)
                                        + ")");
                        return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
                    }
                    if (reply.status() == 404) {
                        if (logged.add("absent")) {
                            LogUtil.debug("Staff bans: the cloud service has none; asking again every "
                                    + ABSENT_POLL_SECONDS / 60 + " minutes");
                        }
                        return CompletableFuture.completedFuture(ABSENT_POLL_SECONDS);
                    }
                    if (reply.status() != 200) {
                        problem(
                                "pending " + reply.status(),
                                "Staff bans: the cloud service answered HTTP " + reply.status() + " for pending bans");
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
            problem("pending body", "Staff bans: the cloud service's pending bans are unreadable");
            return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
        }
        long next =
                Math.clamp(number(pending, "pollSeconds", DEFAULT_POLL_SECONDS), MIN_POLL_SECONDS, MAX_POLL_SECONDS);
        long grace = Math.max(0, number(pending, "graceSeconds", DEFAULT_GRACE_SECONDS));
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        JsonElement bans = pending.get("bans");
        if (bans != null && bans.isJsonArray()) {
            for (JsonElement item : bans.getAsJsonArray()) {
                Ban ban = item.isJsonObject() ? ban(item.getAsJsonObject()) : null;
                if (ban != null && due(ban, grace) && handled.add(ban.id())) runs.add(claim(ban));
            }
        }
        return CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).handle((done, error) -> next);
    }

    /** Whether {@code ban} has a command here and is this instance's to claim now. */
    private boolean due(Ban ban, long graceSeconds) {
        if (handled.contains(ban.id()) || commands.of(ban).isEmpty()) return false;
        return host.online(ban.player()) || clock.getAsLong() >= ban.createdAtMillis() + graceSeconds * 1000;
    }

    private CompletableFuture<Void> claim(Ban ban) {
        JsonObject body = new JsonObject();
        body.addProperty("server", serverId);
        return send(request("/api/v1/bans/" + ban.id() + "/claim")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())))
                .handle((reply, error) -> {
                    if (error != null) {
                        handled.remove(ban.id());
                        problem(
                                "claim " + LabelClient.unanswered(error),
                                "Staff bans: no answer from the cloud" + " service to a claim ("
                                        + LabelClient.unanswered(error) + ")");
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (reply.status() != 200) {
                        handled.remove(ban.id());
                        if (reply.status() != 409) {
                            problem(
                                    "claim " + reply.status(),
                                    "Staff bans: the cloud service answered HTTP " + reply.status() + " to a claim");
                        }
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return run(claimed(reply.body(), ban));
                })
                .thenCompose(Function.identity());
    }

    /** The ban a won claim answered with, else {@code pending}. */
    private static Ban claimed(String body, Ban pending) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("ban")) return pending;
            JsonElement ban = parsed.getAsJsonObject().get("ban");
            Ban answered = ban.isJsonObject() ? ban(ban.getAsJsonObject()) : null;
            return answered != null && answered.id().equals(pending.id()) ? answered : pending;
        } catch (JsonParseException | IllegalStateException unreadable) {
            return pending;
        }
    }

    private CompletableFuture<Void> run(Ban ban) {
        String command = BanCommand.fill(commands.of(ban), ban);
        CompletableFuture<Void> dispatched;
        try {
            dispatched = host.dispatch(command, ban.player());
        } catch (RuntimeException failure) {
            dispatched = CompletableFuture.failedFuture(failure);
        }
        return dispatched
                .orTimeout(DISPATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .handle((done, error) -> {
                    if (error == null) {
                        LogUtil.info("Ran staff ban " + ban.id() + " from the cloud panel: " + command);
                        return result(ban, true, DISPATCHED);
                    }
                    String why = why(error);
                    LogUtil.warn("Staff ban " + ban.id() + " from the cloud panel failed: " + why + ": " + command);
                    return result(ban, false, why);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<Void> result(Ban ban, boolean ok, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("server", serverId);
        body.addProperty("ok", ok);
        body.addProperty("message", LogThrottle.oneLine(message, BanCommand.MAX_TEXT));
        return send(request("/api/v1/bans/" + ban.id() + "/result")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())))
                .handle((reply, error) -> {
                    if (error != null) {
                        problem(
                                "result " + LabelClient.unanswered(error),
                                "Staff bans: no answer from the cloud" + " service to a result ("
                                        + LabelClient.unanswered(error) + ")");
                    } else if (reply.status() != 200) {
                        problem(
                                "result " + reply.status(),
                                "Staff bans: the cloud service answered HTTP " + reply.status() + " to a result");
                    }
                    return null;
                });
    }

    /** What a failed dispatch says: its exception's message, else the exception's type. */
    static String why(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof TimeoutException) {
            return "did not run within " + DISPATCH_TIMEOUT.toSeconds() + " s";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private void problem(String kind, String message) {
        if (logged.add(kind)) LogUtil.warn(message);
    }

    /** A pending ban; null when an id, the player or the duration is not usable. */
    static @Nullable Ban ban(JsonObject ban) {
        String id = text(ban, "id");
        String player = text(ban, "player");
        if (id == null
                || !ID.matcher(id).matches()
                || player == null
                || !ID.matcher(player).matches()) return null;
        Long duration;
        JsonElement seconds = ban.get("durationSeconds");
        if (seconds == null || seconds.isJsonNull()) {
            duration = null;
        } else if (seconds.isJsonPrimitive() && seconds.getAsJsonPrimitive().isNumber()) {
            duration = seconds.getAsLong();
            if (duration <= 0) return null;
        } else {
            return null;
        }
        return new Ban(
                id,
                UUID.fromString(player),
                text(ban, "name"),
                orEmpty(text(ban, "reason")),
                orEmpty(text(ban, "cheat")),
                text(ban, "cheatName"),
                duration,
                orEmpty(text(ban, "staff")),
                createdAt(ban.get("createdAt")),
                orEmpty(text(ban, "link")));
    }

    /** An ISO instant or epoch millis; 0 when it is neither. */
    private static long createdAt(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return 0;
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsLong();
        try {
            return Instant.parse(value.getAsString()).toEpochMilli();
        } catch (DateTimeParseException unreadable) {
            return 0;
        }
    }

    private static @Nullable String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static String orEmpty(@Nullable String text) {
        return text == null ? "" : text;
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

    /** The ids this instance claimed, the oldest forgotten past {@code max}. */
    static final class Handled {
        private final Map<String, Boolean> ids;

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
            return ids.putIfAbsent(id, Boolean.TRUE) == null;
        }

        synchronized boolean contains(String id) {
            return ids.containsKey(id);
        }

        synchronized void remove(String id) {
            ids.remove(id);
        }
    }

    /**
     * The running platform: a player is online here while this instance holds their connection, and a command runs as
     * console on the global scheduler (the main thread on a server), the player as subject when they are still here.
     */
    static final class PlatformHost implements Host {
        @Override
        public boolean online(UUID player) {
            User user = CultAPI.INSTANCE.getNetworkManager().getUser(player);
            return user != null && !user.getCultConnection().disconnected();
        }

        @Override
        public CompletableFuture<Void> dispatch(String command, UUID player) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            CultAPI api = CultAPI.INSTANCE;
            api.getScheduler().getGlobalRegionScheduler().run(api.getGrimPlugin(), () -> {
                try {
                    PlatformServer server = api.getPlatformServer();
                    server.dispatchCommand(
                            server.getConsoleSender(),
                            command,
                            api.getPlatformPlayerFactory().getFromUUID(player));
                    done.complete(null);
                } catch (RuntimeException failure) {
                    done.completeExceptionally(failure);
                }
            });
            return done;
        }
    }
}
