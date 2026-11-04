package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.NetworkPlayerArguments;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;

public final class CultJavaPacketLog implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(BuildableCommand.root(manager)
                .literal("debug")
                .literal("javapacketlog")
                .permission("cult.debug")
                .optional("username", NetworkPlayerArguments.playerName(StringParser.quotedStringParser()))
                .flag(manager.flagBuilder("onjoin"))
                .futureHandler(this::handle));
    }

    /** Completes once the toggle's feedback was sent. */
    private CompletableFuture<Void> handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        var api = CultAPI.INSTANCE;
        var network = api.getNetworkManager();
        var logger = network.getJavaPacketLogger();
        if (logger == null) {
            sender.sendMessage(Component.text("Java packet logger is unavailable."));
            return CompletableFuture.completedFuture(null);
        }
        // Without a username, capture the player running the command.
        String username = context.getOrDefault("username", sender.isPlayer() ? sender.getName() : "");
        if (username.isBlank()) {
            sender.sendMessage(Component.text("Specify a Java-side username."));
            return CompletableFuture.completedFuture(null);
        }
        if (context.flags().isPresent("onjoin")) {
            if (api.getPlatformPlayerFactory().getFromName(username) != null
                    || network.connections().stream()
                            .anyMatch(connection -> connection.user() != null
                                    && username.equalsIgnoreCase(
                                            connection.user().getName()))) {
                sender.sendMessage(
                        Component.text("Player is already connected. Toggle their capture without --onjoin."));
                return CompletableFuture.completedFuture(null);
            }
            sender.sendMessage(Component.text(logger.arm(username)));
            return CompletableFuture.completedFuture(null);
        }
        var player = api.getPlatformPlayerFactory().getFromName(username);
        if (player == null) {
            sender.sendMessage(Component.text(
                    "Player is offline. Use --onjoin with their Java-side profile name to capture their next join."));
            return CompletableFuture.completedFuture(null);
        }
        var user = network.getUser(player);
        if (user == null) {
            sender.sendMessage(Component.text("Player has no active Java packet connection."));
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> answered = new CompletableFuture<>();
        user.execute(() -> logger.toggle(
                user.getCultConnection(),
                message -> api.getScheduler().getGlobalRegionScheduler().run(api.getGrimPlugin(), () -> {
                    if (sender.isValid()) sender.sendMessage(Component.text(message));
                    answered.complete(null);
                })));
        return answered;
    }
}
