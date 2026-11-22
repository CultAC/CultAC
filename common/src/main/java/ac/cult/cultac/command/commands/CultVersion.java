package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class CultVersion implements BuildableCommand {

    public static void sendVersion(Sender sender) {
        String current = CultAPI.INSTANCE.getExternalAPI().getGrimVersion();
        sender.sendMessage(Component.text()
                .append(Component.text("Cult Version: ").color(NamedTextColor.GRAY))
                .append(Component.text(current).color(NamedTextColor.AQUA))
                .asComponent());
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            sender.sendMessage(Component.text("Observe-only: checks flag and alert but never act")
                    .color(NamedTextColor.YELLOW));
        }
    }

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("version")
                .permission("cult.version")
                .handler(this::handleVersion));
    }

    private void handleVersion(@NotNull CommandContext<Sender> context) {
        sendVersion(context.sender());
    }
}
