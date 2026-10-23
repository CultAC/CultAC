package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
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
                .optional("username", StringParser.quotedStringParser())
                .flag(manager.flagBuilder("onjoin"))
                .handler(this::handle));
    }

    private void handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        var api = CultAPI.INSTANCE;
        var network = api.getNetworkManager();
        var logger = network.getJavaPacketLogger();
        if (logger == null) {
            sender.sendMessage(Component.text("Java packet logger is unavailable."));
            return;
        }
        // Without a username, capture the player running the command.
        String username = context.getOrDefault("username", sender.isPlayer() ? sender.getName() : "");
        if (username.isBlank()) {
            sender.sendMessage(Component.text("Specify a Java-side username."));
            return;
        }
        if (context.flags().isPresent("onjoin")) {
            if (api.getPlatformPlayerFactory().getFromName(username) != null
                    || network.connections().stream()
                            .anyMatch(connection -> connection.user() != null
                                    && username.equalsIgnoreCase(
                                            connection.user().getName()))) {
                sender.sendMessage(
                        Component.text("Player is already connected. Toggle their capture without --onjoin."));
                return;
            }
            sender.sendMessage(Component.text(logger.arm(username)));
            return;
        }
        var player = api.getPlatformPlayerFactory().getFromName(username);
        if (player == null) {
            sender.sendMessage(Component.text(
                    "Player is offline. Use --onjoin with their Java-side profile name to capture their next join."));
            return;
        }
        var user = network.getUser(player);
        if (user == null) {
            sender.sendMessage(Component.text("Player has no active Java packet connection."));
            return;
        }
        user.execute(() -> logger.toggle(
                user.getCultConnection(),
                message -> api.getScheduler().getGlobalRegionScheduler().run(api.getGrimPlugin(), () -> {
                    if (sender.isValid()) sender.sendMessage(Component.text(message));
                })));
    }
}
