package ac.cult.cultac.command;

import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.List;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;

public interface BuildableCommand {
    void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments);

    static Command.Builder<Sender> root(CommandManager<Sender> manager) {
        List<String> roots = CloudCommandService.roots();
        return manager.commandBuilder(
                roots.getFirst(), roots.subList(1, roots.size()).toArray(String[]::new));
    }
}
