package ac.cult.cultac.command.commands;

import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.floodgate.GeyserUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;

public final class CultGeyserPacketLog implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(manager.commandBuilder("cult", "cultac", "grim", "grimac")
                .literal("debug").literal("geyserpacketlog")
                .permission("cult.debug")
                .required("username", StringParser.quotedStringParser())
                .flag(manager.flagBuilder("onjoin"))
                .handler(this::handle));
    }

    private void handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (!GeyserUtil.isGeyserAvailable()) {
            sender.sendMessage(Component.text("Local Geyser is unavailable."));
            return;
        }
        String username = context.get("username");
        if (username.isBlank()) {
            sender.sendMessage(Component.text("Specify a Bedrock username."));
            return;
        }
        boolean onJoin = context.flags().isPresent("onjoin");
        Player player = onJoin ? null : Bukkit.getPlayerExact(username);
        if (!onJoin && player == null) {
            sender.sendMessage(Component.text("Player is offline. Use --onjoin with their Bedrock username to capture their next join."));
            return;
        }
        try {
            GeyserBedrockBridgeRuntime.configurePacketLog(username, player == null ? null : player.getUniqueId(),
                    onJoin, message -> sender.sendMessage(Component.text(message)));
        } catch (RuntimeException | LinkageError failure) {
            sender.sendMessage(Component.text("Geyser packet logger is unavailable: " + failure.getClass().getSimpleName()));
        }
    }
}
