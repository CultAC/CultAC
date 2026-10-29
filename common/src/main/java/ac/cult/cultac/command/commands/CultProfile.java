package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.NetworkPlayerArguments;
import ac.cult.cultac.command.render.HistoryComponentRenderer;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.storage.history.HistoryService;
import ac.grim.grimac.api.storage.history.SessionSummary;
import ac.grim.grimac.api.storage.identity.NameResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a player's live profile. The command runs on the instance holding the player while the network runs; a player
 * not online on any instance it knows gets the last session the datastore holds.
 */
public class CultProfile implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("profile")
                .permission("cult.profile")
                // A name rather than an online-player selector: the player may be on another instance.
                .required(
                        "target",
                        NetworkPlayerArguments.playerName(StringParser.stringParser()),
                        arguments.onlinePlayerSuggestions())
                .handler(this::handleProfile));
    }

    private void handleProfile(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        String target = context.get("target");

        PlatformPlayer online = CultAPI.INSTANCE.getPlatformPlayerFactory().getFromName(target);
        if (online != null) {
            CultPlayer cultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(online.getUniqueId());
            if (cultPlayer == null) {
                sender.sendMessage(MessageUtil.getParsedComponent(
                        sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
                return;
            }
            render(cultPlayer).forEach(sender::sendMessage);
            return;
        }

        // Reading the datastore blocks on I/O.
        CultAPI.INSTANCE
                .getScheduler()
                .getAsyncScheduler()
                .runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> sendStored(sender, target));
    }

    /** The profile lines of a player tracked by this instance. */
    public static List<Component> render(@NotNull CultPlayer cultPlayer) {
        List<Component> lines = new ArrayList<>();
        for (String message : CultAPI.INSTANCE.getConfigManager().getConfig().getStringList("profile")) {
            final Component component = MessageUtil.miniMessage(message);
            lines.add(MessageUtil.replacePlaceholders(cultPlayer, component));
        }
        return lines;
    }

    /** The last session the datastore holds. */
    private void sendStored(Sender sender, String target) {
        SessionSummary session = null;
        try {
            session = lastSession(resolveUuid(target));
        } catch (Exception ignored) {
            // Shown below as unknown.
        }
        if (session == null) {
            sender.sendMessage(message(
                    "profile-unknown-player",
                    "%prefix% &c%player% is not online on this instance and has no stored session.",
                    Map.of("player", HistoryComponentRenderer.mmSafe(target))));
            return;
        }
        String server = session.serverName();
        String status = session.isClosed() ? "offline" : "online";
        long seen = session.isClosed() ? session.closedAtEpochMs() : System.currentTimeMillis();
        Map<String, String> values = Map.of(
                "player", HistoryComponentRenderer.mmSafe(target),
                "status", status,
                "server", HistoryComponentRenderer.mmSafe(server == null ? "unknown" : server),
                "version", HistoryComponentRenderer.clientVersionDisplay(session.clientVersion()),
                "brand",
                        HistoryComponentRenderer.mmSafe(
                                session.clientBrand() == null ? "unknown" : session.clientBrand()),
                "timeago", HistoryComponentRenderer.formatDuration(Math.max(0, System.currentTimeMillis() - seen)));
        List<String> template = CultAPI.INSTANCE.getConfigManager().getConfig().getStringList("profile-stored");
        if (template.isEmpty()) template = DEFAULT_STORED;
        for (String line : template) sender.sendMessage(format(line, values));
    }

    private static final List<String> DEFAULT_STORED = List.of(
            "&7======================",
            "&bProfile for &f%player% &7(last session)",
            "&bStatus: &f%status%",
            "&bServer: &f%server%",
            "&bVersion: &f%version%",
            "&bClient Brand: &f%brand%",
            "&bLast seen: &f%timeago% ago",
            "&7======================");

    private static @Nullable UUID resolveUuid(String name) throws Exception {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {
            // A name.
        }
        DataStoreLifecycle lifecycle = CultAPI.INSTANCE.getDataStoreLifecycle();
        if (lifecycle == null || !lifecycle.isLoaded()) return null;
        NameResolver resolver = lifecycle.nameResolver();
        if (resolver == null) return null;
        Optional<UUID> hit = resolver.resolveByName(name).toCompletableFuture().get(2, TimeUnit.SECONDS);
        return hit.orElse(null);
    }

    private static @Nullable SessionSummary lastSession(@Nullable UUID uuid) throws Exception {
        if (uuid == null) return null;
        DataStoreLifecycle lifecycle = CultAPI.INSTANCE.getDataStoreLifecycle();
        if (lifecycle == null || !lifecycle.isLoaded()) return null;
        HistoryService history = lifecycle.historyService();
        if (history == null) return null;
        // Sessions list newest first.
        var page = history.listSessions(uuid, null, 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        return page.items().isEmpty() ? null : page.items().get(0);
    }

    private static Component message(String key, String fallback, Map<String, String> values) {
        return format(CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallback), values);
    }

    private static Component format(String raw, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            raw = raw.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return MessageUtil.miniMessage(raw);
    }
}
