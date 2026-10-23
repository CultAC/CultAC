package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.findings.FindingsLog;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /cult top [check] [minutes]}: the players with the most confident cloud findings in the window, then the most
 * findings. A lone number is the minutes.
 */
public class CultTop implements BuildableCommand {
    static final int DEFAULT_MINUTES = 30;
    static final int MAX_MINUTES = 7 * 24 * 60;
    static final int LIMIT = 10;

    record Query(@Nullable String check, int minutes) {}

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("top")
                .permission(CloudArguments.VIEW)
                .optional("check", StringParser.stringParser(), CloudArguments.checks())
                .optional("minutes", IntegerParser.integerParser(1, MAX_MINUTES))
                .handler(this::handle));
    }

    static Query query(@Nullable String check, @Nullable Integer minutes) {
        if (check != null && minutes == null) {
            try {
                int lone = Integer.parseInt(check);
                if (lone >= 1 && lone <= MAX_MINUTES) return new Query(null, lone);
            } catch (NumberFormatException aCheck) {
            }
        }
        return new Query(check, minutes == null ? DEFAULT_MINUTES : minutes);
    }

    private void handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        FindingsLog log = CloudArguments.findings(sender);
        if (log == null) return;
        Query query = query(
                context.<String>optional("check").orElse(null),
                context.<Integer>optional("minutes").orElse(null));
        long since = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(query.minutes());
        List<FindingsLog.Ranked> top = log.top(query.check(), since, LIMIT);
        String scope = (query.check() == null ? "all checks" : query.check()) + ", last " + query.minutes() + "m";
        if (top.isEmpty()) {
            sender.sendMessage(Component.text(
                    "Nothing from the cloud has arrived in the window (" + scope + ").", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(
                Component.text("Top players by cloud confidence, then count (" + scope + ")", NamedTextColor.GRAY));
        int place = 1;
        for (FindingsLog.Ranked ranked : top) sender.sendMessage(FindingLines.ranked(place++, ranked));
    }
}
