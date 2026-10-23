package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.findings.Finding;
import ac.cult.cultac.cloud.findings.FindingsLog;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;

/** {@code /cult findings <player> [count]}: the player's latest cloud findings, newest first. */
public class CultFindings implements BuildableCommand {
    static final int DEFAULT_COUNT = 15;

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("findings")
                .permission(CloudArguments.VIEW)
                .required("player", StringParser.stringParser(), CloudArguments.players())
                .optional("count", IntegerParser.integerParser(1, FindingsLog.CAPACITY))
                .handler(this::handle));
    }

    private void handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        FindingsLog log = CloudArguments.findings(sender);
        if (log == null || !CloudArguments.labelingAllowed(sender)) return;
        int count = context.getOrDefault("count", DEFAULT_COUNT);
        CloudArguments.resolve(sender, context.get("player"), target -> show(sender, log, target, count));
    }

    private static void show(Sender sender, FindingsLog log, CloudArguments.Target target, int count) {
        List<Finding> findings = log.recent(target.id(), count);
        if (findings.isEmpty()) {
            sender.sendMessage(Component.text(
                    "Nothing from the cloud has arrived for " + target.name() + ".", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(
                "Cloud findings for " + target.name() + " (" + findings.size() + ", newest first)",
                NamedTextColor.GRAY));
        long now = System.currentTimeMillis();
        for (Finding finding : findings) sender.sendMessage(FindingLines.line(finding, now));
    }
}
