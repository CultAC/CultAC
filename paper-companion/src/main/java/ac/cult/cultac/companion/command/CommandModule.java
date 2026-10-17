package ac.cult.cultac.companion.command;

import ac.cult.cultac.bridge.protocol.BridgeMessage.Command;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.companion.api.ApiModule;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/** Registers the /cult command family and forwards it to the proxy. */
public final class CommandModule {
    private static final long SUGGEST_WAIT_MILLIS = 200;
    static final Component REFUSED = Component.text(
            "Only players and the server console can run CultAC commands through the proxy", NamedTextColor.RED);
    static final Component SENT_TO_LOG =
            Component.text("Sent to the CultAC proxy; the output goes to this server's log");
    private static final long SWEEP_TICKS = 20;

    private static CommandRelay relay;
    private static Listener completions;
    private static BukkitTask sweeper;

    private CommandModule() {}

    public static void enable(JavaPlugin plugin, CompanionBridge bridge) {
        CommandRelay created = new CommandRelay(
                bridge,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
                plugin.getLogger(),
                plugin.getConfig().getBoolean("debug", false));
        bridge.addCapabilities(ServerCapability.COMMANDS.flag());
        bridge.addHandler(created);
        CommandExecutor executor = (sender, command, label, args) -> {
            execute(created, plugin, sender, label, args);
            return true;
        };
        Objects.requireNonNull(plugin.getCommand("cult"), "cult command").setExecutor(executor);
        Listener listener = new Listener() {
            @EventHandler
            public void onTabComplete(AsyncTabCompleteEvent event) {
                complete(created, event);
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        ApiModule.reloadVia(() -> created.forward(null, console.getName(), "cult reload", console::sendMessage));
        relay = created;
        completions = listener;
        if (bridge.isEnabled()) {
            sweeper =
                    Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, created::sweep, SWEEP_TICKS, SWEEP_TICKS);
        }
    }

    public static void disable() {
        if (relay == null) return;
        ApiModule.reloadVia(null);
        if (sweeper != null) sweeper.cancel();
        HandlerList.unregisterAll(completions);
        relay.closeAll();
        relay = null;
        completions = null;
        sweeper = null;
    }

    static void execute(CommandRelay relay, Plugin plugin, CommandSender sender, String label, String[] args) {
        String root = label.substring(label.indexOf(':') + 1);
        String line = args.length == 0 ? root : root + ' ' + String.join(" ", args);
        UUID id = forwardedAs(sender);
        if (id == null) {
            sender.sendMessage(REFUSED);
            return;
        }
        UUID forwarded = Command.CONSOLE.equals(id) ? null : id;
        if (!(sender instanceof RemoteConsoleCommandSender)) {
            relay.forward(forwarded, sender.getName(), line, output(plugin, sender));
            return;
        }
        boolean[] refused = {false};
        relay.forward(forwarded, sender.getName(), line, output(plugin, sender), reason -> {
            refused[0] = true;
            sender.sendMessage(reason);
        });
        if (!refused[0]) sender.sendMessage(SENT_TO_LOG);
    }

    /**
     * Who the proxy runs a command as: a player as itself, the console or RCON as {@link Command#CONSOLE}, and null
     * (refused) for every other sender, such as command blocks, entities under {@code /execute} and plugin senders.
     */
    static @Nullable UUID forwardedAs(CommandSender sender) {
        if (sender instanceof Player player) return player.getUniqueId();
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            return Command.CONSOLE;
        }
        return null;
    }

    /**
     * Console output is thread-safe; RCON output goes to the server log, as the RCON reply is already sent when the proxy
     * answers; every other sender hears back on the main thread, in order.
     */
    private static Consumer<Component> output(Plugin plugin, CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) return sender::sendMessage;
        if (sender instanceof RemoteConsoleCommandSender) {
            Logger logger = plugin.getLogger();
            return message ->
                    logger.info(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return message -> {
            if (Bukkit.isPrimaryThread()) sender.sendMessage(message);
            else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(message));
        };
    }

    /** Serves the console only; players complete against the proxy's command tree. */
    private static void complete(CommandRelay relay, AsyncTabCompleteEvent event) {
        if (!event.isAsynchronous() || event.isHandled() || !(event.getSender() instanceof ConsoleCommandSender)) {
            return;
        }
        String buffer = event.getBuffer();
        if (buffer.startsWith("/")) buffer = buffer.substring(1);
        int space = buffer.indexOf(' ');
        if (space < 0) return;
        String root = buffer.substring(0, space);
        root = root.substring(root.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (!BridgeProtocol.COMMAND_ROOTS.contains(root)) return;
        List<String> suggestions = relay.suggest(root + buffer.substring(space), SUGGEST_WAIT_MILLIS);
        if (suggestions == null) return;
        event.completions(suggestions.stream()
                .map(AsyncTabCompleteEvent.Completion::completion)
                .toList());
        event.setHandled(true);
    }
}
