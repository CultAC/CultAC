package ac.cult.cultac.command;

import ac.cult.cultac.command.commands.*;
import ac.cult.cultac.command.handler.CultCommandFailureHandler;
import ac.cult.cultac.platform.api.command.CommandService;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import io.leangen.geantyref.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.processors.requirements.RequirementApplicable;
import org.incendo.cloud.processors.requirements.RequirementApplicable.RequirementApplicableFactory;
import org.incendo.cloud.processors.requirements.RequirementPostprocessor;
import org.incendo.cloud.processors.requirements.Requirements;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

public class CloudCommandService implements CommandService {

    public static final CloudKey<Requirements<Sender, SenderRequirement>> REQUIREMENT_KEY
            = CloudKey.of("requirements", new TypeToken<>() {});

    public static final RequirementApplicableFactory<Sender, SenderRequirement> REQUIREMENT_FACTORY
            = RequirementApplicable.factory(REQUIREMENT_KEY);

    private boolean commandsRegistered = false;

    private final Supplier<CommandManager<Sender>> commandManagerSupplier;
    private final CloudPlatformCommandArguments commandArguments;

    public CloudCommandService(Supplier<CommandManager<Sender>> commandManagerSupplier, CloudPlatformCommandArguments commandArguments) {
        this.commandManagerSupplier = commandManagerSupplier;
        this.commandArguments = commandArguments;
    }

    public void registerCommands() {
        if (commandsRegistered) return;
        CommandManager<Sender> commandManager = commandManagerSupplier.get();
        new CultDebug().register(commandManager, commandArguments);
        new CultDebugVelocity().register(commandManager, commandArguments);
        new CultGeyserPacketLog().register(commandManager, commandArguments);
        new CultAlerts().register(commandManager, commandArguments);
        new CultProfile().register(commandManager, commandArguments);
        new CultSendAlert().register(commandManager, commandArguments);
        new CultHelp().register(commandManager, commandArguments);
        new CultHistory().register(commandManager, commandArguments);
        new CultHistoryMigrate().register(commandManager, commandArguments);
        new CultHistoryCopy().register(commandManager, commandArguments);
        new CultReload().register(commandManager, commandArguments);
        new CultSpectate().register(commandManager, commandArguments);
        new CultStopSpectating().register(commandManager, commandArguments);
        new CultLog().register(commandManager, commandArguments);
        new CultVerbose().register(commandManager, commandArguments);
        new CultVersion().register(commandManager, commandArguments);
        new CultDump().register(commandManager, commandArguments);
        new CultBrands().register(commandManager, commandArguments);
        new CultList().register(commandManager, commandArguments);
        new CultTestWebhook().register(commandManager, commandArguments);

        final RequirementPostprocessor<Sender, SenderRequirement>
                senderRequirementPostprocessor = RequirementPostprocessor.of(
                REQUIREMENT_KEY,
                new CultCommandFailureHandler()
        );
        commandManager.registerCommandPostProcessor(senderRequirementPostprocessor);
        registerInvalidSyntaxHandler(commandManager);
        commandsRegistered = true;
    }

    private void registerInvalidSyntaxHandler(CommandManager<Sender> commandManager) {
        commandManager.exceptionController().registerHandler(InvalidSyntaxException.class, context -> {
            Sender sender = context.context().sender();
            if (isHistoryInput(context.context().rawInput().input())) {
                sender.sendMessage(Component.text("Invalid history syntax.", NamedTextColor.RED));
                sender.sendMessage(Component.text("Use: /cult history <player> [page <N>]", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Use: /cult history <player> session <N|latest> [page <N>] [-d] [-v]", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Tip: /cult history <player> session shows filter and detail options.", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Use /cult history player <player> ... for names that collide with history subcommands.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(Component.text(context.exception().correctSyntax(), NamedTextColor.RED));
        });
    }

    private static boolean isHistoryInput(String rawInput) {
        String input = rawInput.strip();
        if (input.startsWith("/")) input = input.substring(1).strip();
        String[] tokens = input.toLowerCase(Locale.ROOT).split("\\s+");
        return tokens.length >= 2
                && (tokens[0].equals("cult") || tokens[0].equals("cultac")
                || tokens[0].equals("grim") || tokens[0].equals("grimac"))
                && (tokens[1].equals("history") || tokens[1].equals("hist"));
    }

    protected <E extends Exception> void registerExceptionHandler(CommandManager<Sender> commandManager, Class<E> ex, Function<E, ComponentLike> toComponent) {
        commandManager.exceptionController().registerHandler(ex,
                (c) -> c.context().sender().sendMessage(toComponent.apply(c.exception()).asComponent().colorIfAbsent(NamedTextColor.RED))
        );
    }
}
