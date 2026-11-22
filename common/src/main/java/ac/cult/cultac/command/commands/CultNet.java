package ac.cult.cultac.command.commands;

import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.LiteralParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.permission.Permission;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * {@code /cult net <target> <command>}: runs one network command on every CultAC instance the target names, the
 * target being one or more instance ids, backend servers, patterns or keywords joined by commas. The commands are
 * {@code status}, {@code reload}, {@code observe [on|off]}, {@code punish [on|off]} and {@code check <check> <on|off>}.
 * Each needs {@code cult.net} and its own {@code cult.net.<command>}, and completes only after a target it can run on.
 */
public class CultNet implements BuildableCommand {
    static final String PERMISSION = "cult.net";

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command.Builder<Sender> net = BuildableCommand.root(commandManager)
                .literal("net", Description.of("Run a command on several CultAC servers"))
                .permission(PERMISSION);
        commandManager.command(net.handler(context -> NetworkModule.networkCommandUsage(context.sender())));
        Command.Builder<Sender> targeted = net.required(
                "target",
                StringParser.stringParser(),
                SuggestionProvider.blockingStrings(
                        (context, input) -> NetworkModule.networkCommandTargets(input.peekString())));
        for (String name : List.of("status", "reload")) {
            commandManager.command(targeted.required(command(name))
                    .permission(permission(name))
                    .futureHandler(context -> run(context, name, List.of())));
        }
        for (String name : List.of("observe", "punish")) {
            Command.Builder<Sender> setting = targeted.required(command(name)).permission(permission(name));
            commandManager.command(setting.futureHandler(context -> run(context, name, List.of())));
            for (String state : List.of("on", "off"))
                commandManager.command(
                        setting.literal(state).futureHandler(context -> run(context, name, List.of(state))));
        }
        Command.Builder<Sender> check = targeted.required(command("check"))
                .permission(permission("check"))
                .required("check", StringParser.stringParser(), SuggestionProvider.blockingStrings((context, input) -> {
                    String typed = input.peekString().toLowerCase(Locale.ROOT);
                    List<String> names = new ArrayList<>();
                    for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
                        if (entry.name().toLowerCase(Locale.ROOT).startsWith(typed)) names.add(entry.name());
                    }
                    return names;
                }));
        for (String state : List.of("on", "off")) {
            commandManager.command(check.literal(state)
                    .futureHandler(context -> run(context, "check", List.of(context.<String>get("check"), state))));
        }
    }

    private static CompletableFuture<Void> run(CommandContext<Sender> context, String command, List<String> args) {
        return NetworkModule.runNetworkCommand(context.sender(), context.<String>get("target"), command, args);
    }

    /** The literal {@code name}, completed only when the target typed before it names something it can run on. */
    private static CommandComponent.Builder<Sender, String> command(String name) {
        return CommandComponent.<Sender, String>builder()
                .name(name)
                .parser(LiteralParser.literal(name))
                .suggestionProvider(
                        SuggestionProvider.blockingStrings((context, input) -> NetworkModule.networkCommandOffered(
                                        context.<String>optional("target").orElse(""), name)
                                ? List.of(name)
                                : List.of()));
    }

    /** {@code cult.net} and {@code cult.net.<command>}. */
    static Permission permission(String command) {
        return Permission.allOf(Permission.of(PERMISSION), Permission.of(PERMISSION + "." + command));
    }
}
