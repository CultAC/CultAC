package ac.cult.velocity;

import ac.cult.reload.api.RuntimeInstance;
import ac.cult.reload.host.BuildUpdates;
import ac.cult.reload.host.CommandRouting;
import ac.cult.reload.host.GenerationManager;
import ac.cult.reload.host.UpdateSettings;
import ac.cult.reload.host.UpdateWebhook;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(
        id = "cultac",
        name = "CultAC",
        version = CultBuildVersion.VERSION,
        description = "Compensated movement anticheat",
        authors = {"GrimCult"},
        dependencies = {
            @Dependency(id = "geyser", optional = true),
            @Dependency(id = "floodgate", optional = true),
            @Dependency(id = "viaversion", optional = true),
            @Dependency(id = "viabackwards", optional = true),
            @Dependency(id = "luckperms", optional = true),
            // Cross-proxy /cult profile uses its pub/sub once it has started.
            @Dependency(id = "redisbungee", optional = true)
        })
public final class CultVelocityPlugin {
    private static final Component REFUSED = Component.text(RuntimeInstance.CONNECTION_REFUSED);
    /** The proxy does not listen before initialization ends, so this is all a slow download can delay startup. */
    private static final Duration BOOT_UPDATE_LIMIT = Duration.ofSeconds(10);

    private static final Executor UPDATE_WORKER =
            task -> Thread.ofPlatform().daemon().name("cult-update").start(task);
    private static final String UPDATE_USAGE = " update <build> | latest [channel] | builds [channel] | <filename.jar>";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private GenerationManager engine;
    private volatile boolean stopping;
    private java.lang.reflect.Method connectionMethod, channelMethod;
    private BridgeChannelGuard bridgeGuard;
    private final UpdateWebhook webhook = new UpdateWebhook();
    private volatile String bundledVersion = "";
    private SimpleCommand command;
    private final Set<String> registeredRoots = new HashSet<>();

    @Inject
    public CultVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    // Via loads its manager at NORMAL and enables conversion at LAST. Optional
    // dependencies register its LAST handler before ours when it is installed.
    @Subscribe(order = PostOrder.LAST)
    public void initialize(ProxyInitializeEvent event) throws Exception {
        try {
            var hostLoader = proxy.getClass().getClassLoader();
            connectionMethod = Class.forName(
                            "com.velocitypowered.proxy.connection.client.ConnectedPlayer", false, hostLoader)
                    .getMethod("getConnection");
            channelMethod = connectionMethod.getReturnType().getMethod("getChannel");
            bridgeGuard = BridgeChannelGuard.forProxy(hostLoader);
        } catch (ReflectiveOperationException failure) {
            logger.error(
                    "CultAC is disabled and players join uninspected: this Velocity build has no"
                            + " ConnectedPlayer.getConnection().getChannel() or PluginMessagePacket."
                            + " Update CultAC for this Velocity build.",
                    failure);
            return;
        }
        try {
            engine = new GenerationManager(
                    directory,
                    "velocity",
                    this,
                    proxy,
                    logger,
                    getClass().getClassLoader(),
                    logger::info,
                    null,
                    ac.cult.reload.host.LoaderVersions.read(
                            getClass().getResourceAsStream("/velocity-plugin.json"), "velocity"));
            engine.connectionResolver(ac.cult.reload.integrations.GeyserConnections::upstream);
            Path bootBuild = null;
            try {
                bundledVersion = BuildUpdates.descriptorVersion(
                        getClass().getResourceAsStream("/velocity-plugin.json"), "velocity");
                bootBuild = new BuildUpdates(updateSettings(), bundledVersion)
                        .boot(engine.loaderVersion(), engine.updates(), BOOT_UPDATE_LIMIT, logger::info, logger::warn);
            } catch (IOException | RuntimeException failure) {
                logger.warn("Starting the bundled CultAC runtime; the update settings are unusable: " + failure);
            }
            engine.loadInitial(getClass().getResourceAsStream("/cult-runtime/default.jar"), bootBuild);
            engine.startInitial();
            proxy.getScheduler()
                    .buildTask(this, engine::tick)
                    .repeat(java.time.Duration.ofMillis(50))
                    .schedule();
            GrimAPIProvider.init((GrimAbstractAPI) engine.publicApi());
            command = new SimpleCommand() {
                public void execute(Invocation invocation) {
                    var source = invocation.source();
                    var args = invocation.arguments();
                    if (args.length > 0
                            && (args[0].equalsIgnoreCase("update") || args[0].equalsIgnoreCase("devswap"))) {
                        boolean devSwap = args[0].equalsIgnoreCase("devswap");
                        if (!source.hasPermission(devSwap ? "cult.devswap" : "cult.update")) {
                            source.sendMessage(net.kyori.adventure.text.Component.text("You do not have permission."));
                            return;
                        }
                        if (!devSwap) {
                            update(source, args);
                            return;
                        }
                        if (args.length != (devSwap ? 1 : 2)) {
                            source.sendMessage(net.kyori.adventure.text.Component.text(
                                    "Usage: /" + primaryRoot() + (devSwap ? " devswap" : " update <filename.jar>")));
                            return;
                        }
                        BuildUpdates.installFile(engine, "CultAC-velocity.jar", null, UPDATE_WORKER)
                                .thenAccept(line -> tell(source, line, updateColor(line)));
                    } else if (args.length > 0 && args[0].equalsIgnoreCase("runtimes")) {
                        if (!source.hasPermission("cult.runtimes")) {
                            source.sendMessage(net.kyori.adventure.text.Component.text("You do not have permission."));
                            return;
                        }
                        runtimes(source, args);
                    } else {
                        String name = CommandRouting.target(args);
                        Object target =
                                name == null ? source : proxy.getPlayer(name).orElse(null);
                        engine.execute(
                                source,
                                primaryRoot() + " " + String.join(" ", args),
                                target,
                                CommandRouting.aggregate(args));
                    }
                }

                public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
                    return engine.suggest(
                                    invocation.source(), primaryRoot() + " " + String.join(" ", invocation.arguments()))
                            .toCompletableFuture();
                }
            };
            engine.onActivated(this::registerCommandRoots);
            registerCommandRoots();
        } catch (Throwable failure) {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            engine = null;
            logger.error("CultAC is disabled and players join uninspected: it failed to initialize.", failure);
        }
    }

    private synchronized void registerCommandRoots() {
        List<String> fresh = engine.commandRoots().stream()
                .filter(root -> !registeredRoots.contains(root))
                .toList();
        if (fresh.isEmpty()) return;
        var metadata = proxy.getCommandManager()
                .metaBuilder(fresh.getFirst())
                .aliases(fresh.subList(1, fresh.size()).toArray(String[]::new))
                .plugin(this)
                .build();
        proxy.getCommandManager().register(metadata, command);
        registeredRoots.addAll(fresh);
    }

    private String primaryRoot() {
        return engine.commandRoots().getFirst();
    }

    @Subscribe
    public EventTask login(PostLoginEvent event) {
        if (engine == null) return null;
        var player = event.getPlayer();
        try {
            var channel = (Channel) channelMethod.invoke(connectionMethod.invoke(player));
            channel.closeFuture().addListener(ignored -> engine.disconnect(channel));
            try {
                bridgeGuard.install(channel);
            } catch (RuntimeException failure) {
                refuse(player, "Cannot guard the bridge channel of " + player.getUsername(), failure);
                return null;
            }
            return EventTask.resumeWhenComplete(CompletableFuture.runAsync(
                            () -> {
                                if (!channel.isActive()) return;
                                try {
                                    engine.attach(channel, player);
                                } catch (Exception failure) {
                                    throw new CompletionException(failure);
                                }
                            },
                            channel.eventLoop())
                    .exceptionally(failure -> {
                        if (!channel.isActive()) return null;
                        if (stopping) player.disconnect(REFUSED);
                        else refuse(player, "Cannot inspect " + player.getUsername(), failure);
                        return null;
                    }));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            refuse(player, "Cannot access the Velocity client channel of " + player.getUsername(), failure);
            return null;
        }
    }

    private void refuse(Player player, String problem, Throwable failure) {
        if (engine.failsOpen()) {
            logger.warn(problem + "; letting them join uninspected", failure);
            return;
        }
        logger.error(problem + "; disconnecting", failure);
        player.disconnect(REFUSED);
    }

    private UpdateSettings updateSettings() {
        return UpdateSettings.read(directory, CultBuildVersion.REPOSITORY, System::getenv);
    }

    /**
     * {@code /cult update <build> | latest [channel] | builds [channel] | <filename.jar>}. A build install answers the
     * sender at once and again with its outcome, which is also logged and posted to the update webhook; the network
     * work runs off the proxy's threads.
     */
    private void update(CommandSource source, String[] args) {
        String target = args.length > 1 ? args[1] : "";
        if (args.length == 2 && target.endsWith(".jar")) {
            Path stateFile = null;
            String unusable = "";
            try {
                stateFile = updateSettings().stateFile();
            } catch (RuntimeException failure) {
                unusable = "; the next boot may start the last installed build instead: " + failure;
            }
            String note = unusable;
            BuildUpdates.installFile(engine, target, stateFile, UPDATE_WORKER)
                    .thenApply(line -> line.startsWith("Activated") ? line + note : line)
                    .thenAccept(line -> tell(source, line, updateColor(line)));
            return;
        }
        UpdateSettings settings;
        try {
            settings = updateSettings();
        } catch (RuntimeException unusable) {
            if (!(source instanceof ConsoleCommandSource)) logger.warn("update refused: " + unusable);
            tell(source, "update refused: " + unusable, NamedTextColor.RED);
            return;
        }
        var updates = new BuildUpdates(settings, bundledVersion);
        if (args.length == 2 && target.matches("[0-9]{1,18}")) {
            tell(source, "Updating this proxy to b" + target + ": downloading and verifying...", NamedTextColor.GRAY);
            report(source, settings, updates.install(engine, Long.parseLong(target), UPDATE_WORKER));
        } else if ((args.length == 2 || args.length == 3) && target.equalsIgnoreCase("latest")) {
            tell(source, "Updating this proxy to the latest build: downloading and verifying...", NamedTextColor.GRAY);
            report(
                    source,
                    settings,
                    updates.latest(engine, args.length == 3 ? args[2] : CultBuildVersion.BRANCH, UPDATE_WORKER));
        } else if ((args.length == 2 || args.length == 3) && target.equalsIgnoreCase("builds")) {
            updates.builds(args.length == 3 ? args[2] : null, engine.currentBuild(), UPDATE_WORKER)
                    .thenAccept(lines -> lines.forEach(line -> tell(source, line, NamedTextColor.WHITE)));
        } else {
            tell(source, "Usage: /" + primaryRoot() + UPDATE_USAGE, NamedTextColor.RED);
        }
    }

    private void report(CommandSource source, UpdateSettings settings, CompletionStage<String> result) {
        result.exceptionally(failure -> "update refused: " + failure).thenAccept(line -> {
            if (!(source instanceof ConsoleCommandSource)) logger.info(line);
            boolean installed = line.contains(" → b");
            tell(
                    source,
                    line,
                    installed
                            ? NamedTextColor.GREEN
                            : line.startsWith("already on") ? NamedTextColor.YELLOW : NamedTextColor.RED);
            if (installed)
                tell(
                        source,
                        "Players move to the new build as they switch servers; /" + primaryRoot()
                                + " runtimes shows how many are left.",
                        NamedTextColor.GRAY);
            webhook.post(settings.webhook(), line, logger::warn);
        });
    }

    private static NamedTextColor updateColor(String line) {
        if (line.startsWith("Activated")) return NamedTextColor.GREEN;
        return line.startsWith("Already running") ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }

    private static void tell(CommandSource source, String line, NamedTextColor color) {
        source.sendMessage(Component.text("[CultAC] ", NamedTextColor.DARK_AQUA).append(Component.text(line, color)));
    }

    /** {@code /cult runtimes [id [command]]}; commands run as the sender, once per player of that runtime. */
    private void runtimes(com.velocitypowered.api.command.CommandSource source, String[] args) {
        if (args.length == 1) {
            engine.status().forEach(line -> source.sendMessage(net.kyori.adventure.text.Component.text(line)));
            return;
        }
        try {
            var names = engine.players(args[1]).stream()
                    .map(proxy::getPlayer)
                    .flatMap(java.util.Optional::stream)
                    .map(com.velocitypowered.api.proxy.Player::getUsername)
                    .toList();
            if (args.length == 2) {
                source.sendMessage(net.kyori.adventure.text.Component.text(engine.status(args[1])));
                source.sendMessage(net.kyori.adventure.text.Component.text("Players: " + String.join(", ", names)));
                return;
            }
            for (String command : CommandRouting.perPlayer(args, names))
                proxy.getCommandManager().executeAsync(source, command);
            source.sendMessage(net.kyori.adventure.text.Component.text(
                    "Ran the command for " + names.size() + " player(s) on runtime " + args[1]));
        } catch (IllegalArgumentException unknown) {
            source.sendMessage(net.kyori.adventure.text.Component.text(unknown.getMessage()));
        }
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) throws Exception {
        stopping = true;
        if (engine != null) {
            Path installed = proxy.getPluginManager()
                    .fromInstance(this)
                    .orElseThrow()
                    .getDescription()
                    .getSource()
                    .orElseThrow();
            engine.shutdown(installed).toCompletableFuture().join();
        }
    }
}
