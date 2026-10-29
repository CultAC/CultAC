package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckCategory;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.checks.info.CheckTexts;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.StringParser;

public class CultInfo implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        var info = BuildableCommand.root(commandManager)
                .literal("info", Description.of("Explain what the anticheat checks catch"))
                .permission("cult.info");
        commandManager.command(
                info.handler(context -> send(context.sender(), CheckInfoLines.overview(context.sender(), view()))));
        commandManager.command(info.literal("checks")
                .optional("group", StringParser.stringParser(), CloudArguments.of(CultInfo::groupIds))
                .handler(context -> show(context, "group")));
        commandManager.command(info.literal("check")
                .optional("check", StringParser.stringParser(), CloudArguments.of(CultInfo::checkNames))
                .handler(context -> show(context, "check")));
    }

    private static void show(CommandContext<Sender> context, String argument) {
        Sender sender = context.sender();
        CheckInfoLines.View view = view();
        String query = context.getOrDefault(argument, null);
        if (query == null) {
            send(sender, CheckInfoLines.overview(sender, view));
            return;
        }
        CheckCatalog.Entry entry = view.find(query);
        CheckCategory category = CheckCategory.byId(query);
        // Elytra and Cloud are both a check and a group; each subcommand prefers its own kind.
        if (argument.equals("check") && entry != null) send(sender, CheckInfoLines.check(sender, view, entry));
        else if (category != null) send(sender, CheckInfoLines.group(sender, view, category));
        else if (entry != null) send(sender, CheckInfoLines.check(sender, view, entry));
        else send(sender, CheckInfoLines.unknown(sender, view, query));
    }

    private static void send(Sender sender, List<Component> lines) {
        for (Component line : lines) sender.sendMessage(line);
    }

    private static CheckInfoLines.View view() {
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        return new CheckInfoLines.View(
                config, CheckPolicy.of(config), CheckTexts.get(), config.getBooleanElse("experimental-checks", false));
    }

    static List<String> groupIds() {
        List<String> ids = new ArrayList<>();
        for (CheckCategory category : CheckCategory.values()) ids.add(category.id());
        return ids;
    }

    static List<String> checkNames() {
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        Set<String> names = new LinkedHashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            names.add(entry.name());
            names.add(config.getStringElse(entry.configName() + ".displayname", entry.name()));
        }
        return new ArrayList<>(names);
    }
}
