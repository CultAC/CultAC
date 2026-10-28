package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.data.webhook.discord.WebhookMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class CultTestWebhook implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("testwebhook")
                .permission("cult.testwebhook")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleTestWebhook));
    }

    private void handleTestWebhook(@NotNull CommandContext<Sender> context) {
        if (CultAPI.INSTANCE.getDiscordManager().isDisabled()) {
            context.sender()
                    .sendMessage(MessageUtil.miniMessage(
                            CultAPI.INSTANCE.getConfigManager().getWebhookNotEnabled()));
            return;
        }

        WebhookMessage webhookMessage =
                new WebhookMessage().content(CultAPI.INSTANCE.getConfigManager().getWebhookTestMessage());
        PlayerSelector selector = context.<PlayerSelector>optional("target").orElse(null);
        if (selector == null) {
            send(context, webhookMessage);
            return;
        }
        CultPlayer target = CultAPI.INSTANCE
                .getPlayerDataManager()
                .getPlayer(selector.getSinglePlayer().getUniqueId());
        if (target == null) {
            context.sender()
                    .sendMessage(Component.text("That player is exempt or not tracked by Cult.", NamedTextColor.RED));
            return;
        }
        target.runSafely(() -> send(
                context,
                webhookMessage.addEmbeds(
                        CultAPI.INSTANCE.getDiscordManager().alertEmbed(target, "test=true", "TestWebhook", 1))));
    }

    private void send(@NotNull CommandContext<Sender> context, WebhookMessage webhookMessage) {
        CultAPI.INSTANCE
                .getDiscordManager()
                .sendWebhookMessage(webhookMessage)
                .whenCompleteAsync(((successful, throwable) -> {
                    if (successful == true) {
                        context.sender()
                                .sendMessage(MessageUtil.miniMessage(
                                        CultAPI.INSTANCE.getConfigManager().getWebhookTestSucceeded()));
                        return;
                    }

                    context.sender()
                            .sendMessage(MessageUtil.miniMessage(
                                    CultAPI.INSTANCE.getConfigManager().getWebhookTestFailed()));
                }));
    }
}
