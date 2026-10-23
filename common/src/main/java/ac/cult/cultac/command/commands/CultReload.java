package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.GrimAbstractAPI;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class CultReload implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("reload")
                .permission("cult.reload")
                .handler(this::handleReload));
    }

    private void handleReload(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();

        sender.sendMessage(MessageUtil.getParsedComponent(sender, "reloading", "%prefix% &7Reloading config..."));

        reloadAsync().exceptionally(throwable -> false).thenAccept(bool -> {
            Component message = bool
                    ? MessageUtil.getParsedComponent(sender, "reloaded", "%prefix% &fConfig has been reloaded.")
                    : MessageUtil.getParsedComponent(sender, "reload-failed", "%prefix% &cFailed to reload config.");
            sender.sendMessage(message);
        });
    }

    /** Re-reads the on-disk config into every live generation. */
    static CompletableFuture<Boolean> reloadAsync() {
        var host = RuntimeEnvironment.host();
        return (host == null ? CultAPI.INSTANCE.getExternalAPI() : (GrimAbstractAPI) host.publicApi()).reloadAsync();
    }
}
