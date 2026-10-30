package ac.cult.cultac.cloud.bans;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.Generation;
import ac.cult.cultac.cloud.LeasedJobs;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.CommandOutcome;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Runs the bans staff issue from the cloud panel with this server's own {@code cloud.staff-ban} commands, apart from
 * automatic punishments and observe-only. While the upload is configured and a command is set, it polls the cloud
 * service's pending bans, claims each one this instance should run, dispatches it as console and reports the result.
 * The key goes only into the {@code Authorization} header.
 */
public final class StaffBans extends LeasedJobs<StaffBans.Ban> {
    static final long DEFAULT_GRACE_SECONDS = 15;
    /** How long a dispatch may wait for the server's thread before it is given up unrun, inside the claim's lease. */
    static final Duration DISPATCH_TIMEOUT = Duration.ofSeconds(30);

    static final CommandOutcome BUSY = new CommandOutcome(false, "not run: the server was busy");
    static final String FAILED = "the command failed";
    /** How Velocity and Bukkit start the message of the exception they wrap around what a command threw. */
    private static final Pattern WRAPPER = Pattern.compile("Unable to invoke command |Unhandled exception executing ");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Bumped on every configure, so only the latest poller claims and dispatches. */
    private static final Generation GENERATION = new Generation();
    /** Ban ids this instance claimed and results it still owes, across reloads. */
    private static final Handled HANDLED = new Handled(MAX_HANDLED);
    private static final Kind KIND = new Kind("Staff bans", "bans", "bans", "ban");

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
     * One pending ban; a null {@code durationSeconds} is permanent, a zero {@code createdAtMillis} unknown, a null
     * {@code cheat} a gameplay ban, and a null {@code staffUuid} or {@code evidence} unknown to the service.
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
            String link,
            @Nullable String staffUuid,
            @Nullable String evidence) {}

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

    private final Commands commands;
    private final Host host;
    private final LongSupplier clock;

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
        super(KIND, base, apiKey, serverId, http, executor, generation, handled, after);
        this.commands = commands;
        this.host = host;
        this.clock = clock;
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

    @Override
    protected String pendingQuery() {
        return "?durations=" + commands.durations();
    }

    @Override
    protected @Nullable Ban parse(JsonObject job) {
        return ban(job);
    }

    @Override
    protected String id(Ban ban) {
        return ban.id();
    }

    /** The bans with a command here whose player is online here, or whose grace since the ban was made ran out. */
    @Override
    protected Predicate<Ban> due(JsonObject pending) {
        long grace = Math.max(0, number(pending, "graceSeconds", DEFAULT_GRACE_SECONDS));
        return ban -> !commands.of(ban).isEmpty()
                && (host.online(ban.player()) || clock.getAsLong() >= ban.createdAtMillis() + grace * 1000);
    }

    @Override
    protected CompletableFuture<Void> run(Ban ban) {
        String command = BanCommand.fill(commands.of(ban), ban);
        Dispatch dispatch = new Dispatch();
        after(DISPATCH_TIMEOUT.toSeconds()).execute(() -> dispatch.abandon(BUSY));
        if (!latest()) {
            dispatch.abandon(null);
        } else {
            try {
                host.schedule(() -> {
                    if (whileLatest(dispatch::start)) {
                        dispatch.finish(command, ban.player(), host);
                    } else {
                        dispatch.abandon(null);
                    }
                });
            } catch (RejectedExecutionException stopped) {
                dispatch.abandon(null);
            } catch (RuntimeException failure) {
                dispatch.fail(failure);
            }
        }
        return dispatch.outcome
                .handle((outcome, error) -> {
                    if (error == null && outcome == null) {
                        release(ban);
                        LogUtil.info("Staff ban " + ban.id() + " from the cloud panel was not run: CultAC stopped or"
                                + " reloaded; it can be claimed again once its lease runs out");
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (error == null && outcome.ok()) {
                        LogUtil.info("Ran staff ban " + ban.id() + " from the cloud panel (" + outcome.message() + "): "
                                + command);
                        return report(ban, true, LogThrottle.oneLine(outcome.message(), BanCommand.MAX_TEXT));
                    }
                    String why = error == null ? outcome.message() : why(error, command);
                    LogUtil.warn("Staff ban " + ban.id() + " from the cloud panel failed: " + why + ": " + command);
                    return report(ban, false, LogThrottle.oneLine(why, BanCommand.MAX_TEXT));
                })
                .thenCompose(Function.identity());
    }

    /**
     * What a failed dispatch of {@code command} says: the message of the innermost cause that has its own, since
     * platforms wrap what the command threw; else {@link #FAILED}. A message that only repeats its cause, as a wrapper
     * made from the cause alone has, is not its own, and neither is one that names the command, which may carry
     * values the service must not get back.
     */
    static String why(Throwable error, String command) {
        String spaced = WHITESPACE.matcher(command.strip()).replaceAll(" ");
        String why = FAILED;
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            Throwable inner = cause.getCause();
            if (message == null
                    || message.isBlank()
                    || (inner != null && message.equals(inner.toString()))
                    || (!spaced.isEmpty() && (message.contains(command) || message.contains(spaced)))
                    || WRAPPER.matcher(message).lookingAt()) continue;
            why = message;
        }
        return why;
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
                orEmpty(text(ban, "link")),
                string(ban, "staffUuid"),
                string(ban, "evidence"));
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

    /** {@code key}'s value when it is a JSON string; null for any other type. */
    private static @Nullable String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static String orEmpty(@Nullable String text) {
        return text == null ? "" : text;
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
