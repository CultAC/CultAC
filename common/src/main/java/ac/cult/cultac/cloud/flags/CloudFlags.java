package ac.cult.cultac.cloud.flags;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.cloud.CloudFlagCheck;
import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.Generation;
import ac.cult.cultac.cloud.LeasedJobs;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.gson.JsonObject;
import io.netty.util.concurrent.EventExecutor;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Raises the cloud flags the service sends: each is one violation of the {@link CloudFlagCheck} of the broad category
 * the service flags a player under, so this server's punishment groups decide what follows. While the upload is
 * configured it polls the service's pending flags, claims each one whose player is online on this instance, raises it
 * on the player's packet thread and reports the result. The key goes only into the {@code Authorization} header.
 */
public final class CloudFlags extends LeasedJobs<CloudFlags.Flag> {
    /** How long a claimed flag may wait for the player's packet thread before it is let go unraised. */
    static final Duration RAISE_TIMEOUT = Duration.ofSeconds(30);

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
    private static final Kind KIND = new Kind("Cloud flags", "cloud-flags", "flags", "flag");

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

    private final Host host;

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
        super(KIND, base, apiKey, serverId, http, executor, generation, handled, after);
        this.host = host;
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

    @Override
    protected String pendingQuery() {
        return "?server=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
    }

    @Override
    protected @Nullable Flag parse(JsonObject job) {
        return flag(job);
    }

    @Override
    protected String id(Flag flag) {
        return flag.id();
    }

    /** The flags whose player is online here. */
    @Override
    protected Predicate<Flag> due(JsonObject pending) {
        return flag -> host.online(flag.player());
    }

    @Override
    protected boolean answers(Flag answered, Flag pending) {
        return answered.id().equals(pending.id()) && answered.player().equals(pending.player());
    }

    @Override
    protected CompletableFuture<Void> run(Flag flag) {
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
        after(RAISE_TIMEOUT.toSeconds()).execute(() -> {
            if (started.compareAndSet(false, true)) raised.complete(false);
        });
        boolean queued;
        try {
            queued = latest()
                    && host.schedule(flag.player(), type, check -> {
                        if (!whileLatest(() -> started.compareAndSet(false, true))) {
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
                        release(flag);
                        LogUtil.info("Cloud flag " + flag.id() + " was not raised: the player left, or CultAC stopped"
                                + " or reloaded; it can be claimed again once its lease runs out");
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (refused.get()) return report(flag, false, REFUSED);
                    return report(flag, true, RAISED);
                })
                .thenCompose(Function.identity());
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
