package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.Nullable;

/**
 * Staff labels in the cloud's label ledger, each argument tab-completed:
 * <ul>
 *   <li>{@code /cult label <player> <cheat> <high|extreme> <24h|3d|7d|30d> [note…]}: the cheat, positive
 *   <li>{@code /cult label <player> clean <high|extreme> [note…]}: every check, negative, with no span: the player's
 *   stored play and all play from now on, until revoked
 *   <li>{@code /cult label list <player>} and {@code /cult label revoke <id> [reason…]}
 *   <li>{@code /cult label <player>}: the label menu, where the platform draws one ({@link LabelMenu})
 * </ul>
 * A cheat label's span ends when it is sent. Calls go out with the server's key, off the calling thread, once each, naming
 * the sending player's UUID: the service takes them only from a moderator (or above) of the key's organization with
 * that Minecraft account on their profile, so the console only lists. Every invocation answers the sender, whatever
 * fails.
 */
public class CultLabel implements BuildableCommand {
    private static volatile @Nullable LabelMenu menu;

    static final String DEFAULT_REVOKE_REASON = "revoked in game";
    static final String PLAYERS_ONLY =
            "Labels are made in game by a player whose Minecraft account is on their cloud profile.";

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(label(commandManager)
                .literal("list")
                .required("player", StringParser.stringParser(), CloudArguments.players())
                .handler(this::handleList));
        commandManager.command(label(commandManager)
                .literal("revoke")
                .required("id", StringParser.stringParser())
                .optional("reason", StringParser.greedyStringParser())
                .handler(this::handleRevoke));
        commandManager.command(note(confidence(label(commandManager)
                        .required("player", StringParser.stringParser(), CloudArguments.players())
                        .literal("clean")))
                .handler(context -> handleLabel(
                        context,
                        () -> LabelRequest.clean(
                                context.get("confidence"),
                                context.<String>optional("note").orElse(null)))));
        commandManager.command(chosen(label(commandManager)
                        .required("player", StringParser.stringParser(), CloudArguments.players())
                        .required("cheat", StringParser.stringParser(), CloudArguments.of(() -> LabelRequest.CHEATS)))
                .handler(context -> handleLabel(
                        context,
                        () -> LabelRequest.cheat(
                                context.get("cheat"),
                                context.get("confidence"),
                                context.get("span"),
                                context.<String>optional("note").orElse(null)))));
        LabelMenu installed = menu;
        if (installed != null) {
            commandManager.command(label(commandManager)
                    .required("player", StringParser.stringParser(), CloudArguments.players())
                    .handler(context -> handleMenu(context, installed)));
        }
    }

    /** Installs the label menu the platform draws; commands registered after this offer it. */
    static void menu(@Nullable LabelMenu installed) {
        menu = installed;
    }

    private static Command.Builder<Sender> label(CommandManager<Sender> commandManager) {
        return BuildableCommand.root(commandManager).literal("label").permission(CloudArguments.LABEL);
    }

    /** The confidence, span and note a cheat label takes. */
    private static Command.Builder<Sender> chosen(Command.Builder<Sender> builder) {
        return note(confidence(builder)
                .required("span", StringParser.stringParser(), CloudArguments.of(() -> LabelRequest.Span.wires())));
    }

    private static Command.Builder<Sender> confidence(Command.Builder<Sender> builder) {
        return builder.required(
                "confidence", StringParser.stringParser(), CloudArguments.of(() -> LabelRequest.CONFIDENCES));
    }

    private static Command.Builder<Sender> note(Command.Builder<Sender> builder) {
        return builder.optional("note", StringParser.greedyStringParser());
    }

    /** Opens {@code menu} on the named player for a staff member it can be shown to; anyone else is told why not. */
    private void handleMenu(CommandContext<Sender> context, LabelMenu menu) {
        Sender sender = context.sender();
        answering(sender, () -> {
            UUID labellerId = labellerId(sender);
            if (labellerId == null) return;
            if (!menu.showable(sender)) {
                sender.sendMessage(Component.text(LabelLines.javaOnly(), NamedTextColor.RED));
                return;
            }
            if (!CloudArguments.labelingAllowed(sender)) return;
            LabelClient client = client(sender);
            if (client == null) return;
            CloudArguments.resolve(
                    sender, context.get("player"), target -> menu.open(sender, labellerId, client, target));
        });
    }

    /** {@code parsed} throws {@link IllegalArgumentException} naming what is wrong with the arguments. */
    private void handleLabel(CommandContext<Sender> context, Supplier<LabelRequest> parsed) {
        Sender sender = context.sender();
        answering(sender, () -> {
            LabelRequest request;
            try {
                request = parsed.get();
            } catch (IllegalArgumentException invalid) {
                sender.sendMessage(Component.text(invalid.getMessage(), NamedTextColor.RED));
                return;
            }
            UUID labellerId = labellerId(sender);
            if (labellerId == null) return;
            if (!CloudArguments.labelingAllowed(sender)) return;
            LabelClient client = client(sender);
            if (client == null) return;
            CloudArguments.resolve(sender, context.get("player"), target -> {
                JsonObject label = request.body(target.id(), System.currentTimeMillis(), sender.getName(), labellerId);
                call(
                        sender,
                        client.post(label),
                        reply -> sender.sendMessage(
                                Component.text(labelledMessage(target.name(), request), NamedTextColor.GREEN)));
            });
        });
    }

    static String labelledMessage(String player, LabelRequest request) {
        String what = request.clean() ? "clean across all checks" : request.cheat();
        LabelRequest.Span span = request.span();
        return "Labelled " + player + " " + what + ", " + request.confidence()
                + (span == null ? "" : " (" + span.display() + ")");
    }

    private void handleList(CommandContext<Sender> context) {
        Sender sender = context.sender();
        answering(sender, () -> {
            String input = context.get("player");
            if (!CloudArguments.labelingAllowed(sender)) return;
            LabelClient client = client(sender);
            if (client == null) return;
            CloudArguments.resolve(
                    sender,
                    input,
                    target -> call(sender, client.list(target.id()), reply -> {
                        List<JsonObject> labels = LabelClient.labels(reply.body());
                        if (labels.isEmpty()) {
                            sender.sendMessage(
                                    Component.text("No labels for " + target.name() + ".", NamedTextColor.GRAY));
                            return;
                        }
                        String count = labels.size() < LabelClient.MAX_RESULTS
                                ? String.valueOf(labels.size())
                                : "the first " + labels.size();
                        sender.sendMessage(Component.text(
                                "Labels for " + target.name() + " (" + count + ")", NamedTextColor.GRAY));
                        for (JsonObject label : labels) sender.sendMessage(listed(label, reply));
                    }));
        });
    }

    private void handleRevoke(CommandContext<Sender> context) {
        Sender sender = context.sender();
        answering(sender, () -> {
            UUID labellerId = labellerId(sender);
            if (labellerId == null) return;
            if (!CloudArguments.labelingAllowed(sender)) return;
            String input = context.get("id");
            UUID id;
            try {
                id = UUID.fromString(input);
            } catch (IllegalArgumentException notAnId) {
                sender.sendMessage(Component.text("Not a label id: " + input, NamedTextColor.RED));
                return;
            }
            LabelClient client = client(sender);
            if (client == null) return;
            String reason = context.<String>optional("reason").orElse(DEFAULT_REVOKE_REASON);
            call(
                    sender,
                    client.revoke(id, sender.getName(), labellerId, reason),
                    reply -> sender.sendMessage(Component.text("Revoked label " + id + ".", NamedTextColor.GRAY)));
        });
    }

    /** The sending player's UUID; null for the console, which is told it may not label. */
    static @Nullable UUID labellerId(Sender sender) {
        if (sender.isPlayer()) return sender.getUniqueId();
        sender.sendMessage(Component.text(PLAYERS_ONLY, NamedTextColor.RED));
        return null;
    }

    static @Nullable LabelClient client(Sender sender) {
        LabelClient client = LabelClient.configured();
        if (client == null) sender.sendMessage(Component.text(CloudArguments.NOT_CONFIGURED, NamedTextColor.RED));
        return client;
    }

    /** Runs {@code action}; when it throws, {@code sender} is told, so every invocation gets an answer. */
    static void answering(Sender sender, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failed) {
            tell(
                    sender,
                    Component.text(
                            "The label command failed: " + failed.getClass().getSimpleName() + ".",
                            NamedTextColor.RED));
        }
    }

    /** Sends {@code message} unless {@code sender} can no longer be told, such as a player who left. */
    static void tell(Sender sender, Component message) {
        try {
            sender.sendMessage(message);
        } catch (RuntimeException gone) {
            LogUtil.debug("Label answer for " + sender.getName() + " dropped: "
                    + gone.getClass().getSimpleName());
        }
    }

    private static void call(
            Sender sender, CompletableFuture<LabelClient.Reply> call, Consumer<LabelClient.Reply> success) {
        call.whenComplete((reply, error) -> answering(sender, () -> {
            if (error != null) {
                sender.sendMessage(Component.text(
                        "The cloud did not answer: " + LabelClient.unanswered(error) + ".", NamedTextColor.RED));
            } else if (!reply.ok()) {
                sender.sendMessage(
                        Component.text("Label request failed: " + LabelClient.failure(reply), NamedTextColor.RED));
            } else {
                success.accept(reply);
            }
        }));
    }

    /** A label id, offered for revoking only when it is a UUID. */
    private static Component id(String id) {
        if (!LabelClient.isId(id)) return Component.text("  " + id, NamedTextColor.WHITE);
        return Component.text("  " + id, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(CloudCommandService.command("label revoke " + id + " ")))
                .hoverEvent(HoverEvent.showText(Component.text("Click to revoke", NamedTextColor.GRAY)));
    }

    /**
     * A label from {@code reply} as {@code list} shows it: cheat, value, source, labeller, and whether it is revoked.
     */
    static Component listed(JsonObject label, LabelClient.Reply reply) {
        String line = LabelClient.field(label, "cheat", reply) + " " + LabelClient.field(label, "value", reply) + " · "
                + LabelClient.field(label, "source", reply) + " by " + LabelClient.field(label, "labeller", reply)
                + " · " + LabelClient.field(label, "createdAt", reply);
        boolean revoked = label.has("revokedAt") && !label.get("revokedAt").isJsonNull();
        return Component.text()
                .append(id(LabelClient.field(label, "id", reply)))
                .append(Component.text(
                        " " + line + (revoked ? " (revoked)" : ""),
                        revoked ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY))
                .asComponent();
    }
}
