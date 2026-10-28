package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.labels.ReportClient;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /cult report <reporter> <reported> <type> [reason…]}: files one player's report of another with the cloud,
 * which keeps a replay of the reported player around the report when it can. Players are named by UUID, online name or
 * a Mojang lookup; the type is one word such as {@code illegal_crossteaming}. The report names the reported player's
 * backend server when they are behind this proxy. Calls go out with the server's key, off the calling thread, once
 * each, and every invocation answers the sender with one line, whatever fails.
 */
public class CultReport implements BuildableCommand {
    static final String NOT_FILED = "Report not filed: ";

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("report")
                .permission(CloudArguments.REPORT)
                .required("reporter", StringParser.stringParser(), CloudArguments.players())
                .required("reported", StringParser.stringParser(), CloudArguments.players())
                .required("type", StringParser.stringParser())
                .optional("reason", StringParser.greedyStringParser())
                .handler(this::handle));
    }

    private void handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        answering(sender, () -> {
            String type;
            String reason;
            try {
                type = ReportClient.type(context.get("type"));
                reason = ReportClient.reason(context.<String>optional("reason").orElse(null));
            } catch (IllegalArgumentException invalid) {
                sender.sendMessage(Component.text(NOT_FILED + invalid.getMessage(), NamedTextColor.RED));
                return;
            }
            ReportClient client = ReportClient.configured();
            if (client == null) {
                sender.sendMessage(Component.text(CloudArguments.NOT_CONFIGURED, NamedTextColor.RED));
                return;
            }
            long at = System.currentTimeMillis();
            CloudArguments.resolve(
                    sender,
                    context.get("reporter"),
                    reporter -> CloudArguments.resolve(sender, context.get("reported"), reported -> {
                        ReportClient.Report report = new ReportClient.Report(
                                reporter.id(),
                                name(reporter),
                                reported.id(),
                                name(reported),
                                type,
                                reason,
                                server(reported.id()),
                                at);
                        client.file(report).whenComplete((outcome, error) -> tell(sender, outcome, error));
                    }));
        });
    }

    private static void tell(Sender sender, ReportClient.Outcome outcome, @Nullable Throwable error) {
        CultLabel.tell(
                sender,
                error == null
                        ? answer(outcome)
                        : Component.text(NOT_FILED + error.getClass().getSimpleName() + ".", NamedTextColor.RED));
    }

    /** The line for a filed report, its id and replay state, or for why it was not filed. */
    static Component answer(ReportClient.Outcome outcome) {
        ReportClient.Filed filed = outcome.filed();
        if (filed == null) return Component.text(NOT_FILED + outcome.error(), NamedTextColor.RED);
        String replay = filed.replay() == null ? "" : filed.replay();
        String state = switch (replay) {
            case "" -> "";
            case "recording" -> ", replay recording";
            case "none" -> filed.why() == null ? ", no replay" : ", no replay: " + words(filed.why());
            default -> ", replay " + words(replay);
        };
        return Component.text("Report filed: " + filed.id() + state + ".", NamedTextColor.GREEN);
    }

    private static String words(String wire) {
        return wire.replace('_', ' ');
    }

    /** The player's online name, else the name they were found by; null when they were named only by UUID. */
    static @Nullable String name(CloudArguments.Target target) {
        CultPlayer online = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(target.id());
        if (online != null && online.getName() != null) return online.getName();
        return TargetLookup.uuid(target.name()) == null ? target.name() : null;
    }

    /** The backend server {@code player} is on behind this proxy; empty when they are offline or there is none. */
    private static String server(UUID player) {
        CultPlayer online = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
        PlatformConnection platform =
                online == null ? null : online.user.getCultConnection().platform();
        String backend = platform == null ? null : platform.backendName();
        return backend == null ? "" : backend;
    }

    /** Runs {@code action}; when it throws, {@code sender} is told, so every invocation gets an answer. */
    private static void answering(Sender sender, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failed) {
            CultLabel.tell(
                    sender,
                    Component.text(
                            "The report command failed: " + failed.getClass().getSimpleName() + ".",
                            NamedTextColor.RED));
        }
    }
}
