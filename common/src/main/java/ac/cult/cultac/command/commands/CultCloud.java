package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/** Read-only: the upload's sockets, held frames and counters. */
public class CultCloud implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(commandManager
                .commandBuilder("cult", "cultac", "grim", "grimac")
                .literal("cloud")
                .permission("cult.cloud")
                .handler(this::handleCloud));
    }

    private void handleCloud(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        for (String line : CloudModule.status()) sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
    }
}
