package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.requirements.PlayerSenderRequirement;
import ac.cult.cultac.manager.SpectateManager;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

public class CultStopSpectating implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("stopspectating")
                .permission("cult.spectate")
                .optional("here", StringParser.stringParser(), SuggestionProvider.blocking((ctx, in) -> {
                    if (ctx.sender().hasPermission("cult.spectate.stophere")) {
                        return List.of(Suggestion.suggestion("here"));
                    }
                    return List.of();
                }))
                .handler(this::onStopSpectate)
                .apply(CloudCommandService.REQUIREMENT_FACTORY.create(PlayerSenderRequirement.INSTANCE)));
    }

    public void onStopSpectate(CommandContext<Sender> commandContext) {
        Sender sender = commandContext.sender();
        String string = commandContext.getOrDefault("here", null);
        if (CultAPI.INSTANCE.getSpectateManager().isConnecting(sender.getUniqueId())) {
            sender.sendMessage(Component.text(SpectateManager.ALREADY_CONNECTING_MESSAGE, NamedTextColor.RED));
        } else if (CultAPI.INSTANCE.getSpectateManager().isSpectating(sender.getUniqueId())) {
            boolean teleportBack = string == null
                    || !string.equalsIgnoreCase("here")
                    || !sender.hasPermission("cult.spectate.stophere");
            PlatformPlayer player = Objects.requireNonNull(sender.getPlatformPlayer(), "player");
            CultAPI.INSTANCE.getSpectateManager().disable(player, teleportBack);
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "cannot-spectate-return", "%prefix% &cYou can only do this after spectating a player."));
        }
    }
}
