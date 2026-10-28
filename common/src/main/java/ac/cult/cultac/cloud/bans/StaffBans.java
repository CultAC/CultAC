package ac.cult.cultac.cloud.bans;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.CommandOutcome;
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
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
    /** How long a dispatch may wait for the server's thread before it is given up unrun, inside the claim's lease. */
    static final Duration DISPATCH_TIMEOUT = Duration.ofSeconds(30);

    static final long RESULT_RETRY_SECONDS = 10;

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BODY = 256 << 10;
    static final int MAX_HANDLED = 1000;
    static final CommandOutcome BUSY = new CommandOutcome(false, "not run: the server was busy");

    /** Bumped on every configure, so only the latest poller claims and dispatches. */
    private static final Generation GENERATION = new Generation();
    /** Ban ids this instance claimed and results it still owes, across reloads. */
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

        /** The bans these commands can run, as the pending query's {@code durations}. */
        String durations() {
            if (command.isEmpty()) return "timed";
            return tempCommand.isEmpty() ? "permanent" : "all";
        }
    }

    /**
     * One pending ban; a null {@code durationSeconds} is permanent, a zero {@code createdAtMillis} unknown, and a null
     * {@code cheat} a gameplay ban.
     */
    public record Ban(
            String id,
            UUID player,
            @Nullable String name,
            String reason,
            @Nullable String cheat,
            @Nullable String cheatName,
            @Nullable Long durationSeconds,
            String staff,
            long createdAtMillis,
            String link) {}

    /** Where bans run: whether a player is connected here, and the dispatch of a command as console. */
    interface Host {
        boolean online(UUID player);

        /** Runs {@code task} on the thread commands run on. */
        void schedule(Runnable task);

        /**
         * Dispatches {@code command} from a {@link #schedule} task; completes with what the platform reports,
         * exceptionally when it threw.
         */
        CompletableFuture<CommandOutcome> dispatch(String command, UUID player);
    }

    private final String base;
    private final String apiKey;
    private final String serverId;
    private final Commands commands;
    private final HttpClient http;
    private final Executor executor;
    private final Host host;
    private final LongSupplier clock;
    private final Generation generation;
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
            Generation generation,
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
        this.generation = generation;
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
                        GENERATION,
                        HANDLED,
                        seconds -> CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS, CloudHttp.CALLS))
                .start();
    }

    /**
     * Stops the running poller: it claims and dispatches nothing more, a claim not yet dispatched is let go for its
     * lease to run out, and results already owed are still delivered.
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

    /** One round: fetches the pending bans and runs each this instance should; completes with the seconds to wait. */
    CompletableFuture<Long> pollOnce() {
        return send(request("/api/v1/bans/pending?server=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8)
                                + "&durations=" + commands.durations())
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
                if (ban != null && generation.whileLatest(chain, () -> due(ban, grace) && handled.add(ban.id()))) {
                    runs.add(claim(ban));
                }
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
        Dispatch dispatch = new Dispatch();
        after.apply(DISPATCH_TIMEOUT.toSeconds()).execute(() -> dispatch.abandon(BUSY));
        try {
            host.schedule(() -> {
                if (generation.whileLatest(chain, dispatch::start)) {
                    dispatch.finish(command, ban.player(), host);
                } else {
                    dispatch.abandon(null);
                }
            });
        } catch (RuntimeException failure) {
            dispatch.fail(failure);
        }
        return dispatch.outcome
                .handle((outcome, error) -> {
                    if (error == null && outcome == null) {
                        handled.remove(ban.id());
                        LogUtil.info("Staff ban " + ban.id() + " from the cloud panel was not run: CultAC reloaded;"
                                + " it can be claimed again once its lease runs out");
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (error == null && outcome.ok()) {
                        LogUtil.info("Ran staff ban " + ban.id() + " from the cloud panel (" + outcome.message() + "): "
                                + command);
                        return report(ban, true, outcome.message());
                    }
                    String why = error == null ? outcome.message() : why(error);
                    LogUtil.warn("Staff ban " + ban.id() + " from the cloud panel failed: " + why + ": " + command);
                    return report(ban, false, why);
                })
                .thenCompose(Function.identity());
    }

    /** Owes the service {@code ban}'s result until it takes it; completes after the first attempt. */
    private CompletableFuture<Void> report(Ban ban, boolean ok, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("server", serverId);
        body.addProperty("ok", ok);
        body.addProperty("message", LogThrottle.oneLine(message, BanCommand.MAX_TEXT));
        handled.owe(ban.id());
        return deliver(ban.id(), body.toString());
    }

    /**
     * Posts a result, again every {@link #RESULT_RETRY_SECONDS} until the service takes it (200) or says it is not this
     * instance's to give (404, 409), whatever configure comes meanwhile.
     */
    private CompletableFuture<Void> deliver(String id, String body) {
        return send(request("/api/v1/bans/" + id + "/result").POST(HttpRequest.BodyPublishers.ofString(body)))
                .handle((reply, error) -> {
                    if (error == null && (reply.status() == 200 || reply.status() == 404 || reply.status() == 409)) {
                        handled.paid(id);
                        if (reply.status() != 200) {
                            problem(
                                    "result " + reply.status(),
                                    "Staff bans: the cloud service answered HTTP " + reply.status() + " to a result");
                        }
                        return null;
                    }
                    if (error != null) {
                        problem(
                                "result " + LabelClient.unanswered(error),
                                "Staff bans: no answer from the cloud service to a result ("
                                        + LabelClient.unanswered(error) + "); retrying every "
                                        + RESULT_RETRY_SECONDS + " s");
                    } else {
                        problem(
                                "result " + reply.status(),
                                "Staff bans: the cloud service answered HTTP " + reply.status()
                                        + " to a result; retrying every " + RESULT_RETRY_SECONDS + " s");
                    }
                    after.apply(RESULT_RETRY_SECONDS).execute(() -> deliver(id, body));
                    return null;
                });
    }

    /** What a failed dispatch says: its exception's message, else the exception's type. */
    static String why(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
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
                text(ban, "cheat"),
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

        /** Forgets a claim that was never dispatched. */
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

    /** The configure generation: a poller claims and dispatches only while its own is the latest. */
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

    /**
     * One claimed ban's command: QUEUED until the server's thread starts it (RUNNING, then DONE) or it is given up
     * unrun (ABANDONED), so a dispatch that waited too long never runs late.
     */
    static final class Dispatch {
        enum State {
            QUEUED,
            RUNNING,
            DONE,
            ABANDONED
        }

        private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
        /** What the platform reported; null when the dispatch was let go unrun for a reload. */
        final CompletableFuture<@Nullable CommandOutcome> outcome = new CompletableFuture<>();

        boolean start() {
            return state.compareAndSet(State.QUEUED, State.RUNNING);
        }

        /** Waits for a started dispatch however long the platform takes. */
        void finish(String command, UUID player, Host host) {
            CompletableFuture<CommandOutcome> ran;
            try {
                ran = host.dispatch(command, player);
            } catch (RuntimeException failure) {
                ran = CompletableFuture.failedFuture(failure);
            }
            ran.whenComplete((reported, error) -> {
                state.set(State.DONE);
                if (error != null) {
                    outcome.completeExceptionally(error);
                } else {
                    outcome.complete(reported);
                }
            });
        }

        void abandon(@Nullable CommandOutcome why) {
            if (state.compareAndSet(State.QUEUED, State.ABANDONED)) outcome.complete(why);
        }

        void fail(Throwable failure) {
            if (state.compareAndSet(State.QUEUED, State.ABANDONED)) outcome.completeExceptionally(failure);
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
        public void schedule(Runnable task) {
            CultAPI api = CultAPI.INSTANCE;
            api.getScheduler().getGlobalRegionScheduler().run(api.getGrimPlugin(), task);
        }

        @Override
        public CompletableFuture<CommandOutcome> dispatch(String command, UUID player) {
            CultAPI api = CultAPI.INSTANCE;
            PlatformServer server = api.getPlatformServer();
            return server.runCommand(
                    server.getConsoleSender(),
                    command,
                    api.getPlatformPlayerFactory().getFromUUID(player));
        }
    }
}
