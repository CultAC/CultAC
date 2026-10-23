package ac.cult.loader.bukkit;

import ac.cult.reload.host.*;
import ac.grim.grimac.api.*;
import io.netty.channel.*;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.util.*;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Native plugin identity and registrations survive every engine update. */
public final class CultACBootstrapPlugin extends JavaPlugin implements TabExecutor {
    private static final Key INJECTOR = Key.key("cultac", "injector");
    private GenerationManager runtimes;
    private BukkitLegacyEvents legacyEvents;
    private CultPlaceholderExpansion placeholders;
    private final Set<String> registeredRoots = new HashSet<>();

    @Override
    public void onLoad() {
        try {
            runtimes = new GenerationManager(
                    getDataFolder().toPath(),
                    "paper",
                    this,
                    Bukkit.getServer(),
                    getLogger(),
                    getClass().getClassLoader(),
                    getLogger()::info,
                    Bukkit.getMinecraftVersion(),
                    LoaderVersions.read(getResource("plugin.yml"), "paper"));
            runtimes.connectionResolver(ac.cult.reload.integrations.GeyserConnections::upstream);
            runtimes.loadInitial(getResource("cult-runtime/default.jar"));
            ChannelInitializeListenerHolder.addListener(INJECTOR, channel -> {
                channel.closeFuture().addListener(ignored -> runtimes.disconnect(channel));
                channel.pipeline().addFirst("cult-runtime-admission", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object frame) throws Exception {
                        // Geyser's downstream identity is available before its first handshake.
                        try {
                            // Geyser also opens a local dummy status connection at startup.
                            // Its real downstreams already belong to a pinned Bedrock session.
                            boolean localGeyser = ctx.channel() instanceof io.netty.channel.local.LocalChannel
                                    && Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot");
                            runtimes.attach(ctx.channel(), null, localGeyser);
                        } catch (Throwable failure) {
                            io.netty.util.ReferenceCountUtil.release(frame);
                            ctx.close();
                            getLogger()
                                    .log(java.util.logging.Level.SEVERE, "CultAC session attachment failed", failure);
                            return;
                        }
                        ctx.pipeline().remove(this);
                        ctx.fireChannelRead(frame);
                    }
                });
            });
            var service = (GrimAbstractAPI) runtimes.publicApi();
            GrimAPIProvider.init(service);
            legacyEvents = new BukkitLegacyEvents(service, this);
            Bukkit.getServicesManager().register(GrimAbstractAPI.class, service, this, ServicePriority.Normal);
        } catch (Exception failure) {
            throw new IllegalStateException("CultAC bootstrap failed", failure);
        }
    }

    @Override
    public void onEnable() {
        try {
            runtimes.startInitial();
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                placeholders = new CultPlaceholderExpansion(this, runtimes);
                placeholders.register();
            }
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, ignored -> runtimes.tick(), 1, 1);
            runtimes.onActivated(() -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                if (registerCommandRoots()) Bukkit.getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands);
            }));
            registerCommandRoots();
        } catch (Exception failure) {
            throw new IllegalStateException("CultAC startup failed", failure);
        }
    }

    private boolean registerCommandRoots() {
        List<String> fresh = runtimes.commandRoots().stream()
                .filter(root -> !registeredRoots.contains(root))
                .toList();
        if (fresh.isEmpty()) return false;
        var command =
                new Command(
                        fresh.getFirst(),
                        "CultAC administration",
                        "/" + fresh.getFirst(),
                        new ArrayList<>(fresh.subList(1, fresh.size()))) {
                    @Override
                    public boolean execute(CommandSender sender, String label, String[] args) {
                        return onCommand(sender, this, label, args);
                    }

                    @Override
                    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                        return onTabComplete(sender, this, alias, args);
                    }
                };
        Bukkit.getCommandMap().register(getName().toLowerCase(Locale.ROOT), command);
        registeredRoots.addAll(fresh);
        return true;
    }

    private String primaryRoot() {
        return runtimes.commandRoots().getFirst();
    }

    @Override
    public void onDisable() {
        ChannelInitializeListenerHolder.removeListener(INJECTOR);
        if (placeholders != null) placeholders.unregister();
        try {
            if (runtimes != null)
                runtimes.shutdown(installedPlugin()).toCompletableFuture().join();
        } finally {
            if (legacyEvents != null) legacyEvents.close();
        }
    }

    private java.nio.file.Path installedPlugin() {
        var loaded = getFile().toPath().toAbsolutePath();
        if (loaded.getParent().getFileName().toString().equals(".paper-remapped")) {
            var source = loaded.getParent().getParent().resolve(loaded.getFileName());
            if (java.nio.file.Files.isRegularFile(source)) return source;
        }
        return loaded;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("update")) {
            if (!sender.hasPermission("cult.update")) {
                sender.sendMessage("You do not have permission.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage("Usage: /" + primaryRoot() + " update <filename.jar>");
                return true;
            }
            runtimes.update(args[1])
                    .whenComplete((message, error) ->
                            sender.sendMessage(error == null ? message : "Update failed: " + error.getMessage()));
        } else if (args.length > 0 && args[0].equalsIgnoreCase("runtimes")) {
            if (!sender.hasPermission("cult.runtimes")) {
                sender.sendMessage("You do not have permission.");
                return true;
            }
            runtimes(sender, args);
        } else {
            var targetName = CommandRouting.target(args);
            Object target = targetName == null ? sender : Bukkit.getPlayerExact(targetName);
            runtimes.execute(
                    sender, primaryRoot() + " " + String.join(" ", args), target, CommandRouting.aggregate(args));
        }
        return true;
    }

    /** {@code /cult runtimes [id [command]]}; commands run as the sender, once per player of that runtime. */
    private void runtimes(CommandSender sender, String[] args) {
        if (args.length == 1) {
            runtimes.status().forEach(sender::sendMessage);
            return;
        }
        try {
            var names = runtimes.players(args[1]).stream()
                    .map(Bukkit::getPlayer)
                    .filter(java.util.Objects::nonNull)
                    .map(org.bukkit.entity.Player::getName)
                    .toList();
            if (args.length == 2) {
                sender.sendMessage(runtimes.status(args[1]));
                sender.sendMessage("Players: " + String.join(", ", names));
                return;
            }
            for (String line : CommandRouting.perPlayer(args, names)) Bukkit.dispatchCommand(sender, line);
            sender.sendMessage("Ran the command for " + names.size() + " player(s) on runtime " + args[1]);
        } catch (IllegalArgumentException unknown) {
            sender.sendMessage(unknown.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return runtimes.suggest(sender, primaryRoot() + " " + String.join(" ", args))
                .toCompletableFuture()
                .getNow(List.of());
    }
}
