package ac.cult.velocity;

import ac.cult.reload.api.RuntimeInstance;
import ac.cult.reload.host.CommandRouting;
import ac.cult.reload.host.GenerationManager;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import com.google.inject.Inject;
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
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
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
            @Dependency(id = "viabackwards", optional = true)
        })
public final class CultVelocityPlugin {
    private static final Component REFUSED = Component.text(RuntimeInstance.CONNECTION_REFUSED);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private GenerationManager engine;
    private volatile boolean stopping;
    private java.lang.reflect.Method connectionMethod, channelMethod;

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
        } catch (ReflectiveOperationException failure) {
            logger.error(
                    "CultAC is disabled and players join uninspected: this Velocity build has no"
                            + " ConnectedPlayer.getConnection().getChannel(). Update CultAC for this Velocity build.",
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
            engine.loadInitial(getClass().getResourceAsStream("/cult-runtime/default.jar"));
            engine.startInitial();
            proxy.getScheduler()
                    .buildTask(this, engine::tick)
                    .repeat(java.time.Duration.ofMillis(50))
                    .schedule();
            GrimAPIProvider.init((GrimAbstractAPI) engine.publicApi());
            var metadata = proxy.getCommandManager()
                    .metaBuilder("cult")
                    .aliases("cultac", "grim", "grimac")
                    .plugin(this)
                    .build();
            proxy.getCommandManager().register(metadata, new SimpleCommand() {
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
                        if (args.length != (devSwap ? 1 : 2)) {
                            source.sendMessage(net.kyori.adventure.text.Component.text(
                                    devSwap ? "Usage: /cult devswap" : "Usage: /cult update <filename.jar>"));
                            return;
                        }
                        engine.update(devSwap ? "CultAC-velocity.jar" : args[1])
                                .whenComplete((message, failure) ->
                                        source.sendMessage(net.kyori.adventure.text.Component.text(
                                                failure == null ? message : "Update failed: " + failure.getMessage())));
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
                                source, "cult " + String.join(" ", args), target, CommandRouting.aggregate(args));
                    }
                }

                public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
                    return engine.suggest(invocation.source(), "cult " + String.join(" ", invocation.arguments()))
                            .toCompletableFuture();
                }
            });
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

    @Subscribe
    public EventTask login(PostLoginEvent event) {
        if (engine == null) return null;
        var player = event.getPlayer();
        try {
            var channel = (Channel) channelMethod.invoke(connectionMethod.invoke(player));
            channel.closeFuture().addListener(ignored -> engine.disconnect(channel));
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
                        if (channel.isActive()) {
                            if (!stopping)
                                logger.error("Cannot inspect " + player.getUsername() + "; disconnecting", failure);
                            player.disconnect(REFUSED);
                        }
                        return null;
                    }));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logger.error(
                    "Cannot access the Velocity client channel of " + player.getUsername() + "; disconnecting",
                    failure);
            player.disconnect(REFUSED);
            return null;
        }
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
