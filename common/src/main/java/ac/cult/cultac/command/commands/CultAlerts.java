package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.jetbrains.annotations.NotNull;

public class CultAlerts implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command.Builder<Sender> alerts = BuildableCommand.root(commandManager)
                .literal("alerts", Description.of("Toggle alerts for the sender"))
                .permission("cult.alerts");
        commandManager.command(alerts.handler(this::handleAlerts));
        Command.Builder<Sender> scope =
                alerts.literal("scope", Description.of("Show or set whose alerts reach the sender"));
        commandManager.command(scope.handler(context -> showScope(context.sender())));
        for (AlertScope each : AlertScope.values()) {
            commandManager.command(scope.literal(each.id()).handler(context -> chooseScope(context.sender(), each)));
        }
    }

    private void handleAlerts(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (sender.isPlayer()) {
            PlatformPlayer player = Objects.requireNonNull(context.sender().getPlatformPlayer(), "player");
            AlertManagerImpl am = CultAPI.INSTANCE.getAlertManager();
            boolean newState = !am.hasAlertsEnabled(player);
            am.setAlertsEnabled(player, newState, false);
            CultAPI.INSTANCE
                    .getDataStoreLifecycle()
                    .playerToggleStore()
                    .applyUserToggle(player.getUniqueId(), PlayerToggleStore.KEY_ALERTS, newState);
            sender.sendMessage(scopeLine(sender, player));
        } else if (sender.isConsole()) {
            CultAPI.INSTANCE.getAlertManager().toggleConsoleAlerts();
        }
    }

    static void showScope(Sender sender) {
        PlatformPlayer player = sender.isPlayer() ? sender.getPlatformPlayer() : null;
        if (player == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "run-as-player", "%prefix% &cThis command can only be used by players!"));
            return;
        }
        sender.sendMessage(scopeLine(sender, player));
    }

    /** Sets the sender's scope; {@code global} needs {@link AlertManagerImpl#networkAlerts}. */
    static void chooseScope(Sender sender, AlertScope scope) {
        PlatformPlayer player = sender.isPlayer() ? sender.getPlatformPlayer() : null;
        if (player == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "run-as-player", "%prefix% &cThis command can only be used by players!"));
            return;
        }
        AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
        if (scope == AlertScope.GLOBAL && !alerts.networkAlerts()) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "network-disabled-alerts",
                    "%prefix% &cNetwork-wide alerts are not enabled on this server. Ask a server administrator to"
                            + " configure a messaging backend (Redis or NATS) in the CultAC configuration."));
            return;
        }
        alerts.chooseScope(player, scope);
        sender.sendMessage(message(sender, "alerts-scope-set", "%prefix% &fAlert scope set to &b%scope%", scope, ""));
    }

    /** The scope that applies to {@code player} now and where it comes from. */
    static Component scopeLine(Sender sender, PlatformPlayer player) {
        AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
        String source;
        if (!alerts.networkAlerts()) {
            source = text("alerts-scope-local", "network-wide alerts are not enabled on this server");
        } else if (alerts.chosenScope(player) != null) {
            source = text("alerts-scope-chosen", "chosen");
        } else {
            source = text("alerts-scope-default", "default");
        }
        return message(
                sender, "alerts-scope", "%prefix% &fAlert scope: &b%scope% &7(%source%)", alerts.scope(player), source);
    }

    private static Component message(Sender sender, String key, String fallback, AlertScope scope, String source) {
        String raw = text(key, fallback).replace("%scope%", scope.id()).replace("%source%", source);
        return MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, raw));
    }

    private static String text(String key, String fallback) {
        return CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallback);
    }
}
