package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import com.google.gson.JsonElement;
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
 *   <li>{@code /cult label <player> clean <high|extreme> <24h|3d|7d|30d> [note…]}: every check, negative
 *   <li>{@code /cult label list <player>} and {@code /cult label revoke <id> [reason…]}
 * </ul>
 * The span ends when the label is sent. Calls go out with the server's key, off the calling thread, once each, naming
 * the sending player's UUID: the service takes them only from a moderator (or above) of the key's organization with
 * that Minecraft account on their profile, so the console only lists. Every invocation answers the sender, whatever
 * fails.
 */
public class CultLabel implements BuildableCommand {
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
        commandManager.command(chosen(label(commandManager)
                        .required("player", StringParser.stringParser(), CloudArguments.players())
                        .literal("clean"))
                .handler(context -> handleLabel(
                        context,
                        () -> LabelRequest.clean(
                                context.get("confidence"),
                                context.get("span"),
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
    }

    private static Command.Builder<Sender> label(CommandManager<Sender> commandManager) {
        return commandManager
                .commandBuilder("cult", "cultac", "grim", "grimac")
                .literal("label")
                .permission(CloudArguments.LABEL);
    }

    /** The confidence, span and note every label takes. */
    private static Command.Builder<Sender> chosen(Command.Builder<Sender> builder) {
        return builder.required(
                        "confidence", StringParser.stringParser(), CloudArguments.of(() -> LabelRequest.CONFIDENCES))
                .required("span", StringParser.stringParser(), CloudArguments.of(() -> LabelRequest.Span.wires()))
                .optional("note", StringParser.greedyStringParser());
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
            String input = context.get("player");
            sender.sendMessage(Component.text("Labelling " + input + "…", NamedTextColor.GRAY));
            CloudArguments.resolve(sender, input, target -> {
                JsonObject label = request.body(target.id(), System.currentTimeMillis(), sender.getName(), labellerId);
                call(sender, client.post(label), body -> {
                    List<String> ids = LabelClient.ids(body);
                    sender.sendMessage(
                            Component.text(labelledMessage(target.name(), request, ids.size()), NamedTextColor.GRAY));
                    for (String id : ids) sender.sendMessage(id(id));
                });
            });
        });
    }

    static String labelledMessage(String player, LabelRequest request, int labels) {
        String what = request.clean() ? "clean across all checks" : request.cheat();
        return "Labelled " + player + " " + what + ", " + request.confidence() + " ("
                + request.span().display() + "): " + (labels == 0 ? "no label ids returned" : labels + " label(s)");
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
                    target -> call(sender, client.list(target.id()), body -> {
                        List<JsonObject> labels = LabelClient.labels(body);
                        if (labels.isEmpty()) {
                            sender.sendMessage(
                                    Component.text("No labels for " + target.name() + ".", NamedTextColor.GRAY));
                            return;
                        }
                        sender.sendMessage(Component.text(
                                "Labels for " + target.name() + " (" + labels.size() + ")", NamedTextColor.GRAY));
                        for (JsonObject label : labels) sender.sendMessage(listed(label));
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
                    body -> sender.sendMessage(Component.text("Revoked label " + id + ".", NamedTextColor.GRAY)));
        });
    }

    /** The sending player's UUID; null for the console, which is told it may not label. */
    static @Nullable UUID labellerId(Sender sender) {
        if (sender.isPlayer()) return sender.getUniqueId();
        sender.sendMessage(Component.text(PLAYERS_ONLY, NamedTextColor.RED));
        return null;
    }

    private static @Nullable LabelClient client(Sender sender) {
        LabelClient client = LabelClient.configured();
        if (client == null) sender.sendMessage(Component.text(CloudArguments.NOT_CONFIGURED, NamedTextColor.RED));
        return client;
    }

    /** Runs {@code action}; when it throws, {@code sender} is told, so every invocation gets an answer. */
    static void answering(Sender sender, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failed) {
            sender.sendMessage(Component.text(
                    "The label command failed: " + failed.getClass().getSimpleName() + ".", NamedTextColor.RED));
        }
    }

    private static void call(Sender sender, CompletableFuture<LabelClient.Reply> call, Consumer<String> success) {
        call.whenComplete((reply, error) -> answering(sender, () -> {
            if (error != null) {
                sender.sendMessage(Component.text(
                        "The cloud did not answer: " + LabelClient.unanswered(error) + ".", NamedTextColor.RED));
            } else if (!reply.ok()) {
                sender.sendMessage(
                        Component.text("Label request failed: " + LabelClient.failure(reply), NamedTextColor.RED));
            } else {
                success.accept(reply.body());
            }
        }));
    }

    private static Component id(String id) {
        return Component.text("  " + id, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand("/cult label revoke " + id + " "))
                .hoverEvent(HoverEvent.showText(Component.text("Click to revoke", NamedTextColor.GRAY)));
    }

    /** A label as {@code list} shows it: cheat, value, source, labeller, and whether it is revoked. */
    private static Component listed(JsonObject label) {
        String line = text(label, "cheat") + " " + text(label, "value") + " · " + text(label, "source") + " by "
                + text(label, "labeller") + " · " + text(label, "createdAt");
        boolean revoked = label.has("revokedAt") && !label.get("revokedAt").isJsonNull();
        return Component.text()
                .append(id(text(label, "id")))
                .append(Component.text(
                        " " + line + (revoked ? " (revoked)" : ""),
                        revoked ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY))
                .build();
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull()
                ? "?"
                : value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }
}
