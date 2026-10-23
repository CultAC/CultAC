package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.requirements.PlayerSenderRequirement;
import ac.cult.cultac.manager.SpectateManager;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class CultSpectate implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("spectate")
                .permission("cult.spectate")
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleSpectate)
                .apply(CloudCommandService.REQUIREMENT_FACTORY.create(PlayerSenderRequirement.INSTANCE)));
    }

    private void handleSpectate(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector targetSelectorResults = context.getOrDefault("target", null);
        if (targetSelectorResults == null) return;

        PlatformPlayer targetPlatformPlayer =
                targetSelectorResults.getSinglePlayer().getPlatformPlayer();

        if (targetPlatformPlayer != null && targetPlatformPlayer.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "cannot-run-on-self", "%prefix% &cYou cannot use this command on yourself!"));
            return;
        }

        @NotNull PlatformPlayer platformPlayer = Objects.requireNonNull(sender.getPlatformPlayer(), "platformPlayer");
        PlatformPlayer target = Objects.requireNonNull(targetPlatformPlayer, "targetPlatformPlayer");
        Runnable sendReturnLink = () -> sender.sendMessage(
                MessageUtil.getParsedComponent(
                        sender,
                        "spectate-return",
                        "<click:run_command:/%command% stopspectating><hover:show_text:\"/%command% stopspectating\">\n%prefix% &fClick here to return to previous location\n</hover></click>"));
        CultAPI.INSTANCE
                .getSpectateManager()
                .spectate(platformPlayer, target, sendReturnLink)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        LogUtil.error("Spectating " + target.getName() + " failed", failure);
                        sender.sendMessage(Component.text("Teleport failed, please try again.", NamedTextColor.RED));
                        return;
                    }
                    switch (result.outcome()) {
                        case UNSUPPORTED ->
                            sender.sendMessage(
                                    Component.text(
                                            "Spectating requires server-side game-mode changes and teleports, which this platform cannot perform."));
                        case CONNECT_FAILED ->
                            sender.sendMessage(Component.text(
                                    "Could not connect you to " + target.getName() + "'s server, please try again.",
                                    NamedTextColor.RED));
                        case TELEPORT_FAILED ->
                            sender.sendMessage(
                                    Component.text("Teleport failed, please try again.", NamedTextColor.RED));
                        case ALREADY_CONNECTING ->
                            sender.sendMessage(
                                    Component.text(SpectateManager.ALREADY_CONNECTING_MESSAGE, NamedTextColor.RED));
                        case STATE_UNAVAILABLE ->
                            sender.sendMessage(Component.text(
                                    "Could not read your game mode and position from the server, so spectating did not"
                                            + " start. Please try again.",
                                    NamedTextColor.RED));
                        case SPECTATING -> {}
                    }
                });
    }
}
