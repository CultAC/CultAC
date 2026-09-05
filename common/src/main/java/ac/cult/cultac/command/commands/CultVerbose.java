package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class CultVerbose implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("cult", "cultac", "grim", "grimac")
                        .literal("verbose")
                        .permission("cult.verbose")
                        .handler(this::handleVerbose)
        );
    }

    private void handleVerbose(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (sender.isPlayer()) {
            PlatformPlayer player = Objects.requireNonNull(context.sender().getPlatformPlayer(), "player");
            AlertManagerImpl am = CultAPI.INSTANCE.getAlertManager();
            boolean newState = !am.hasVerboseEnabled(player);
            am.setVerboseEnabled(player, newState, false);
            PlayerToggleStore toggles = CultAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore();
            toggles.applyUserToggle(player.getUniqueId(), PlayerToggleStore.KEY_VERBOSE, newState);
            // setVerboseEnabled(true) cascades to setAlertsEnabled(true) in AlertManager
            // — mirror that into the toggle store so the persisted alerts row tracks the
            // implied state, otherwise a verbose-on staff member would re-toggle alerts
            // off on next reconnect when persisted alerts is still false.
            if (newState) toggles.applyUserToggle(player.getUniqueId(), PlayerToggleStore.KEY_ALERTS, true);
        } else if (sender.isConsole()) {
            CultAPI.INSTANCE.getAlertManager().toggleConsoleVerbose();
        }
    }
}
