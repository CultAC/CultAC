package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.NetworkPlayerArguments;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.floodgate.GeyserUtil;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;

public final class CultGeyserPacketLog implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(BuildableCommand.root(manager)
                .literal("debug")
                .literal("geyserpacketlog")
                .permission("cult.debug")
                .optional("username", NetworkPlayerArguments.playerName(StringParser.quotedStringParser()))
                .flag(manager.flagBuilder("onjoin"))
                .futureHandler(this::handle));
    }

    /** Completes once the packet log's feedback was sent. */
    private CompletableFuture<Void> handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (!GeyserUtil.isGeyserAvailable()) {
            sender.sendMessage(Component.text("Local Geyser is unavailable."));
            return CompletableFuture.completedFuture(null);
        }
        // Without a username, capture the player running the command.
        String username = context.getOrDefault("username", sender.isPlayer() ? sender.getName() : "");
        if (username.isBlank()) {
            sender.sendMessage(Component.text("Specify a Bedrock username."));
            return CompletableFuture.completedFuture(null);
        }
        boolean onJoin = context.flags().isPresent("onjoin");
        PlatformPlayer player =
                onJoin ? null : CultAPI.INSTANCE.getPlatformPlayerFactory().getFromName(username);
        if (!onJoin && player == null) {
            sender.sendMessage(Component.text(
                    "Player is offline. Use --onjoin with their Bedrock username to capture their next join."));
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> answered = new CompletableFuture<>();
        try {
            GeyserBedrockBridgeRuntime.configurePacketLog(
                    username,
                    player == null ? null : player.getUniqueId(),
                    onJoin,
                    message -> {
                        sender.sendMessage(Component.text(message));
                        answered.complete(null);
                    });
        } catch (RuntimeException | LinkageError failure) {
            sender.sendMessage(Component.text(
                    "Geyser packet logger is unavailable: " + failure.getClass().getSimpleName()));
            answered.complete(null);
        }
        return answered;
    }
}
