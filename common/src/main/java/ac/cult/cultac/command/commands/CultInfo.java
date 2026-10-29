package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckCategory;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.checks.info.CheckTexts;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.StringParser;

/**
 * {@code /cult info check <check>} explains what a check catches and what this server does about its flags, in plain
 * language for staff; {@code /cult info checks [group]} lists the checks by group.
 */
public class CultInfo implements BuildableCommand {
    static final String PERMISSION = "cult.info";

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        var info = BuildableCommand.root(commandManager)
                .literal("info", Description.of("Explain what the anticheat checks catch"))
                .permission(PERMISSION);
        commandManager.command(
                info.handler(context -> send(context.sender(), CheckInfoLines.groups(context.sender(), view()))));
        commandManager.command(info.literal("checks")
                .optional("group", StringParser.stringParser(), CloudArguments.of(CultInfo::groupIds))
                .handler(context -> show(context, "group")));
        commandManager.command(info.literal("check")
                .optional("check", StringParser.stringParser(), CloudArguments.of(CultInfo::checkNames))
                .handler(context -> show(context, "check")));
    }

    /** A check or a group, whichever {@code argument} names; every group without one. */
    private static void show(CommandContext<Sender> context, String argument) {
        Sender sender = context.sender();
        CheckInfoLines.View view = view();
        String query = context.<String>optional(argument).orElse(null);
        if (query == null) {
            send(sender, CheckInfoLines.groups(sender, view));
            return;
        }
        CheckCatalog.Entry entry = view.find(query);
        CheckCategory category = CheckCategory.byId(query);
        if (argument.equals("check") && entry != null) send(sender, CheckInfoLines.check(sender, view, entry));
        else if (category != null) send(sender, CheckInfoLines.group(sender, view, category));
        else if (entry != null) send(sender, CheckInfoLines.check(sender, view, entry));
        else send(sender, CheckInfoLines.unknown(sender, view, query));
    }

    private static void send(Sender sender, List<Component> lines) {
        for (Component line : lines) sender.sendMessage(line);
    }

    private static CheckInfoLines.View view() {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        ConfigManager config = manager.getConfig();
        return new CheckInfoLines.View(
                config,
                CheckPolicy.of(config),
                CheckTexts.get(),
                manager.isObserveOnly(),
                config.getBooleanElse("experimental-checks", false));
    }

    /** {@code alert} with the check's name, wherever it shows, running {@code /cult info check} for it when clicked. */
    public static Component linkCheckName(Component alert, Check check) {
        String name = check.getCheckName();
        String shown = check.getDisplayName();
        if (name == null || shown == null || shown.isBlank() || CheckCatalog.find(name) == null) return alert;
        ClickEvent click = ClickEvent.runCommand(CloudCommandService.command("info check " + name));
        return alert.replaceText(TextReplacementConfig.builder()
                .match(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(shown) + "(?![\\p{L}\\p{N}])"))
                .replacement(text -> text.clickEvent(click))
                .build());
    }

    static List<String> groupIds() {
        List<String> ids = new ArrayList<>();
        for (CheckCategory category : CheckCategory.values()) ids.add(category.id());
        return ids;
    }

    /** Every check's name, and its display name where this server renamed it. */
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
