package ac.cult.cultac.network.routing;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.command.commands.CultReload;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.DormancyController;
import ac.cult.cultac.network.routing.NetworkPlatform.Holder;
import ac.cult.cultac.network.routing.NetworkPlatform.OnlinePlayer;
import ac.cult.cultac.network.routing.RosterCache.Instance;
import ac.cult.cultac.network.routing.RosterCache.State;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.host.UpdateSettings;
import ac.cult.reload.host.UpdateWebhook;
import ac.grim.grimac.api.config.ConfigManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Network commands, {@code /cult net <target> <command>}: one command of a fixed allowlist ({@link #COMMANDS}) run on
 * every instance {@code target} names ({@link Target}). {@value #CMD} carries it from the issuer, broadcast when the
 * target can reach instances the issuer has not heard from or names several, direct when it names one, and over the
 * in-process bus when the issuer is a target itself: {@code {id, cmd, args, target, scope, origin, sender:{u, n,
 * console}, granted, setting, settings}}. {@code scope} lists the target's terms that name backend servers; a proxy
 * that a term names only through its backends runs the command for the players on those backends. {@code setting},
 * sent by a command that sets one, is its {@link NetSettings} record, and {@code settings}, sent by one that sets
 * several, all of them, {@code setting} holding the first; the issuer merged them first and every instance hearing the
 * command merges them when the sender held the command's node. A record names a check by its stable key, which the
 * issuer read the typed name as in its own name version; {@code args} keep the name as typed. Each target answers with
 * {@value #REPLY}, {@code {id, final, ok, code, params, text}}; the issuer shows {@code code} with {@code params} in
 * its own messages, and {@code text}, English, for a code it does not know. Of an instance's generations only the
 * current one runs a command, and only once per id. A target refuses unless {@code network.commands} lets it and the
 * issuer granted {@code cult.net.<command>}. The issuer shows each answer as it arrives and a summary once every
 * expected instance gave its final answer or the command's time ran out. One sender has one command in flight, an
 * instance at most {@value #MAX_PENDING} awaiting answers, and an instance runs at most {@value #MAX_RUNNING}
 * received ones at once and takes {@value #BURST} from one origin in a burst, then one every
 * {@value #REFILL_MILLIS} ms. Each run and each issue and summary is logged as one audit line.
 */
final class NetCommands {
    static final String CMD = "net.cmd";
    static final String REPLY = "net.reply";
    /** The {@link SharedClaims} scope of the command ids a generation here already took. */
    static final String CLAIMS = "net";

    static final String PERMISSION = "cult.net";
    static final String STATUS = "status";
    static final String RELOAD = "reload";
    static final String CHECK = "check";
    static final String PROXIES = "proxies";
    /** Names an instance whose id reads as a target keyword. */
    static final String ID_PREFIX = "id:";

    static final int MAX_PENDING = 8;
    static final int MAX_RUNNING = 8;
    static final int MAX_ARGS = 8;
    static final int MAX_ARG_LENGTH = 200;
    static final int MAX_TERMS = 32;
    /** The commands one origin can have taken here at once after a quiet spell. */
    static final int BURST = 20;
    /** How often an origin earns back one command of its {@link #BURST}. */
    static final long REFILL_MILLIS = 500;

    private static final int MAX_LISTED_BACKENDS = 8;
    private static final int MAX_ORIGINS = 256;
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z]{1,32}");
    private static final Pattern NAME_TERM = Pattern.compile("[A-Za-z0-9._*-]{1,64}");
    private static final Pattern COLOR = Pattern.compile("&[0-9a-fk-orA-FK-OR]");

    /**
     * One allowlisted command: how long its issuer waits for the answers, whether a target may name backend servers
     * for it, which arguments it takes, and the shared settings they set, empty for none, a check name among them read
     * in the name version given. A setting's target may name what no roster lists yet, as the setting applies once that
     * appears.
     */
    record Spec(
            String name,
            long replyMillis,
            boolean backends,
            Predicate<List<String>> valid,
            BiFunction<List<String>, Integer, List<NetSettings.Setting>> settings) {
        Spec(String name, long replyMillis, boolean backends, Predicate<List<String>> valid) {
            this(name, replyMillis, backends, valid, (args, nameVersion) -> List.of());
        }

        String permission() {
            return PERMISSION + "." + name;
        }
    }

    /** The commands this build runs; a target refuses the others as unsupported. */
    static final Map<String, Spec> COMMANDS = Map.of(
            STATUS,
            new Spec(STATUS, 5_000, true, List::isEmpty),
            RELOAD,
            new Spec(RELOAD, 20_000, false, List::isEmpty),
            NetSettings.OBSERVE,
            new Spec(
                    NetSettings.OBSERVE,
                    10_000,
                    true,
                    NetCommands::switchArgs,
                    (args, nameVersion) -> switched(NetSettings.OBSERVE, args)),
            NetSettings.PUNISH,
            new Spec(
                    NetSettings.PUNISH,
                    10_000,
                    true,
                    NetCommands::switchArgs,
                    (args, nameVersion) -> switched(NetSettings.PUNISH, args)),
            NetSettings.TOGGLE,
            new Spec(
                    NetSettings.TOGGLE,
                    10_000,
                    true,
                    NetCommands::switchArgs,
                    (args, nameVersion) -> switched(NetSettings.TOGGLE, args)),
            CHECK,
            new Spec(CHECK, 10_000, true, args -> args.size() == 2 && onOff(args.get(1)) != null, NetCommands::checked),
            NetUpdates.NAME,
            NetUpdates.SPEC);

    /** {@code on} or {@code off}, ignoring case, as a value; null for anything else. */
    static @Nullable Boolean onOff(String arg) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case NetSettings.ON -> true;
            case NetSettings.OFF -> false;
            default -> null;
        };
    }

    /** The arguments of {@code observe}, {@code punish} and {@code toggle}: none to show the setting, else on or off. */
    private static boolean switchArgs(List<String> args) {
        return args.isEmpty() || (args.size() == 1 && onOff(args.getFirst()) != null);
    }

    /**
     * What {@code observe}, {@code punish} or {@code toggle} sets with {@code args}: {@code key} on or off, nothing when
     * bare.
     */
    private static List<NetSettings.Setting> switched(String key, List<String> args) {
        return args.isEmpty()
                ? List.of()
                : List.of(new NetSettings.Setting(key, Boolean.TRUE.equals(onOff(args.getFirst()))));
    }

    /**
     * What {@code check <check> <on|off>} sets: every check {@link CheckCatalog#named(String, int)} reads the name as
     * in {@code nameVersion}; nothing when it names no check of this build.
     */
    private static List<NetSettings.Setting> checked(List<String> args, int nameVersion) {
        boolean on = Boolean.TRUE.equals(onOff(args.get(1)));
        List<NetSettings.Setting> settings = new ArrayList<>();
        for (CheckCatalog.Entry entry : CheckCatalog.named(args.getFirst(), nameVersion))
            settings.add(new NetSettings.Setting(NetSettings.CHECK + entry.stableKey(), on));
        return settings;
    }

    /** What {@code network.commands.webhook} posts to {@code update.webhook}. */
    enum Webhook {
        /** The issuer posts each command it sends and its summary. */
        SUMMARY,
        /** Each target posts its own audit line. */
        EVERY,
        NONE
    }

    /** {@code network.commands}: whether this instance takes network commands, which ones, and what it posts. */
    record Options(boolean enabled, Set<String> accept, Webhook webhook) {
        static final Options DEFAULT = new Options(
                true, Set.of("status", "reload", "observe", "toggle", "check", "punish", "update"), Webhook.SUMMARY);

        Options {
            accept = Set.copyOf(accept);
        }

        /** The {@code network.commands} keys of {@code config}. */
        static Options read(ConfigManager config) {
            List<String> accepted = config.getStringListElse("network.commands.accept", List.copyOf(DEFAULT.accept()));
            Set<String> accept = new HashSet<>();
            if (accepted != null) {
                for (String name : accepted) {
                    if (name != null && !name.isBlank()) accept.add(name.strip().toLowerCase(Locale.ROOT));
                }
            }
            String webhook = config.getStringElse("network.commands.webhook", "summary");
            return new Options(
                    config.getBooleanElse("network.commands.enabled", true),
                    accept,
                    switch (webhook == null ? "" : webhook.strip().toLowerCase(Locale.ROOT)) {
                        case "every" -> Webhook.EVERY;
                        case "none" -> Webhook.NONE;
                        default -> Webhook.SUMMARY;
                    });
        }
    }

    /** How this instance runs network commands and describes itself in the roster. */
    interface Local {
        /** The build the roster and status name. */
        String build();

        /** This instance's state, counting the players {@code platform} lists; the platform adds the backends. */
        State state(NetworkPlatform platform);

        /** Whether an engine generation here inspects {@code player}. Any thread. */
        default boolean inspected(NetworkPlatform platform, UUID player) {
            return platform.holder(player) != Holder.NONE;
        }

        /** The status line's {@code generations}, {@code prediction}, {@code uptime} and {@code engine_uptime}. */
        Map<String, String> details();

        /** {@code network.commands} as it reads now. */
        Options options();

        /** Runs a received command off the network thread. */
        default void dispatch(Runnable task) {
            task.run();
        }

        /** {@code update.webhook} as config.yml holds it; empty for none. */
        default String webhook() {
            return "";
        }

        /** Posts {@code line} to the webhook at {@code url}, not empty. */
        default void post(String url, String line) {}

        /** config.yml's values of the shared settings, as loaded now. */
        default NetSettings.Baseline baseline() {
            return NetSettings.Baseline.DEFAULT;
        }

        /** The name version check names typed here and shown in answers follow: config.yml's, as loaded now. */
        default int checkNameVersion() {
            return CheckNames.LATEST;
        }

        /** Where the shared settings are kept; null keeps them in memory only. */
        default @Nullable Path stateFile() {
            return null;
        }

        /** The config.yml the shared settings are written to and hand edits read from; null for none. */
        default @Nullable Path configFile() {
            return null;
        }

        /** Reloads config.yml into every generation here; completes with whether that succeeded. */
        default CompletableFuture<Boolean> reload() {
            return CompletableFuture.completedFuture(true);
        }

        /**
         * Brings the loaded config, which this instance's own mode reads, to {@code observeOnly}, the network's value for
         * this instance: reloads when the loaded config differs and config.yml holds the value, as no reload reaches a
         * value config.yml lacks; completes with whether the loaded config holds it.
         */
        default CompletableFuture<Boolean> observeOnly(boolean observeOnly) {
            return CompletableFuture.completedFuture(true);
        }

        /**
         * Takes the players the shared {@code toggle} turns off for out of inspection, then completes with how many
         * players here are not checked: all of them or, given {@code backends}, those on one of them.
         */
        default CompletableFuture<Integer> dormant(@Nullable List<String> backends) {
            return CompletableFuture.completedFuture(dormantPlayers(backends));
        }

        /** How many players here are not checked: all of them or, given {@code backends}, those on one of them. */
        default int dormantPlayers(@Nullable List<String> backends) {
            return 0;
        }

        /** How this instance's loader installs numbered builds. */
        default NetUpdates.Builds builds() {
            return NetUpdates.CULT;
        }
    }

    static final Local CULT = new Local() {
        private final long started = System.currentTimeMillis();
        private volatile @Nullable UpdateWebhook hook;

        @Override
        public String build() {
            HostContext host = RuntimeEnvironment.host();
            return host != null
                    ? host.build()
                    : CultAPI.INSTANCE.getExternalAPI().getGrimVersion();
        }

        @Override
        public State state(NetworkPlatform platform) {
            Collection<OnlinePlayer> online = platform.onlinePlayers();
            int elsewhere = 0;
            for (OnlinePlayer player : online) {
                if (platform.holder(player.uuid()) == Holder.OTHER) elsewhere++;
            }
            return new State(
                    NetworkModule.setting(NetSettings.TOGGLE).on(),
                    NetworkModule.setting(NetSettings.OBSERVE).on(),
                    List.copyOf(NetworkModule.checksOff(null)),
                    online.size(),
                    CultAPI.INSTANCE.getPlayerDataManager().size() + elsewhere,
                    DormancyController.dormantPlayers(null));
        }

        @Override
        public boolean inspected(NetworkPlatform platform, UUID player) {
            return platform.holder(player) == Holder.OTHER
                    || CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player) != null;
        }

        @Override
        public Map<String, String> details() {
            HostContext host = RuntimeEnvironment.host();
            return Map.of(
                    "generations",
                    String.valueOf(host == null ? 1 : host.status().size()),
                    "prediction",
                    String.format(Locale.ROOT, "%.3f", SimulationProcessor.getComputeTimeNS() / 1e6),
                    "uptime",
                    duration(ManagementFactory.getRuntimeMXBean().getUptime()),
                    "engine_uptime",
                    duration(System.currentTimeMillis() - started));
        }

        @Override
        public Options options() {
            return Options.read(CultAPI.INSTANCE.getConfigManager().getConfig());
        }

        @Override
        public void dispatch(Runnable task) {
            CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(CultAPI.INSTANCE.getGrimPlugin(), task);
        }

        @Override
        public String webhook() {
            return UpdateSettings.read(directory(), "", System::getenv).webhook();
        }

        @Override
        public void post(String url, String line) {
            UpdateWebhook poster = hook;
            if (poster == null) hook = poster = new UpdateWebhook();
            poster.post(url, line, LogUtil::warn);
        }

        @Override
        public NetSettings.Baseline baseline() {
            BaseConfigManager config = CultAPI.INSTANCE.getConfigManager();
            return new NetSettings.Baseline(
                    config.isDormant(),
                    config.getConfig().getBooleanElse(BaseConfigManager.OBSERVE_ONLY_KEY, false),
                    config.isPausePunishments(),
                    config.getChecksOff());
        }

        @Override
        public int checkNameVersion() {
            return CheckNames.version(CultAPI.INSTANCE.getConfigManager().getConfig(), CheckNames.CONFIG_KEY);
        }

        @Override
        public Path stateFile() {
            return directory().resolve(NetSettings.FILE);
        }

        @Override
        public Path configFile() {
            return directory().resolve("config.yml");
        }

        @Override
        public CompletableFuture<Boolean> reload() {
            return CultReload.reloadAsync();
        }

        @Override
        public CompletableFuture<Boolean> observeOnly(boolean observeOnly) {
            if (CultAPI.INSTANCE.getConfigManager().isObserveOnly() == observeOnly)
                return CompletableFuture.completedFuture(true);
            if (!configHolds(observeOnly)) return CompletableFuture.completedFuture(false);
            return CultReload.reloadAsync()
                    .thenApply(ok -> ok && CultAPI.INSTANCE.getConfigManager().isObserveOnly() == observeOnly);
        }

        @Override
        public CompletableFuture<Integer> dormant(@Nullable List<String> backends) {
            return DormancyController.settle().thenApply(ignored -> dormantPlayers(backends));
        }

        @Override
        public int dormantPlayers(@Nullable List<String> backends) {
            return DormancyController.dormantPlayers(backends);
        }

        private boolean configHolds(boolean observeOnly) {
            try {
                return Boolean.valueOf(observeOnly)
                        .equals(NetStateFile.readConfig(configFile()).get(BaseConfigManager.OBSERVE_ONLY_KEY));
            } catch (IOException | RuntimeException unreadable) {
                return false;
            }
        }

        // Every generation of the host reads config.yml from, and keeps the shared settings in, the host's folder.
        private Path directory() {
            HostContext host = RuntimeEnvironment.host();
            return host == null
                    ? CultAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath()
                    : host.directory();
        }
    };

    /** A message key with its English text, which the bundled messages files repeat. */
    record Text(String key, String fallback) {
        String template() {
            return CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallback);
        }

        Component render(Sender sender, Map<String, String> values) {
            return MessageUtil.getParsedComponent(sender, key, fallback, values);
        }
    }

    private static final Text USAGE = new Text(
            "net-usage",
            "%prefix% &cUse /%command% net <target> <command>. A target is all, proxies, an instance id, a backend"
                    + " server or a pattern such as BRG_*, or several of them joined by commas. Commands: %commands%."
                    + " Name an instance called proxies as id:proxies.");
    private static final Text OFF = new Text(
            "net-off", "%prefix% &eNetwork routing is off on this server, so network commands reach this server only.");
    static final Text BUSY =
            new Text("net-busy", "%prefix% &cA network command is still running; try again in a few seconds.");
    private static final Text INVALID_TERM = new Text(
            "net-invalid-term",
            "%prefix% &c%term% is not a valid target. Names take letters, digits and . _ - *; join several with commas,"
                    + " no spaces.");
    private static final Text UNKNOWN =
            new Text("net-unknown", "%prefix% &cNo proxy or server is named %term%; nothing was sent.");
    private static final Text NO_MATCH =
            new Text("net-no-match", "%prefix% &cNothing matches %term%; nothing was sent.");
    private static final Text NOT_LISTED =
            new Text("net-not-listed", "%prefix% &eNo proxy lists %term%; the setting applies when it appears.");
    private static final Text SYNCING = new Text(
            "net-syncing",
            "%prefix% &cThis server is still loading the network's shared settings; try again in a few seconds.");
    private static final Text BACKEND_TERM =
            new Text("net-backend-term", "%prefix% &c%cmd% runs on CultAC instances only; %term% is a backend server.");
    static final Text SENT = new Text("net-sent", "%prefix% &7%cmd% sent to %count% instances (%target%)");
    private static final Text LINE = new Text("net-reply", "&b%instance% &8» &f%line%");
    static final Text SUMMARY = new Text("net-summary", "%prefix% &7%answered% of %expected% answered");
    static final Text NO_ANSWER = new Text("net-no-answer", "%prefix% &cDid not answer: &f%instances%");
    static final Text OLDER =
            new Text("net-older", "%prefix% &eOlder builds (cannot run network commands): &f%instances%");
    private static final Text REFUSED_PERMISSION =
            new Text("net-refused-permission", "&crefused: %sender% lacks %node% here");

    /** What each result code reads as. */
    private static final Map<String, Text> REPLIES = Map.ofEntries(
            Map.entry(
                    STATUS,
                    new Text(
                            "net-status-line",
                            "&f%build% &8· &7%role% &8· &f%players% &7players, &f%inspected% &7inspected, &f%dormant%"
                                    + " &7dormant &8· &7%toggle% &8· &7%observe% &8· &7%punish% &8· &7checks off:"
                                    + " &f%checks% &8· &f%prediction% &7ms/prediction &8· &7up &f%uptime% &7(engine"
                                    + " &f%engine_uptime%&7, %generations% generations)")),
            Map.entry(
                    "status-backends",
                    new Text(
                            "net-status-backends-line",
                            "&f%build% &8· &7%role% &8· &7on &f%backends%&7: &f%players% &7players, &f%inspected%"
                                    + " &7inspected, &f%dormant% &7dormant &8· &7%toggle% &8· &7%observe% &8· &7%punish%"
                                    + " &8· &7checks off: &f%checks%")),
            Map.entry("refused-permission", REFUSED_PERMISSION),
            Map.entry(
                    "refused-disabled",
                    new Text("net-refused-disabled", "&crefused: this server does not take network commands")),
            Map.entry(
                    "refused-not-accepted",
                    new Text(
                            "net-refused-not-accepted", "&crefused: this server does not take %cmd% over the network")),
            Map.entry(
                    "refused-unsupported",
                    new Text("net-refused-unsupported", "&crefused: this server's build cannot run %cmd%")),
            Map.entry(
                    "refused-invalid",
                    new Text("net-refused-invalid", "&crefused: %cmd% does not take these arguments")),
            Map.entry(
                    "refused-busy",
                    new Text("net-refused-busy", "&crefused: too many network commands are running here")),
            Map.entry(
                    "refused-rate",
                    new Text("net-refused-rate", "&crefused: too many network commands from %instance% lately")),
            Map.entry("failed", new Text("net-failed", "&cfailed: %reason%")),
            Map.entry("reloaded", new Text("reloaded", "%prefix% &fConfig has been reloaded.")),
            Map.entry("reload-failed", new Text("reload-failed", "%prefix% &cFailed to reload config.")),
            Map.entry("observe-state", new Text("net-observe-state", "&f%players%: %state%")),
            Map.entry("observe-applied", new Text("net-observe-applied", "&f%players%: now %state%")),
            Map.entry(
                    "observe-failed",
                    new Text(
                            "net-observe-failed",
                            "&c%players%: set to %state%, but reloading failed; see the console")),
            Map.entry("punish-state", new Text("net-punish-state", "&f%players%: %state%")),
            Map.entry("punish-applied", new Text("net-punish-applied", "&f%players%: now %state%")),
            Map.entry("toggle-state", new Text("net-toggle-state", "&f%players%: %state%")),
            Map.entry(
                    "toggle-off-applied",
                    new Text("net-toggle-off-applied", "&f%players%: now %state% &7(%count% players not checked)")),
            Map.entry(
                    "toggle-on-applied",
                    new Text(
                            "net-toggle-on-applied",
                            "&f%players%: now %state% &7(%count% players not checked yet; checking starts again at"
                                    + " their next server switch or reconnect)")),
            Map.entry("check-applied", new Text("net-check-applied", "&f%check% %state% &7(%players%)")),
            Map.entry("check-unknown", new Text("net-check-unknown", "&cno check %check% here")));

    private final NetworkService service;
    private final LongSupplier clock;
    private final Local local;
    private final SharedClaims claims;
    private final NetUpdates updates;
    private final Map<String, Request> pending = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger running = new AtomicInteger();
    /** Per origin, when its {@link #BURST} is whole again; guarded by itself. */
    private final Map<String, Long> budgets = new HashMap<>();
    /** {@code update.webhook}, read on the worker once per config.yml load; null until the next post reads it. */
    private volatile @Nullable String webhookUrl;
    /** Shows the answers and summaries, and ends each command at its deadline. */
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "CultAC network commands");
        thread.setDaemon(true);
        return thread;
    });
    /** Guarded by {@code this}. */
    private boolean closed;
    /** The run of {@link #syncObserve} in progress; guarded by {@code this}. */
    private @Nullable CompletableFuture<Boolean> observeSync;
    /** The value {@link #observeSync} brings the loaded config to; guarded by {@code this}. */
    private boolean observeSyncTarget;

    /** Commands for {@code service}; {@code host} shares the claimed command ids with the other generations. */
    NetCommands(NetworkService service, LongSupplier clock, Local local, @Nullable HostContext host) {
        this.service = service;
        this.clock = clock;
        this.local = local;
        this.claims = new SharedClaims(host, CLAIMS, clock);
        this.updates = new NetUpdates(service, local, this::post);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    void register() {
        service.on(CMD, this::received);
        service.on(REPLY, this::answered);
        updates.register();
    }

    /** Has the next post read {@code update.webhook} again; called after config.yml reloads. */
    void configLoaded() {
        webhookUrl = null;
    }

    /**
     * A change to the shared settings: when it may change the instance's observe value, the current generation brings
     * the loaded config along, a reload covering every generation here. A reload is itself such a change, which finds
     * the loaded config already holding the value.
     */
    void settingsChanged(NetSettings.Change change) {
        if (!change.affects(NetSettings.OBSERVE) || !service.current()) return;
        syncObserve().whenComplete((ok, failure) -> {
            if (failure != null)
                LogUtil.warn("Could not bring the loaded config to the network's observe-only value", failure);
            else if (!Boolean.TRUE.equals(ok))
                LogUtil.warn("Could not bring the loaded config to the network's observe-only value; see config.yml");
        });
    }

    /**
     * Brings the loaded config to the instance's observe value through {@link Local#observeOnly}; a call while a run for
     * the same value is in progress shares it, and one for another value follows it. Completes with whether the loaded
     * config holds the value.
     */
    CompletableFuture<Boolean> syncObserve() {
        boolean target = service.netSettings().value(NetSettings.OBSERVE).on();
        CompletableFuture<Boolean> running;
        CompletableFuture<Boolean> started = new CompletableFuture<>();
        synchronized (this) {
            running = observeSync;
            if (running == null) {
                observeSync = started;
                observeSyncTarget = target;
            } else if (observeSyncTarget == target) {
                return running;
            }
        }
        if (running != null) return running.handle((ok, failure) -> null).thenCompose(ignored -> syncObserve());
        CompletionStage<Boolean> sync;
        try {
            sync = local.observeOnly(target);
        } catch (RuntimeException failure) {
            sync = CompletableFuture.failedFuture(failure);
        }
        sync.whenComplete((ok, failure) -> {
            synchronized (this) {
                if (observeSync == started) observeSync = null;
            }
            if (failure != null) started.completeExceptionally(failure);
            else started.complete(ok);
        });
        return started;
    }

    /**
     * Sends {@code command} with {@code args} to the instances {@code typed} names and shows {@code sender} each answer
     * and the summary; completes once the summary was shown, at once when nothing was sent.
     */
    CompletableFuture<Void> issue(Sender sender, String typed, String command, List<String> args) {
        return issue(sender, typed, COMMANDS.get(command.toLowerCase(Locale.ROOT)), args);
    }

    /** {@link #issue(Sender, String, String, List)} of {@code spec}, null for a command this build does not run. */
    CompletableFuture<Void> issue(Sender sender, String typed, @Nullable Spec spec, List<String> args) {
        CompletableFuture<Void> none = CompletableFuture.completedFuture(null);
        Target target = Target.parse(typed);
        String malformed = target == null && spec != null ? Target.malformed(typed) : null;
        if (malformed != null) {
            sender.sendMessage(INVALID_TERM.render(sender, Map.of("term", malformed)));
            return none;
        }
        if (spec == null || target == null || !acceptable(args) || !spec.valid().test(args)) {
            usage(sender);
            return none;
        }
        List<NetSettings.Setting> settings = spec.settings().apply(args, local.checkNameVersion());
        if (!settings.isEmpty() && target.text().length() > NetSettings.MAX_TARGET_LENGTH) {
            usage(sender);
            return none;
        }
        if (sender instanceof RemoteCommandSource || !holds(sender, spec.permission())) {
            sender.sendMessage(
                    REFUSED_PERMISSION.render(sender, Map.of("sender", sender.getName(), "node", spec.permission())));
            return none;
        }
        if (spec.name().equals(CHECK) && settings.isEmpty()) {
            sender.sendMessage(line(
                    sender, service.instanceId(), Reply.of(false, "check-unknown", Map.of("check", args.getFirst()))));
            return none;
        }
        if (!settings.isEmpty() && !service.netSettings().caughtUp()) {
            sender.sendMessage(SYNCING.render(sender, Map.of()));
            return none;
        }
        boolean bus = service.enabled();
        List<Known> known = known(bus);
        List<String> unlisted = new ArrayList<>();
        for (Term term : target.terms()) {
            if (bus && !term.known(known)) {
                if (settings.isEmpty()) {
                    sender.sendMessage(
                            (term.pattern() ? NO_MATCH : UNKNOWN).render(sender, Map.of("term", term.text())));
                    return none;
                }
                unlisted.add(term.text());
                continue;
            }
            String backend = spec.backends() ? null : term.backendOnly(known);
            if (backend != null) {
                sender.sendMessage(BACKEND_TERM.render(sender, Map.of("cmd", spec.name(), "term", backend)));
                return none;
            }
        }
        List<String> scope = new ArrayList<>();
        if (spec.backends()) {
            for (Term term : target.terms()) {
                if (term.namesBackend(known)) scope.add(term.value());
            }
        }
        String self = service.instanceId();
        boolean here = false;
        Set<String> expected = new TreeSet<>();
        Set<String> older = new TreeSet<>();
        Set<String> possible = new TreeSet<>();
        for (Known instance : known) {
            if (instance.older()) {
                if (target.namesOlder(instance.id())) older.add(instance.id());
            } else if (target.instanceWide(instance.id(), instance.proxy())
                    || !Target.matching(instance.backends(), scope).isEmpty()) {
                expected.add(instance.id());
                if (instance.id().equals(self)) here = true;
            } else if (instance.undescribed() && (target.namesProxies() || !scope.isEmpty())) {
                possible.add(instance.id());
            }
        }
        if (!bus) {
            off(sender);
            if (!here) return none;
        }
        if (spec == NetUpdates.SPEC)
            return updates.issue(sender, target, args, expected, proxies(known, expected), older);
        Set<String> remote = new TreeSet<>(expected);
        remote.addAll(possible);
        remote.remove(self);
        boolean broadcast = bus && (target.open() || remote.size() > 1);
        UUID senderId = sender.getUniqueId() == null ? Sender.CONSOLE_UUID : sender.getUniqueId();
        String id = UUID.randomUUID().toString();
        List<NetSettings.Record> records = new ArrayList<>();
        for (NetSettings.Setting setting : settings) {
            NetSettings.Record record = service.netSettings().stamp(setting, target.text(), sender.getName(), id);
            if (record == null) {
                usage(sender);
                return none;
            }
            records.add(record);
        }
        Request request = new Request(
                id, sender, senderId, target, spec, List.copyOf(args), expected, older, possible, broadcast);
        synchronized (this) {
            if (closed) {
                off(sender);
                return none;
            }
            if (inFlight.contains(request.key) || pending.size() >= MAX_PENDING) {
                sender.sendMessage(BUSY.render(sender, Map.of()));
                return none;
            }
            inFlight.add(request.key);
            pending.put(request.id, request);
        }
        if (!records.isEmpty()) service.netSettings().merge(records);
        for (String term : unlisted) sender.sendMessage(NOT_LISTED.render(sender, Map.of("term", term)));
        sender.sendMessage(SENT.render(
                sender, Map.of("cmd", spec.name(), "count", String.valueOf(expected.size()), "target", target.text())));
        request.logged("sent to " + expected.size());
        if (expected.isEmpty() && possible.isEmpty() && !broadcast) {
            request.finish();
            return request.done;
        }
        try {
            request.deadline = worker.schedule(request::finish, spec.replyMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            request.finish();
            return request.done;
        }
        JsonObject body = request.body(self, granted(sender, spec), scope);
        if (!records.isEmpty()) body.add("setting", records.getFirst().toJson());
        if (records.size() > 1) body.add("settings", NetSettings.json(records));
        if (here) service.sendLocal(CMD, body);
        if (broadcast) service.broadcast(CMD, body);
        else if (bus && remote.size() == 1) service.send(remote.iterator().next(), CMD, body);
        return request.done;
    }

    /**
     * What /cult net completes for the target typed so far: after its last comma, a keyword, an instance id, a backend
     * server or a pattern for a group of them, that the target does not name yet.
     */
    List<String> targets(String typed) {
        Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> backends = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Known instance : known(service.enabled())) {
            ids.add(instance.id());
            backends.addAll(instance.backends());
        }
        for (String id : ids) backends.remove(id);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(ids);
        names.addAll(backends);
        List<String> candidates = new ArrayList<>(List.of(NetworkSettings.ALL, PROXIES));
        for (String id : ids) candidates.add(reserved(id) ? ID_PREFIX + id : id);
        candidates.addAll(backends);
        candidates.addAll(groups(names));
        return complete(typed, candidates);
    }

    /** Whether /cult net offers {@code command} after {@code typed}: not when the command cannot reach what it names. */
    boolean offered(String typed, String command) {
        Spec spec = COMMANDS.get(command);
        if (spec == null) return false;
        Target target = spec.backends() ? null : Target.parse(typed);
        if (target == null) return true;
        List<Known> known = known(service.enabled());
        for (Term term : target.terms()) {
            if (term.backendOnly(known) != null) return false;
        }
        return true;
    }

    /**
     * {@code typed} up to its last comma followed by each of {@code candidates} that completes the rest and that no term
     * before it names, in order; a candidate the rest already spells out is offered with a comma as well.
     */
    static List<String> complete(String typed, Collection<String> candidates) {
        int comma = typed.lastIndexOf(',');
        String head = typed.substring(0, comma + 1);
        String partial = typed.substring(comma + 1).toLowerCase(Locale.ROOT);
        Set<String> given = new HashSet<>();
        for (String term : head.split(",")) given.add(term.strip().toLowerCase(Locale.ROOT));
        if (given.contains(NetworkSettings.ALL)) return List.of();
        Set<String> offered = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (given.contains(lower) || !lower.startsWith(partial) || covered(given, candidate)) continue;
            offered.add(head + candidate);
            if (lower.equals(partial) && !lower.equals(NetworkSettings.ALL)) offered.add(head + candidate + ",");
        }
        return List.copyOf(offered);
    }

    /** Whether a pattern of {@code given} matches {@code candidate}; patterns name no keyword. */
    private static boolean covered(Set<String> given, String candidate) {
        if (candidate.equalsIgnoreCase(NetworkSettings.ALL) || candidate.equalsIgnoreCase(PROXIES)) return false;
        for (String term : given) {
            if (term.indexOf('*') >= 0 && glob(term, candidate)) return true;
        }
        return false;
    }

    /**
     * A pattern for every group of two or more {@code names}, a group being the names alike up to their trailing
     * digits, else up to their last {@code _} or {@code -}: {@code BRG_*} for {@code BRG_1} and {@code BRG_2}.
     */
    static List<String> groups(Collection<String> names) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        Map<String, Integer> sizes = new HashMap<>();
        for (String name : names) {
            String prefix = group(name);
            if (prefix == null) continue;
            String key = prefix.toLowerCase(Locale.ROOT);
            prefixes.putIfAbsent(key, prefix);
            sizes.merge(key, 1, Integer::sum);
        }
        Set<String> globs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> prefix : prefixes.entrySet()) {
            if (sizes.get(prefix.getKey()) >= 2) globs.add(prefix.getValue() + "*");
        }
        return List.copyOf(globs);
    }

    private static @Nullable String group(String name) {
        int end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) end--;
        if (end == name.length()) end = Math.max(name.lastIndexOf('_'), name.lastIndexOf('-')) + 1;
        return end > 0 && end < name.length() ? name.substring(0, end) : null;
    }

    /** This instance and, while the bus runs, every live instance heard from, as their rosters describe them. */
    private List<Known> known(boolean bus) {
        String self = service.instanceId();
        List<Known> known = new ArrayList<>();
        known.add(new Known(self, service.proxy(), false, false, service.backends()));
        if (!bus) return known;
        for (Instance peer : service.instances()) {
            if (peer.id().equals(self)) continue;
            State state = peer.state();
            known.add(new Known(
                    peer.id(),
                    RosterCache.PROXY.equals(peer.role()),
                    peer.older(),
                    !peer.older() && peer.role() == null,
                    state == null ? List.of() : state.backends()));
        }
        return known;
    }

    /** Ends every command still awaiting answers as its deadline would, after the answers already received. */
    CompletableFuture<Void> expireAll() {
        CompletableFuture<Void> expired = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    for (Request request : List.copyOf(pending.values())) request.finish();
                } finally {
                    expired.complete(null);
                }
            });
        } catch (RejectedExecutionException stopped) {
            expired.complete(null);
        }
        return expired;
    }

    /** Ends every command awaiting answers and refuses new ones; the service is stopping. */
    void close() {
        synchronized (this) {
            closed = true;
        }
        expireAll();
        worker.shutdown();
        updates.close();
    }

    static void usage(Sender sender) {
        sender.sendMessage(USAGE.render(
                sender, Map.of("commands", String.join(", ", new TreeSet<>(COMMANDS.keySet())))));
    }

    static void off(Sender sender) {
        sender.sendMessage(OFF.render(sender, Map.of()));
    }

    /** Whether {@code id} reads as a target keyword, so that /cult net reaches it only as {@code id:<id>}. */
    static boolean reserved(String id) {
        return id.equalsIgnoreCase(PROXIES);
    }

    private static boolean acceptable(List<String> args) {
        if (args.size() > MAX_ARGS) return false;
        for (String arg : args) {
            if (arg == null || arg.isEmpty() || arg.length() > MAX_ARG_LENGTH) return false;
        }
        return true;
    }

    private static boolean holds(Sender sender, String node) {
        return sender.isConsole() || sender.hasPermission(node);
    }

    /** The {@code cult.net} nodes {@code sender} holds, {@code sent}'s included; all of them for the console. */
    static JsonArray granted(Sender sender, Spec sent) {
        Set<String> nodes = new TreeSet<>();
        nodes.add(PERMISSION);
        nodes.add(sent.permission());
        for (Spec spec : COMMANDS.values()) nodes.add(spec.permission());
        JsonArray granted = new JsonArray();
        for (String node : nodes) {
            if (holds(sender, node)) granted.add(node);
        }
        return granted;
    }

    private void received(Envelope envelope) {
        if (!service.current()) return;
        Received request = Received.parse(envelope);
        if (request == null) return;
        if (!request.settings().isEmpty() && request.granted().contains(PERMISSION + "." + request.cmd()))
            service.netSettings().merge(request.settings());
        List<String> backends = null;
        if (!request.target().instanceWide(service.instanceId(), service.proxy())) {
            backends = Target.matching(service.backends(), request.scope());
            if (backends.isEmpty()) return;
        }
        if (!claims.claim(request.id())) return;
        if (!admit(request.origin())) {
            answer(request, Webhook.NONE, Reply.of(false, "refused-rate", Map.of("instance", request.origin())));
            return;
        }
        Options options;
        try {
            options = local.options();
        } catch (RuntimeException unreadable) {
            answer(request, Webhook.NONE, Reply.failed(unreadable));
            return;
        }
        Webhook webhook = options.webhook();
        Reply refusal = refusal(request, options, backends != null);
        if (refusal != null) {
            answer(request, webhook, refusal);
            return;
        }
        if (running.incrementAndGet() > MAX_RUNNING) {
            running.decrementAndGet();
            answer(request, webhook, Reply.of(false, "refused-busy", Map.of()));
            return;
        }
        List<String> scoped = backends;
        try {
            local.dispatch(() -> {
                CompletionStage<Reply> result;
                try {
                    result = run(request, scoped);
                } catch (RuntimeException failure) {
                    result = CompletableFuture.completedFuture(Reply.failed(failure));
                }
                result.whenComplete((reply, failure) -> {
                    running.decrementAndGet();
                    answer(request, webhook, failure == null ? reply : Reply.failed(failure));
                });
            });
        } catch (RuntimeException unavailable) {
            running.decrementAndGet();
            answer(request, webhook, Reply.failed(unavailable));
        }
    }

    /** Takes one command from {@code origin}'s {@link #BURST}; false when it is spent. */
    private boolean admit(String origin) {
        long now = clock.getAsLong();
        synchronized (budgets) {
            if (budgets.size() >= MAX_ORIGINS) budgets.values().removeIf(whole -> whole <= now);
            long whole = Math.max(budgets.getOrDefault(origin, now), now);
            if (whole - now > (BURST - 1) * REFILL_MILLIS) return false;
            budgets.put(origin, whole + REFILL_MILLIS);
            return true;
        }
    }

    private static @Nullable Reply refusal(Received request, Options options, boolean scoped) {
        String command = request.cmd();
        Spec spec = COMMANDS.get(command);
        if (spec == null) return Reply.of(false, "refused-unsupported", Map.of("cmd", command));
        if (!options.enabled()) return Reply.of(false, "refused-disabled", Map.of());
        if (!options.accept().contains(command)) return Reply.of(false, "refused-not-accepted", Map.of("cmd", command));
        if (!request.granted().contains(spec.permission()))
            return Reply.of(false, "refused-permission", Map.of("sender", request.name(), "node", spec.permission()));
        if (!spec.valid().test(request.args()) || (scoped && !spec.backends()))
            return Reply.of(false, "refused-invalid", Map.of("cmd", command));
        return null;
    }

    /**
     * Runs an accepted command here, on the async scheduler: for every player, or with {@code backends} for the players
     * on those backend servers.
     */
    private CompletionStage<Reply> run(Received request, @Nullable List<String> backends) {
        return switch (request.cmd()) {
            case STATUS -> CompletableFuture.completedFuture(backends == null ? status() : status(backends));
            case RELOAD ->
                local.reload()
                        .exceptionally(failure -> false)
                        .thenApply(ok -> Reply.of(ok, ok ? "reloaded" : "reload-failed", Map.of()));
            case NetSettings.OBSERVE, NetSettings.PUNISH -> switched(request, backends);
            case NetSettings.TOGGLE -> toggle(request, backends);
            case CHECK -> CompletableFuture.completedFuture(check(request, backends));
            case NetUpdates.NAME -> updates.run(request.id(), request.origin(), request.name(), request.args());
            default ->
                CompletableFuture.completedFuture(Reply.of(false, "refused-unsupported", Map.of("cmd", request.cmd())));
        };
    }

    /**
     * {@code observe} or {@code punish}: bare, shows the setting; else answers with the value its record, merged on
     * receipt, left. Observe for the whole instance answers once config.yml holds the value and the loaded config took
     * it.
     */
    private CompletionStage<Reply> switched(Received request, @Nullable List<String> backends) {
        String key = request.cmd();
        String players = players(backends);
        if (request.args().isEmpty())
            return CompletableFuture.completedFuture(Reply.of(
                    true, key + "-state", Map.of("players", players, "state", described(key, backends, true))));
        if (backends != null || !key.equals(NetSettings.OBSERVE))
            return CompletableFuture.completedFuture(Reply.of(
                    true, key + "-applied", Map.of("players", players, "state", described(key, backends, false))));
        return service.netSettings()
                .settled()
                .thenCompose(ignored -> syncObserve())
                .exceptionally(failure -> false)
                .thenApply(ok -> Reply.of(
                        ok,
                        ok ? "observe-applied" : "observe-failed",
                        Map.of("players", players, "state", described(key, null, false))));
    }

    /**
     * {@code toggle}: bare, shows the setting; else answers with the value its record, merged on receipt, left, once the
     * players it turns off for are out of inspection, and how many players here are not checked.
     */
    private CompletionStage<Reply> toggle(Received request, @Nullable List<String> backends) {
        String key = NetSettings.TOGGLE;
        String players = players(backends);
        if (request.args().isEmpty())
            return CompletableFuture.completedFuture(Reply.of(
                    true, "toggle-state", Map.of("players", players, "state", described(key, backends, true))));
        String code =
                Boolean.TRUE.equals(onOff(request.args().getFirst())) ? "toggle-on-applied" : "toggle-off-applied";
        return service.netSettings()
                .settled()
                .thenCompose(ignored -> local.dormant(backends))
                .thenApply(count -> Reply.of(
                        true,
                        code,
                        Map.of(
                                "players",
                                players,
                                "state",
                                described(key, backends, false),
                                "count",
                                String.valueOf(count))));
    }

    /**
     * {@code check <check> <on|off>}: answers with the value each of its records, merged on receipt, left, or without
     * one, with the value of each check the name names here, the checks named in this instance's name version.
     */
    private Reply check(Received request, @Nullable List<String> backends) {
        int nameVersion = local.checkNameVersion();
        List<String> keys = new ArrayList<>();
        if (request.settings().isEmpty()) {
            for (CheckCatalog.Entry entry : CheckCatalog.named(request.args().getFirst(), nameVersion))
                keys.add(entry.stableKey());
        } else {
            for (NetSettings.Record record : request.settings()) {
                String key = record.k().substring(NetSettings.CHECK.length());
                if (CheckCatalog.byStableKey(key) != null) keys.add(key);
            }
        }
        if (keys.isEmpty())
            return Reply.of(
                    false, "check-unknown", Map.of("check", request.args().getFirst()));
        Map<String, List<String>> byState = new LinkedHashMap<>();
        for (String key : keys)
            byState.computeIfAbsent(described(NetSettings.CHECK + key, backends, false), ignored -> new ArrayList<>())
                    .add(key);
        List<String> states = new ArrayList<>();
        for (Map.Entry<String, List<String>> state : byState.entrySet())
            states.add(byState.size() == 1
                    ? state.getKey()
                    : state.getKey() + " for " + String.join(", ", CheckCatalog.names(state.getValue(), nameVersion)));
        return Reply.of(
                true,
                "check-applied",
                Map.of(
                        "check",
                        String.join(", ", CheckCatalog.names(keys, nameVersion)),
                        "state",
                        String.join("; ", states),
                        "players",
                        players(backends)));
    }

    /** Whom a command ran for: every player here, or those on {@code backends}. */
    private static String players(@Nullable List<String> backends) {
        return backends == null ? "all players" : "players on " + listed(backends);
    }

    /** {@code names}, the first {@value #MAX_LISTED_BACKENDS} of them and how many more. */
    private static String listed(List<String> names) {
        String listed = String.join(", ", names.subList(0, Math.min(names.size(), MAX_LISTED_BACKENDS)));
        return names.size() > MAX_LISTED_BACKENDS ? listed + " +" + (names.size() - MAX_LISTED_BACKENDS) : listed;
    }

    /**
     * {@code key}'s value for every player here or, given {@code backends}, for those on each of them, with where it
     * comes from when {@code source}; backends whose players differ are listed with each value.
     */
    private String described(String key, @Nullable List<String> backends, boolean source) {
        NetSettings settings = service.netSettings();
        if (backends == null) return describe(key, settings.value(key), source);
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String backend : backends)
            groups.computeIfAbsent(describe(key, settings.value(key, backend), source), ignored -> new ArrayList<>())
                    .add(backend);
        return grouped(groups);
    }

    private static String describe(String key, NetSettings.Value value, boolean source) {
        String state = switch (key) {
            case NetSettings.OBSERVE -> value.on() ? "observe-only" : "enforcing";
            case NetSettings.PUNISH -> value.on() ? "punishing" : "punishments paused";
            case NetSettings.TOGGLE -> value.on() ? "checking" : "dormant";
            default -> value.on() ? NetSettings.ON : NetSettings.OFF;
        };
        if (!source) return state;
        NetSettings.Record record = value.record();
        return record == null || record.by().equals(NetSettings.HAND_EDIT)
                ? state + " (from config.yml)"
                : state + " (set by " + record.by() + " for " + record.t() + ")";
    }

    /** The names of the checks switched off for every player here or, given {@code backends}, on each of them. */
    private String checksOff(@Nullable List<String> backends) {
        NetSettings settings = service.netSettings();
        int nameVersion = local.checkNameVersion();
        if (backends == null) return checkNames(settings.checksOff(null), nameVersion);
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String backend : backends)
            groups.computeIfAbsent(checkNames(settings.checksOff(backend), nameVersion), ignored -> new ArrayList<>())
                    .add(backend);
        return grouped(groups);
    }

    private static String checkNames(Collection<String> stableKeys, int nameVersion) {
        return stableKeys.isEmpty() ? "none" : String.join(", ", CheckCatalog.names(stableKeys, nameVersion));
    }

    /** The one value of {@code groups}, else each value with the backends it holds on. */
    private static String grouped(Map<String, List<String>> groups) {
        if (groups.size() == 1) return groups.keySet().iterator().next();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet())
            parts.add(group.getKey() + " on " + listed(group.getValue()));
        return String.join("; ", parts);
    }

    private Reply status() {
        State state = local.state(service.platform());
        Map<String, String> params = new LinkedHashMap<>(local.details());
        settings(params, state, null);
        params.put("players", String.valueOf(state.players()));
        params.put("inspected", String.valueOf(state.inspected()));
        params.put("dormant", String.valueOf(state.dormant()));
        if (!state.checking()) params.put("prediction", "–");
        return Reply.of(true, STATUS, params);
    }

    /** The status of the players on {@code backends}, each a backend server registered here. */
    private Reply status(List<String> backends) {
        NetworkPlatform platform = service.platform();
        Set<String> on = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        on.addAll(backends);
        int players = 0;
        int inspected = 0;
        for (OnlinePlayer player : platform.onlinePlayers()) {
            if (player.server() == null || !on.contains(player.server())) continue;
            players++;
            if (local.inspected(platform, player.uuid())) inspected++;
        }
        Map<String, String> params = new LinkedHashMap<>();
        settings(params, local.state(platform), backends);
        params.put("backends", listed(backends));
        params.put("players", String.valueOf(players));
        params.put("inspected", String.valueOf(inspected));
        params.put("dormant", String.valueOf(local.dormantPlayers(backends)));
        return Reply.of(true, "status-backends", params);
    }

    /**
     * The build, role and settings a status line shows, for every player here or, given {@code backends}, for those on
     * each of them.
     */
    private void settings(Map<String, String> params, State state, @Nullable List<String> backends) {
        params.put("build", String.valueOf(local.build()));
        params.put("role", service.proxy() ? RosterCache.PROXY : RosterCache.SERVER);
        params.put(
                "toggle",
                backends == null
                        ? state.checking() ? "checking" : "dormant"
                        : described(NetSettings.TOGGLE, backends, false));
        params.put("observe", described(NetSettings.OBSERVE, backends, false));
        params.put("punish", described(NetSettings.PUNISH, backends, false));
        params.put("checks", checksOff(backends));
    }

    private void answer(Received request, Webhook webhook, Reply reply) {
        String line = audit(
                request.id(),
                request.name(),
                request.uuid(),
                request.console(),
                request.origin(),
                request.target(),
                request.cmd(),
                request.args(),
                reply.code());
        LogUtil.info(line);
        if (webhook == Webhook.EVERY) post(line);
        service.send(request.origin(), REPLY, reply.toJson(request.id()));
    }

    private void answered(Envelope envelope) {
        String id;
        Reply reply;
        try {
            id = envelope.body().get("id").getAsString();
            reply = Reply.parse(envelope.body());
        } catch (RuntimeException malformed) {
            return;
        }
        Request request = pending.get(id);
        if (request == null) return;
        String from = envelope.from();
        try {
            worker.execute(() -> request.accept(from, reply));
        } catch (RejectedExecutionException stopped) {
            request.finish();
        }
    }

    /** Posts {@code line} to {@code update.webhook} from the worker, or on this thread when the worker is shut down. */
    private void post(String line) {
        try {
            worker.execute(() -> postNow(line));
        } catch (RejectedExecutionException stopped) {
            postNow(line);
        }
    }

    private void postNow(String line) {
        try {
            String url = webhookUrl;
            if (url == null) webhookUrl = url = local.webhook();
            if (!url.isEmpty()) local.post(url, "[CultAC] " + line);
        } catch (RuntimeException failure) {
            LogUtil.warn("Network command webhook post failed", failure);
        }
    }

    /** {@code net <id8> <name> (<uuid>|console) from <origin> → <target>: <command> <args> = <result>}. */
    static String audit(
            String id,
            String name,
            UUID uuid,
            boolean console,
            String origin,
            Target target,
            String command,
            List<String> args,
            String result) {
        return "net " + id.substring(0, Math.min(8, id.length())) + " " + name + " (" + (console ? "console" : uuid)
                + ") from " + origin + " → " + target.text() + ": " + command
                + (args.isEmpty() ? "" : " " + String.join(" ", args)) + " = " + result;
    }

    /** {@code millis} as days and hours, hours and minutes, or minutes. */
    static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        long days = minutes / 1440;
        long hours = minutes / 60 % 24;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes % 60 + "m";
        return minutes + "m";
    }

    static Component line(Sender sender, String instance, Reply reply) {
        Text text = replyText(reply.code());
        String line = text == null
                ? MessageUtil.miniMessageSafe(reply.text())
                : fill(unprefixed(text.template()), reply.params());
        String format = LINE.template()
                .replace("%instance%", MessageUtil.miniMessageSafe(instance))
                .replace("%line%", line);
        return MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, format));
    }

    /** {@code template} without {@code %prefix%}: an answer shows after its instance's name. */
    private static String unprefixed(String template) {
        return template.replace("%prefix%", "").strip();
    }

    /** What result {@code code} reads as; null for a code this build does not know. */
    private static @Nullable Text replyText(String code) {
        Text text = REPLIES.get(code);
        return text != null ? text : NetUpdates.REPLIES.get(code);
    }

    private static String fill(String template, Map<String, String> values) {
        for (Map.Entry<String, String> value : values.entrySet())
            template = template.replace("%" + value.getKey() + "%", MessageUtil.miniMessageSafe(value.getValue()));
        return template;
    }

    /**
     * An instance a command can address, and the backend servers it answers for; {@code undescribed} while its role and
     * backends are not known yet.
     */
    private record Known(String id, boolean proxy, boolean older, boolean undescribed, List<String> backends) {}

    /** The proxies of {@code known} among {@code expected}, by id. */
    private static Set<String> proxies(List<Known> known, Set<String> expected) {
        Set<String> proxies = new TreeSet<>();
        for (Known instance : known) {
            if (instance.proxy() && expected.contains(instance.id())) proxies.add(instance.id());
        }
        return proxies;
    }

    /** One term of a target: {@code all}, {@code proxies}, {@code id:<instance id>}, or a name or pattern. */
    record Term(Kind kind, String value) {
        enum Kind {
            ALL,
            PROXIES,
            /** An instance id, never a backend server. */
            ID,
            /** An instance id or a backend server name; {@code *} stands for any run of characters. */
            NAME
        }

        static @Nullable Term parse(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.equals(NetworkSettings.ALL)) return new Term(Kind.ALL, NetworkSettings.ALL);
            if (lower.equals(PROXIES)) return new Term(Kind.PROXIES, PROXIES);
            if (lower.startsWith(ID_PREFIX)) {
                String id = text.substring(ID_PREFIX.length());
                return NetworkSettings.invalidInstanceId(id) == null ? new Term(Kind.ID, id) : null;
            }
            return NAME_TERM.matcher(text).matches() ? new Term(Kind.NAME, text) : null;
        }

        String text() {
            return kind == Kind.ID ? ID_PREFIX + value : value;
        }

        /** Whether the term is a pattern. */
        boolean pattern() {
            return kind == Kind.NAME && value.indexOf('*') >= 0;
        }

        boolean instanceWide(String instance, boolean proxy) {
            return switch (kind) {
                case ALL -> true;
                case PROXIES -> proxy;
                case ID -> value.equalsIgnoreCase(instance);
                case NAME -> glob(value, instance);
            };
        }

        /** Whether the term names an instance of {@code known} or one of their backend servers. */
        boolean known(List<Known> known) {
            if (kind == Kind.ALL || kind == Kind.PROXIES) return true;
            for (Known instance : known) {
                if (instanceWide(instance.id(), instance.proxy())) return true;
            }
            return namesBackend(known);
        }

        /** Whether the term names, or may name, a backend server of an instance of {@code known}. */
        boolean namesBackend(List<Known> known) {
            if (kind != Kind.NAME) return false;
            for (Known instance : known) {
                if (instance.undescribed()) return true;
                for (String backend : instance.backends()) {
                    if (glob(value, backend)) return true;
                }
            }
            return false;
        }

        /** A backend server the term names that is no instance of {@code known}; null when it names none. */
        @Nullable
        String backendOnly(List<Known> known) {
            if (kind != Kind.NAME) return null;
            for (Known instance : known) {
                for (String backend : instance.backends()) {
                    if (glob(value, backend) && !isInstance(backend, known)) return backend;
                }
            }
            return null;
        }

        private static boolean isInstance(String name, List<Known> known) {
            for (Known instance : known) {
                if (instance.id().equalsIgnoreCase(name)) return true;
            }
            return false;
        }
    }

    /**
     * What a command addresses: one or more {@link Term}s joined by commas. A term names an instance as a whole when it
     * is {@code all}, {@code proxies} for a proxy, or the instance's id, and a proxy's players on a backend server when
     * it is that server's name; a name that is both stands for both. Names compare ignoring case.
     */
    record Target(List<Term> terms) {
        Target {
            terms = List.copyOf(terms);
        }

        /** {@code text} as typed; null when a term is malformed, there are none, or more than {@value #MAX_TERMS}. */
        static @Nullable Target parse(String text) {
            String[] parts = text.split(",", -1);
            if (parts.length > MAX_TERMS) return null;
            Map<String, Term> terms = new LinkedHashMap<>();
            for (String part : parts) {
                Term term = Term.parse(part);
                if (term == null) return null;
                terms.putIfAbsent(term.text().toLowerCase(Locale.ROOT), term);
            }
            return new Target(List.copyOf(terms.values()));
        }

        /** The first term of {@code text} that is malformed, all of {@code text} when that term is empty; else null. */
        static @Nullable String malformed(String text) {
            for (String part : text.split(",", -1)) {
                if (Term.parse(part) == null) return part.isEmpty() ? text : part;
            }
            return null;
        }

        /** Whether some term names {@code instance} as a whole. */
        boolean instanceWide(String instance, boolean proxy) {
            for (Term term : terms) {
                if (term.instanceWide(instance, proxy)) return true;
            }
            return false;
        }

        /** Whether the target names {@code instance}, a build without network commands, as a whole. */
        boolean namesOlder(String instance) {
            for (Term term : terms) {
                if (term.kind() != Term.Kind.PROXIES && term.instanceWide(instance, false)) return true;
            }
            return false;
        }

        /** Whether some term is {@code proxies}. */
        boolean namesProxies() {
            for (Term term : terms) {
                if (term.kind() == Term.Kind.PROXIES) return true;
            }
            return false;
        }

        /** Whether the target can name instances not heard from yet: a keyword or a pattern. */
        boolean open() {
            for (Term term : terms) {
                if (term.kind() != Term.Kind.ID && (term.kind() != Term.Kind.NAME || term.pattern())) return true;
            }
            return false;
        }

        String text() {
            List<String> texts = new ArrayList<>();
            for (Term term : terms) texts.add(term.text());
            return String.join(",", texts);
        }

        /** The {@code names} some of {@code patterns} match, in order. */
        static List<String> matching(List<String> names, List<String> patterns) {
            List<String> matched = new ArrayList<>();
            if (patterns.isEmpty()) return matched;
            for (String name : names) {
                for (String pattern : patterns) {
                    if (glob(pattern, name)) {
                        matched.add(name);
                        break;
                    }
                }
            }
            return matched;
        }
    }

    /** Whether {@code name} matches {@code pattern}, ignoring case, {@code *} standing for any run of characters. */
    static boolean glob(String pattern, String name) {
        int p = 0;
        int n = 0;
        int star = -1;
        int resume = 0;
        while (n < name.length()) {
            if (p < pattern.length()
                    && pattern.charAt(p) != '*'
                    && Character.toLowerCase(pattern.charAt(p)) == Character.toLowerCase(name.charAt(n))) {
                p++;
                n++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                resume = n;
            } else if (star >= 0) {
                p = star + 1;
                n = ++resume;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    /**
     * One answer: whether it is the last for its command, whether the command succeeded, the result code with its
     * parameters, and {@code text}, the line in English for an issuer that does not know the code.
     */
    record Reply(boolean last, boolean ok, String code, Map<String, String> params, String text) {
        Reply {
            params = Map.copyOf(params);
        }

        static Reply of(boolean ok, String code, Map<String, String> params) {
            return new Reply(true, ok, code, params, english(code, params));
        }

        static Reply failed(Throwable failure) {
            Throwable cause =
                    failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return of(false, "failed", Map.of("reason", reason));
        }

        private static String english(String code, Map<String, String> params) {
            Text text = replyText(code);
            String line = text == null ? code : unprefixed(text.fallback());
            for (Map.Entry<String, String> value : params.entrySet())
                line = line.replace("%" + value.getKey() + "%", value.getValue());
            return COLOR.matcher(line).replaceAll("");
        }

        JsonObject toJson(String id) {
            JsonObject body = new JsonObject();
            body.addProperty("id", id);
            body.addProperty("final", last);
            body.addProperty("ok", ok);
            body.addProperty("code", code);
            JsonObject values = new JsonObject();
            for (Map.Entry<String, String> value : params.entrySet())
                values.addProperty(value.getKey(), value.getValue());
            body.add("params", values);
            body.addProperty("text", text);
            return body;
        }

        static Reply parse(JsonObject body) {
            Map<String, String> params = new LinkedHashMap<>();
            if (body.get("params") instanceof JsonObject values) {
                for (Map.Entry<String, JsonElement> value : values.entrySet()) {
                    if (value.getValue() instanceof JsonPrimitive primitive)
                        params.put(value.getKey(), primitive.getAsString());
                }
            }
            return new Reply(
                    !body.has("final") || body.get("final").getAsBoolean(),
                    body.get("ok").getAsBoolean(),
                    body.get("code").getAsString(),
                    params,
                    body.get("text") instanceof JsonPrimitive text ? text.getAsString() : "");
        }
    }

    /**
     * A command as a target received it; {@code origin} is the instance that sent it, {@code scope} the target's terms
     * that name backend servers, {@code settings} the records of the settings it sets.
     */
    private record Received(
            String id,
            String cmd,
            List<String> args,
            Target target,
            List<String> scope,
            String origin,
            String name,
            UUID uuid,
            boolean console,
            Set<String> granted,
            List<NetSettings.Record> settings) {
        static @Nullable Received parse(Envelope envelope) {
            JsonObject body = envelope.body();
            try {
                String id = UUID.fromString(body.get("id").getAsString()).toString();
                String command = body.get("cmd").getAsString();
                Target target = Target.parse(body.get("target").getAsString());
                List<String> args = new ArrayList<>();
                if (body.get("args") instanceof JsonArray values) {
                    for (JsonElement value : values) args.add(value.getAsString());
                }
                List<String> scope = new ArrayList<>();
                if (body.get("scope") instanceof JsonArray patterns) {
                    for (JsonElement pattern : patterns) {
                        String text = pattern.getAsString();
                        if (scope.size() >= MAX_TERMS
                                || !NAME_TERM.matcher(text).matches()) return null;
                        scope.add(text);
                    }
                }
                Set<String> granted = new HashSet<>();
                if (body.get("granted") instanceof JsonArray nodes) {
                    for (JsonElement node : nodes) granted.add(node.getAsString());
                }
                JsonObject from = body.getAsJsonObject("sender");
                if (target == null || !COMMAND_NAME.matcher(command).matches() || !acceptable(args)) return null;
                List<NetSettings.Record> sent = NetSettings.records(body.get("settings"));
                if (sent.isEmpty()) {
                    NetSettings.Record setting = NetSettings.Record.parse(body.get("setting"));
                    if (setting != null) sent = List.of(setting);
                }
                List<NetSettings.Record> settings = new ArrayList<>();
                for (NetSettings.Record setting : sent) {
                    if (setting.id().equals(id) && NetSettings.command(setting.k()).equals(command))
                        settings.add(setting);
                }
                return new Received(
                        id,
                        command,
                        List.copyOf(args),
                        target,
                        List.copyOf(scope),
                        envelope.from(),
                        from.get("n").getAsString(),
                        UUID.fromString(from.get("u").getAsString()),
                        from.has("console") && from.get("console").getAsBoolean(),
                        Set.copyOf(granted),
                        List.copyOf(settings));
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }

    /** A command awaiting its answers; it ends once, at its last expected answer or its deadline. */
    private final class Request {
        final String id;
        final Sender sender;
        final UUID senderId;
        /** One command in flight per key. */
        final String key;

        final Target target;
        final Spec spec;
        final List<String> args;
        final Set<String> expected;
        /** The live instances addressed whose last full roster names no role: builds without network commands. */
        final Set<String> older;
        /** Instances not described yet that the target may name through their role or backends; none must answer. */
        final Set<String> possible;
        /** Whether every instance heard it. An instance not in {@code expected} may answer. */
        final boolean broadcast;

        final Set<String> answered = new TreeSet<>();
        final Webhook webhook;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile @Nullable ScheduledFuture<?> deadline;
        private boolean finished;

        Request(
                String id,
                Sender sender,
                UUID senderId,
                Target target,
                Spec spec,
                List<String> args,
                Set<String> expected,
                Set<String> older,
                Set<String> possible,
                boolean broadcast) {
            this.id = id;
            this.sender = sender;
            this.senderId = senderId;
            this.key = senderId.toString();
            this.target = target;
            this.spec = spec;
            this.args = args;
            this.expected = expected;
            this.older = older;
            this.possible = possible;
            this.broadcast = broadcast;
            Webhook mode;
            try {
                mode = local.options().webhook();
            } catch (RuntimeException unreadable) {
                mode = Webhook.NONE;
            }
            this.webhook = mode;
        }

        JsonObject body(String origin, JsonArray granted, List<String> scope) {
            JsonObject from = new JsonObject();
            from.addProperty("u", senderId.toString());
            from.addProperty("n", sender.getName());
            from.addProperty("console", sender.isConsole());
            JsonArray values = new JsonArray();
            for (String arg : args) values.add(arg);
            JsonObject body = new JsonObject();
            body.addProperty("id", id);
            body.addProperty("cmd", spec.name());
            body.add("args", values);
            body.addProperty("target", target.text());
            if (!scope.isEmpty()) {
                JsonArray patterns = new JsonArray();
                for (String pattern : scope) patterns.add(pattern);
                body.add("scope", patterns);
            }
            body.addProperty("origin", origin);
            body.add("sender", from);
            body.add("granted", granted);
            return body;
        }

        /**
         * Shows {@code reply} from {@code from}; from an instance not expected, only after a broadcast or when it is
         * possible. Silent possible instances keep the command open until its deadline; older builds drop the command
         * unread, so none is waited for.
         */
        synchronized void accept(String from, Reply reply) {
            if (finished || answered.contains(from)) return;
            if (!expected.contains(from)) {
                if (!broadcast && !possible.contains(from)) return;
                expected.add(from);
            }
            sender.sendMessage(line(sender, from, reply));
            if (!reply.last()) return;
            answered.add(from);
            if (answered.containsAll(expected) && answered.containsAll(possible)) finish();
        }

        synchronized void finish() {
            if (finished) return;
            finished = true;
            try {
                ScheduledFuture<?> timer = deadline;
                if (timer != null) timer.cancel(false);
                pending.remove(id, this);
                inFlight.remove(key);
                List<String> silent = new ArrayList<>();
                for (String instance : expected) {
                    if (!answered.contains(instance) && !older.contains(instance)) silent.add(instance);
                }
                List<String> outdated = new ArrayList<>();
                for (String instance : older) {
                    if (!answered.contains(instance)) outdated.add(instance);
                }
                sender.sendMessage(SUMMARY.render(
                        sender,
                        Map.of(
                                "answered",
                                String.valueOf(answered.size()),
                                "expected",
                                String.valueOf(expected.size()))));
                if (!silent.isEmpty())
                    sender.sendMessage(NO_ANSWER.render(sender, Map.of("instances", String.join(", ", silent))));
                if (!outdated.isEmpty())
                    sender.sendMessage(OLDER.render(sender, Map.of("instances", String.join(", ", outdated))));
                logged(answered.size() + " of " + expected.size() + " answered"
                        + (silent.isEmpty() ? "" : "; did not answer: " + String.join(", ", silent))
                        + (outdated.isEmpty() ? "" : "; older builds: " + String.join(", ", outdated)));
            } finally {
                done.complete(null);
            }
        }

        void logged(String result) {
            String line = audit(
                    id,
                    sender.getName(),
                    senderId,
                    sender.isConsole(),
                    service.instanceId(),
                    target,
                    spec.name(),
                    args,
                    result);
            LogUtil.info(line);
            if (webhook == Webhook.SUMMARY) post(line);
        }
    }
}
