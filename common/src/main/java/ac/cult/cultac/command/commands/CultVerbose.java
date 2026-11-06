package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.Objects;
import java.util.UUID;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class CultVerbose implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("verbose")
                .permission("cult.verbose")
                .handler(this::handleVerbose));
    }

    private void handleVerbose(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (sender.isPlayer()) {
            PlatformPlayer player = Objects.requireNonNull(context.sender().getPlatformPlayer(), "player");
            AlertManagerImpl am = CultAPI.INSTANCE.getAlertManager();
            boolean newState = !am.hasVerboseEnabled(player);
            am.setVerboseEnabled(player, newState, false);
            store(CultAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore(), player.getUniqueId(), newState);
        } else if (sender.isConsole()) {
            CultAPI.INSTANCE.getAlertManager().toggleConsoleVerbose();
        }
    }

    /**
     * Stores the verbose toggle. The alerts verbose turns on are stored only over an alerts toggle staff already
     * chose; otherwise the alerts join permission keeps deciding them.
     */
    static void store(PlayerToggleStore toggles, UUID uuid, boolean verbose) {
        toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_VERBOSE, verbose);
        if (verbose && toggles.current(uuid, PlayerToggleStore.KEY_ALERTS) != null) {
            toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_ALERTS, true);
        }
    }
}
