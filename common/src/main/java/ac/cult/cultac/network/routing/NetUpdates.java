package ac.cult.cultac.network.routing;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.routing.NetCommands.Options;
import ac.cult.cultac.network.routing.NetCommands.Reply;
import ac.cult.cultac.network.routing.NetCommands.Spec;
import ac.cult.cultac.network.routing.NetCommands.Target;
import ac.cult.cultac.network.routing.NetCommands.Text;
import ac.cult.cultac.network.routing.NetCommands.Webhook;
import ac.cult.cultac.platform.api.PlatformPlugin;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.api.ResourceScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /cult net <target> update <build>|latest [channel] [--canary]}: installs one signed numbered build on every
 * proxy the target names, as {@code /cult update <build>} does there, through the loader's
 * {@link HostContext#installBuild}. The issuer looks {@code latest} up once, by default for the channel of its own build,
 * and sends the build number with the channel it must be signed for; when it cannot look it up, it sends
 * {@code latest} and each proxy looks it up. A proxy answers {@value #ACCEPTED} at once and its install line when the
 * install ends; other instances answer {@value #NOT_PROXY}, and a proxy whose loader cannot install builds answers
 * {@value #OLD_LOADER}. With {@code --canary} the targeted proxy with the lowest id other than the issuer installs
 * first, and the others are sent the command only once it activated the build. Each stage waits
 * {@value #ACCEPT_MILLIS} ms for every instance to accept and at most {@value #STAGE_MILLIS} ms for the install lines.
 * A targeted issuer installs the build on itself only after the last stage's summary, as that install retires the
 * engine generation showing the stages.
 */
final class NetUpdates {
    static final String NAME = "update";
    static final String LATEST = "latest";
    static final String CANARY = "--canary";
    static final long ACCEPT_MILLIS = 10_000;
    static final long STAGE_MILLIS = 11 * 60_000;

    static final String ACCEPTED = "update-accepted";
    static final String DONE = "update-done";
    static final String FAILED = "update-failed";
    static final String NOT_PROXY = "update-not-proxy";
    static final String OLD_LOADER = "update-old-loader";

    static final Spec SPEC = new Spec(NAME, STAGE_MILLIS, false, NetUpdates::valid);

    /** What each of the update's result codes reads as. */
    static final Map<String, Text> REPLIES = Map.of(
            ACCEPTED,
            new Text("net-update-accepted", "&7updating to %build%: downloading and verifying..."),
            DONE,
            new Text("net-update-done", "&a%line%"),
            FAILED,
            new Text("net-update-failed", "&c%line%"),
            NOT_PROXY,
            new Text("net-update-not-proxy", "&7skipped: only proxies install builds"),
            OLD_LOADER,
            new Text("net-update-old-loader", "&cneeds a proxy restart (loader %from% → %to%)"));

    private static final Text CANARY_FIRST = new Text("net-update-canary", "%prefix% &7canary %instance% first...");
    private static final Text CANARY_FAILED = new Text(
            "net-update-canary-failed",
            "%prefix% &ccanary %instance% did not activate %build%; the rest were not updated");
    private static final Text SELF_LAST = new Text("net-update-self", "%prefix% &7%instance% updates itself last");

    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,18}");
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9._/-]{1,200}");
    private static final Pattern VERSION_NUMBER = Pattern.compile("-b([0-9]{1,18})-");
    private static final Pattern VERSION_CHANNEL = Pattern.compile("-b[0-9]{1,18}-([A-Za-z0-9._-]+)-[0-9a-f]{7,40}$");

    /** How this instance's loader installs numbered builds. */
    interface Builds {
        /** Whether the loader installs builds for the engine; loaders built before {@link HostContext#installBuild} do not. */
        boolean installs();

        /** The loader's version, as its plugin descriptor names it. */
        String loader();

        /** As {@link HostContext#installBuild}; called only while {@link #installs}. */
        CompletionStage<String> install(long number, @Nullable String branch, String requestedBy);

        /** As {@link HostContext#latestBuild}; called only while {@link #installs}. */
        CompletionStage<Long> latest(String channel);

        /** Keeps this engine generation from stopping until the returned action runs. */
        default Runnable hold() {
            return () -> {};
        }
    }

    /** The builds of the loader this engine runs under. */
    static final Builds CULT = new HostBuilds(HostContext.class, RuntimeEnvironment::host, () -> {
        PlatformPlugin loader = CultAPI.INSTANCE.getPluginManager().getPlugin("cultac");
        return loader == null ? "unknown" : loader.getVersion();
    });

    /**
     * The builds of {@code host}, whose type is {@code api} as the loader defines it.
     * TODO(cultLoaderVersion>6): loaders of version 6 built before installBuild lack it and latestBuild, so both are
     * looked up before either is called.
     */
    static final class HostBuilds implements Builds {
        private final boolean installs;
        private final Supplier<@Nullable HostContext> host;
        private final Supplier<String> loader;

        HostBuilds(Class<?> api, Supplier<@Nullable HostContext> host, Supplier<String> loader) {
            this.installs = NetUpdates.installs(api);
            this.host = host;
            this.loader = loader;
        }

        @Override
        public boolean installs() {
            return installs;
        }

        @Override
        public String loader() {
            return loader.get();
        }

        @Override
        public CompletionStage<String> install(long number, @Nullable String branch, String requestedBy) {
            HostContext context = host.get();
            return context == null
                    ? CompletableFuture.completedFuture("update refused: this server does not install builds")
                    : context.installBuild(number, branch, requestedBy);
        }

        @Override
        public CompletionStage<Long> latest(String channel) {
            HostContext context = host.get();
            return context == null
                    ? CompletableFuture.failedFuture(
                            new UnsupportedOperationException("this server does not list builds"))
                    : context.latestBuild(channel);
        }

        @Override
        public Runnable hold() {
            HostContext context = host.get();
            if (context == null) return () -> {};
            try {
                ResourceScope.Lease lease = context.resources().acquire();
                return lease::close;
            } catch (RuntimeException ended) {
                return () -> {};
            }
        }
    }

    /** Whether {@code api} declares {@link HostContext#installBuild} and {@link HostContext#latestBuild}. */
    static boolean installs(Class<?> api) {
        try {
            api.getMethod("installBuild", long.class, String.class, String.class);
            api.getMethod("latestBuild", String.class);
            return true;
        } catch (NoSuchMethodException older) {
            return false;
        }
    }

    /** {@code args} as /cult net takes them; null when they are not {@code <N>|latest [channel] [--canary]}. */
    record Wanted(@Nullable Long number, @Nullable String channel, boolean canary) {
        static @Nullable Wanted parse(List<String> args) {
            boolean canary = false;
            List<String> rest = new ArrayList<>();
            for (String arg : args) {
                if (!arg.equalsIgnoreCase(CANARY)) rest.add(arg);
                else if (canary) return null;
                else canary = true;
            }
            if (rest.isEmpty() || rest.size() > 2) return null;
            String channel = rest.size() == 2 ? rest.get(1) : null;
            if (channel != null && !CHANNEL.matcher(channel).matches()) return null;
            String build = rest.get(0);
            if (build.equalsIgnoreCase(LATEST)) return new Wanted(null, channel, canary);
            if (!NUMBER.matcher(build).matches()) return null;
            long number = Long.parseLong(build);
            return number > 0 ? new Wanted(number, channel, canary) : null;
        }

        /** The arguments sent to the targets. */
        List<String> sent() {
            List<String> sent = new ArrayList<>();
            sent.add(number == null ? LATEST : String.valueOf(number));
            if (channel != null) sent.add(channel);
            return List.copyOf(sent);
        }

        /** How the issuer names the build. */
        String build() {
            return number != null ? "b" + number : channel == null ? LATEST : LATEST + " " + channel;
        }
    }

    static boolean valid(List<String> args) {
        return Wanted.parse(args) != null;
    }

    /** {@code b<N>} for a numbered version, else the version without its runtime digest. */
    static String label(String version) {
        String plain = version.split("@", 2)[0];
        Matcher number = VERSION_NUMBER.matcher(plain);
        return number.find() ? "b" + number.group(1) : plain;
    }

    /** The channel a numbered version was published on; null for any other version. */
    static @Nullable String channel(String version) {
        Matcher channel = VERSION_CHANNEL.matcher(version.split("@", 2)[0]);
        return channel.find() ? channel.group(1) : null;
    }

    private final NetworkService service;
    private final NetCommands.Local local;
    /** Posts a line to {@code update.webhook} off the caller's thread. */
    private final Consumer<String> webhookPost;
    /** This generation's stages awaiting answers, by command id. */
    private final Map<String, Stage> stages = new ConcurrentHashMap<>();
    /** One update in flight per sender. */
    private final Set<String> senders = ConcurrentHashMap.newKeySet();
    /** Shows the answers and summaries, and ends each stage's accept window and deadline. */
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "CultAC network updates");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean closed;

    NetUpdates(NetworkService service, NetCommands.Local local, Consumer<String> webhookPost) {
        this.service = service;
        this.local = local;
        this.webhookPost = webhookPost;
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    void register() {
        service.on(NetCommands.REPLY, this::answered);
    }

    /** Ends every stage awaiting answers; the service is stopping. */
    void close() {
        closed = true;
        for (Stage stage : List.copyOf(stages.values())) stage.finish();
        worker.shutdown();
    }

    private void answered(Envelope envelope) {
        Stage stage;
        Reply reply;
        try {
            stage = stages.get(envelope.body().get("id").getAsString());
            if (stage == null) return;
            reply = Reply.parse(envelope.body());
        } catch (RuntimeException malformed) {
            return;
        }
        String from = envelope.from();
        try {
            worker.execute(() -> stage.accept(from, reply));
        } catch (RejectedExecutionException stopped) {
            // The service is stopping and close ends this stage.
        }
    }

    /**
     * Sends the update {@code args} ask for to {@code expected}, the instances the target names, of which
     * {@code proxies} are proxies and {@code older} run builds without network commands. Completes once the last stage's
     * summary and then, when the target names this instance, its own install line were shown.
     */
    CompletableFuture<Void> issue(
            Sender sender,
            Target target,
            List<String> args,
            Set<String> expected,
            Set<String> proxies,
            Set<String> older) {
        Wanted wanted = Wanted.parse(args);
        if (wanted == null) {
            NetCommands.usage(sender);
            return CompletableFuture.completedFuture(null);
        }
        UUID senderId = sender.getUniqueId() == null ? Sender.CONSOLE_UUID : sender.getUniqueId();
        String key = senderId.toString();
        if (closed || !senders.add(key)) {
            sender.sendMessage(NetCommands.BUSY.render(sender, Map.of()));
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        resolve(wanted)
                .thenCompose(sent -> stages(sender, senderId, target, wanted.canary(), sent, expected, proxies, older))
                .whenComplete((release, failure) -> {
                    senders.remove(key);
                    if (failure != null) LogUtil.warn("A network update failed", failure);
                    done.complete(null);
                    if (release != null) release.run();
                });
        return done;
    }

    /** The arguments the targets get: the build number with the channel it must be signed for, else as typed. */
    private CompletionStage<Wanted> resolve(Wanted wanted) {
        String channel;
        try {
            channel = wanted.channel() != null ? wanted.channel() : channel(local.build());
        } catch (RuntimeException unknown) {
            channel = null;
        }
        Wanted latest = new Wanted(null, channel, wanted.canary());
        if (wanted.number() != null) return CompletableFuture.completedFuture(wanted);
        Builds builds = local.builds();
        if (channel == null || !builds.installs()) return CompletableFuture.completedFuture(latest);
        String signed = channel;
        try {
            return builds.latest(channel)
                    .handle((number, failure) -> failure == null && number != null && number > 0
                            ? new Wanted(number, signed, wanted.canary())
                            : latest);
        } catch (RuntimeException | LinkageError unlisted) {
            return CompletableFuture.completedFuture(latest);
        }
    }

    private static final Runnable NO_HOLD = () -> {};

    /** Completes with the release of this generation's hold, which the run takes when it installs here. */
    private CompletionStage<Runnable> stages(
            Sender sender,
            UUID senderId,
            Target target,
            boolean canaryFirst,
            Wanted sent,
            Set<String> expected,
            Set<String> proxies,
            Set<String> older) {
        String self = service.instanceId();
        boolean here = expected.contains(self);
        Set<String> others = new TreeSet<>(expected);
        others.remove(self);
        TreeSet<String> candidates = new TreeSet<>(proxies);
        candidates.remove(self);
        String canary = canaryFirst && !candidates.isEmpty() ? candidates.first() : null;
        if (here && others.isEmpty()) {
            older(sender, older);
            return self(sender, senderId, target, sent, UUID.randomUUID().toString(), true);
        }
        if (here && proxies.contains(self)) sender.sendMessage(SELF_LAST.render(sender, Map.of("instance", self)));
        if (canary == null) {
            Stage only = new Stage(sender, senderId, target, sent.sent(), others, older, null);
            return only.start().thenCompose(finals -> last(only, sent, here));
        }
        sender.sendMessage(CANARY_FIRST.render(sender, Map.of("instance", canary)));
        Stage first = new Stage(sender, senderId, target, sent.sent(), Set.of(canary), Set.of(), "canary");
        return first.start().thenCompose(finals -> {
            if (closed) return CompletableFuture.completedFuture(NO_HOLD);
            Reply reply = finals.get(canary);
            if (reply == null || !reply.ok() || !DONE.equals(reply.code())) {
                String build = reply == null ? null : reply.params().get("build");
                sender.sendMessage(CANARY_FAILED.render(
                        sender, Map.of("instance", canary, "build", build == null ? sent.build() : build)));
                first.post(first.logged("canary failed; the rest were not sent the update"));
                return CompletableFuture.completedFuture(NO_HOLD);
            }
            Wanted installed = activated(reply, sent);
            Set<String> rest = new TreeSet<>(others);
            rest.remove(canary);
            if (rest.isEmpty()) {
                older(sender, older);
                return last(first, installed, here);
            }
            Stage remaining = new Stage(sender, senderId, target, installed.sent(), rest, older, "rest");
            return remaining.start().thenCompose(ignored -> last(remaining, installed, here));
        });
    }

    /** Posts the summary of {@code stage}, the run's last, then installs {@code sent} on this instance when targeted. */
    private CompletionStage<Runnable> last(Stage stage, Wanted sent, boolean here) {
        stage.post(stage.summary);
        return here
                ? self(stage.sender, stage.senderId, stage.target, sent, stage.id, false)
                : CompletableFuture.completedFuture(NO_HOLD);
    }

    private static void older(Sender sender, Set<String> older) {
        if (!older.isEmpty())
            sender.sendMessage(NetCommands.OLDER.render(sender, Map.of("instances", String.join(", ", older))));
    }

    /**
     * Installs {@code sent} on this instance as a receiving proxy would and shows {@code sender} its answers, audited
     * under the run's {@code id}; {@code only} when this instance is the run's only target. Completes with the release
     * of the hold on this generation, which the caller runs once the run has completed.
     */
    private CompletionStage<Runnable> self(
            Sender sender, UUID senderId, Target target, Wanted sent, String id, boolean only) {
        if (closed) return CompletableFuture.completedFuture(NO_HOLD);
        String self = service.instanceId();
        List<String> args = sent.sent();
        Runnable release = local.builds().hold();
        Webhook webhook = Webhook.NONE;
        CompletionStage<Reply> result;
        try {
            Options options = local.options();
            webhook = options.webhook();
            if (!options.enabled())
                result = CompletableFuture.completedFuture(Reply.of(false, "refused-disabled", Map.of()));
            else if (!options.accept().contains(NAME))
                result =
                        CompletableFuture.completedFuture(Reply.of(false, "refused-not-accepted", Map.of("cmd", NAME)));
            else
                result = install(
                        args,
                        sender.getName() + " from " + self,
                        accepted -> sender.sendMessage(NetCommands.line(sender, self, accepted)));
        } catch (RuntimeException | LinkageError failure) {
            result = CompletableFuture.completedFuture(Reply.failed(failure));
        }
        boolean posted = webhook == Webhook.EVERY || (only && webhook == Webhook.SUMMARY);
        return result.handle((reply, failure) -> {
            try {
                Reply answer = failure == null ? reply : Reply.failed(failure);
                sender.sendMessage(NetCommands.line(sender, self, answer));
                String line = NetCommands.audit(
                        id, sender.getName(), senderId, sender.isConsole(), self, target, NAME, args, answer.code());
                LogUtil.info(line);
                if (posted) post(line);
            } catch (RuntimeException | Error shown) {
                release.run();
                throw shown;
            }
            return release;
        });
    }

    private void post(String line) {
        webhookPost.accept(line);
    }

    /** What the rest are sent once the canary activated a build: its number when it looked {@code latest} up itself. */
    private static Wanted activated(Reply reply, Wanted sent) {
        String build = reply.params().get("build");
        if (sent.number() != null || build == null || !build.startsWith("b")) return sent;
        Wanted installed = Wanted.parse(List.of(build.substring(1)));
        if (installed == null) return sent;
        String channel = reply.params().getOrDefault("channel", sent.channel());
        return new Wanted(installed.number(), channel, false);
    }

    /**
     * Runs a received update here: answers {@value #ACCEPTED} to {@code origin} at once, then completes with the install
     * line once the loader's install ends. {@code by} is the sender's name.
     */
    CompletionStage<Reply> run(String id, String origin, String by, List<String> args) {
        return install(
                args, by + " from " + origin, accepted -> service.send(origin, NetCommands.REPLY, accepted.toJson(id)));
    }

    /**
     * Installs the build {@code args} name here for {@code requestedBy}: hands {@code accepted} the non-final
     * {@value #ACCEPTED} answer once it starts, then completes with the install line once the loader's install ends.
     */
    private CompletionStage<Reply> install(List<String> args, String requestedBy, Consumer<Reply> accepted) {
        if (!service.proxy()) return CompletableFuture.completedFuture(Reply.of(false, NOT_PROXY, Map.of()));
        Builds builds = local.builds();
        String engine = local.build();
        if (!builds.installs())
            return CompletableFuture.completedFuture(
                    Reply.of(false, OLD_LOADER, Map.of("from", label(builds.loader()), "to", label(engine))));
        Wanted wanted = Wanted.parse(args);
        if (wanted == null)
            return CompletableFuture.completedFuture(Reply.of(false, "refused-invalid", Map.of("cmd", NAME)));
        String channel = wanted.number() == null && wanted.channel() == null ? channel(engine) : wanted.channel();
        if (wanted.number() == null && channel == null)
            return CompletableFuture.completedFuture(refused("this build has no channel; use update latest <channel>"));
        Wanted target = new Wanted(wanted.number(), channel, false);
        Reply started = Reply.of(true, ACCEPTED, Map.of("build", target.build()));
        accepted.accept(new Reply(false, true, ACCEPTED, started.params(), started.text()));
        CompletableFuture<Reply> result = new CompletableFuture<>();
        Runnable release = builds.hold();
        try {
            CompletionStage<Long> number = target.number() != null
                    ? CompletableFuture.completedFuture(target.number())
                    : builds.latest(channel);
            number.thenCompose(build -> builds.install(build, channel, requestedBy)
                            .thenApply(line -> installed(build, channel, line)))
                    .whenComplete((reply, failure) -> {
                        try {
                            result.complete(failure == null ? reply : refused(failure));
                        } finally {
                            release.run();
                        }
                    });
        } catch (RuntimeException | LinkageError failure) {
            release.run();
            result.complete(refused(failure));
        }
        return result;
    }

    /** The answer for the loader's install {@code line} of build {@code number}. */
    static Reply installed(long number, @Nullable String channel, String line) {
        String already = "already on b" + number;
        boolean activated =
                line.contains(" → b" + number + " (") || line.equals(already) || line.startsWith(already + ";");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("build", "b" + number);
        params.put("line", line);
        if (channel != null) params.put("channel", channel);
        return Reply.of(activated, activated ? DONE : FAILED, params);
    }

    private static Reply refused(String reason) {
        return Reply.of(false, FAILED, Map.of("line", "update refused: " + reason));
    }

    private static Reply refused(Throwable failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return refused(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    /** One round of an update: the command sent to {@code expected}, each answer shown as it arrives, then a summary. */
    private final class Stage {
        final String id = UUID.randomUUID().toString();
        final Sender sender;
        final UUID senderId;
        final Target target;
        final List<String> args;
        final Set<String> expected;
        final Set<String> older;
        /** {@code canary} or {@code rest}; null for the only stage. */
        final @Nullable String name;

        final Webhook webhook;
        final CompletableFuture<Map<String, Reply>> done = new CompletableFuture<>();
        /** The audit line of the summary, once {@link #done}; null when the stage ended unsent. */
        @Nullable
        String summary;
        /** Guarded by {@code this}. */
        final Set<String> heard = new TreeSet<>();
        /** Guarded by {@code this}. */
        final Map<String, Reply> finals = new TreeMap<>();
        /** Guarded by {@code this}. */
        private boolean accepting = true;
        /** Guarded by {@code this}. */
        private boolean finished;
        /** Guarded by {@code this}. */
        private @Nullable ScheduledFuture<?> window;
        /** Guarded by {@code this}. */
        private @Nullable ScheduledFuture<?> deadline;

        Stage(
                Sender sender,
                UUID senderId,
                Target target,
                List<String> args,
                Set<String> expected,
                Set<String> older,
                @Nullable String name) {
            this.sender = sender;
            this.senderId = senderId;
            this.target = target;
            this.args = args;
            this.expected = Set.copyOf(expected);
            this.older = Set.copyOf(older);
            this.name = name;
            Webhook mode;
            try {
                mode = local.options().webhook();
            } catch (RuntimeException unreadable) {
                mode = Webhook.NONE;
            }
            this.webhook = mode;
        }

        CompletableFuture<Map<String, Reply>> start() {
            if (!expected.isEmpty()) {
                if (closed) return abandon();
                stages.put(id, this);
                try {
                    synchronized (this) {
                        window = worker.schedule(this::acceptanceOver, ACCEPT_MILLIS, TimeUnit.MILLISECONDS);
                        deadline = worker.schedule(this::finish, STAGE_MILLIS, TimeUnit.MILLISECONDS);
                    }
                } catch (RejectedExecutionException stopped) {
                    return abandon();
                }
            }
            sender.sendMessage(NetCommands.SENT.render(
                    sender, Map.of("cmd", NAME, "count", String.valueOf(expected.size()), "target", target.text())));
            String sent = logged(prefix() + "sent to " + expected.size());
            if (!"rest".equals(name)) post(sent);
            if (expected.isEmpty()) {
                finish();
                return done;
            }
            JsonObject body = body();
            for (String instance : expected) service.send(instance, NetCommands.CMD, body);
            return done;
        }

        /** Ends this stage unsent: the service is stopping. */
        private CompletableFuture<Map<String, Reply>> abandon() {
            synchronized (this) {
                if (finished) return done;
                finished = true;
                if (window != null) window.cancel(false);
            }
            stages.remove(id, this);
            NetCommands.off(sender);
            done.complete(Map.of());
            return done;
        }

        private String prefix() {
            return name == null ? "" : name + ": ";
        }

        private JsonObject body() {
            JsonObject from = new JsonObject();
            from.addProperty("u", senderId.toString());
            from.addProperty("n", sender.getName());
            from.addProperty("console", sender.isConsole());
            JsonArray values = new JsonArray();
            for (String arg : args) values.add(arg);
            JsonObject body = new JsonObject();
            body.addProperty("id", id);
            body.addProperty("cmd", NAME);
            body.add("args", values);
            body.addProperty("target", target.text());
            body.addProperty("origin", service.instanceId());
            body.add("sender", from);
            body.add("granted", NetCommands.granted(sender, SPEC));
            if (name != null) body.addProperty("stage", name);
            return body;
        }

        private void accept(String from, Reply reply) {
            boolean complete;
            synchronized (this) {
                if (finished || !expected.contains(from) || finals.containsKey(from)) return;
                heard.add(from);
                sender.sendMessage(NetCommands.line(sender, from, reply));
                if (reply.last()) finals.put(from, reply);
                complete = complete();
            }
            if (complete) finish();
        }

        /** Past the accept window, the stage waits only for the instances that answered. */
        private void acceptanceOver() {
            boolean complete;
            synchronized (this) {
                accepting = false;
                complete = complete();
            }
            if (complete) finish();
        }

        private boolean complete() {
            return finals.keySet().containsAll(accepting ? expected : heard);
        }

        void finish() {
            Map<String, Reply> answers;
            List<String> silent = new ArrayList<>();
            synchronized (this) {
                if (finished) return;
                finished = true;
                if (window != null) window.cancel(false);
                if (deadline != null) deadline.cancel(false);
                answers = Map.copyOf(finals);
                for (String instance : new TreeSet<>(expected)) {
                    if (!finals.containsKey(instance)) silent.add(instance);
                }
            }
            stages.remove(id, this);
            try {
                sender.sendMessage(NetCommands.SUMMARY.render(
                        sender,
                        Map.of(
                                "answered",
                                String.valueOf(answers.size()),
                                "expected",
                                String.valueOf(expected.size()))));
                if (!silent.isEmpty())
                    sender.sendMessage(
                            NetCommands.NO_ANSWER.render(sender, Map.of("instances", String.join(", ", silent))));
                List<String> outdated = List.copyOf(new TreeSet<>(older));
                if (!outdated.isEmpty())
                    sender.sendMessage(
                            NetCommands.OLDER.render(sender, Map.of("instances", String.join(", ", outdated))));
                summary = logged(prefix() + answers.size() + " of " + expected.size() + " answered"
                        + (silent.isEmpty() ? "" : "; did not answer: " + String.join(", ", silent))
                        + (outdated.isEmpty() ? "" : "; older builds: " + String.join(", ", outdated)));
            } finally {
                done.complete(answers);
            }
        }

        /** Logs {@code result} as this stage's audit line and returns the line. */
        String logged(String result) {
            String line = NetCommands.audit(
                    id,
                    sender.getName(),
                    senderId,
                    sender.isConsole(),
                    service.instanceId(),
                    target,
                    NAME,
                    args,
                    result);
            LogUtil.info(line);
            return line;
        }

        /** Posts {@code line} to {@code update.webhook} under {@link Webhook#SUMMARY}; the run posts its first and last. */
        void post(@Nullable String line) {
            if (line != null && webhook == Webhook.SUMMARY) NetUpdates.this.post(line);
        }
    }
}
