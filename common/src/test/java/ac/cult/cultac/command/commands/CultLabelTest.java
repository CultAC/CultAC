package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.platform.api.sender.Sender;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.Test;

class CultLabelTest {
    private final List<String> sent = new ArrayList<>();

    @Test
    void bothFormsParseTheirArguments() {
        CommandManager<Sender> manager = manager();
        Sender sender = sender(true);
        CommandContext<Sender> clean = parse(manager, sender, "cult label Steve clean high 30d 30 minutes watched");
        assertEquals("Steve", clean.get("player"));
        assertEquals("high", clean.get("confidence"));
        assertEquals("30d", clean.get("span"));
        assertEquals("30 minutes watched", clean.get("note"));
        assertFalse(parse(manager, sender, "cult label Steve clean extreme 7d")
                .optional("note")
                .isPresent());
        CommandContext<Sender> cheat = parse(manager, sender, "cult label Steve autototem extreme 24h seen twice");
        assertEquals("autototem", cheat.get("cheat"));
        assertEquals("extreme", cheat.get("confidence"));
        assertEquals("24h", cheat.get("span"));
        assertEquals("seen twice", cheat.get("note"));
    }

    @Test
    void confidenceAndSpanTabComplete() {
        CommandManager<Sender> manager = manager();
        Sender sender = sender(true);
        assertEquals(List.of("high", "extreme"), suggest(manager, sender, "cult label Steve triggerbot "));
        assertEquals(List.of("high", "extreme"), suggest(manager, sender, "cult label Steve clean "));
        assertEquals(List.of("24h", "3d", "7d", "30d"), suggest(manager, sender, "cult label Steve triggerbot high "));
        assertEquals(List.of("24h", "3d", "7d", "30d"), suggest(manager, sender, "cult label Steve clean high "));
        assertEquals(List.of("3d", "30d"), suggest(manager, sender, "cult label Steve clean high 3"));
    }

    @Test
    void theConsoleIsToldItMayNotLabel() {
        assertNull(CultLabel.labellerId(sender(false)));
        assertEquals(List.of(CultLabel.PLAYERS_ONLY), sent);
    }

    @Test
    void aFailureIsStillAnswered() {
        CultLabel.answering(sender(true), () -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(List.of("The label command failed: IllegalStateException."), sent);
    }

    @Test
    void theReplyNamesTheChoices() {
        assertEquals(
                "Labelled Steve triggerbot, extreme (last 24 hours): 1 label(s)",
                CultLabel.labelledMessage("Steve", LabelRequest.cheat("triggerbot", "extreme", "24h", null), 1));
        assertEquals(
                "Labelled Steve clean across all checks, high (last 7 days): 2 label(s)",
                CultLabel.labelledMessage("Steve", LabelRequest.clean("high", "7d", null), 2));
    }

    private static CommandManager<Sender> manager() {
        CommandManager<Sender> manager =
                new CommandManager<>(
                        ExecutionCoordinator.simpleCoordinator(),
                        CommandRegistrationHandler.nullCommandRegistrationHandler()) {
                    @Override
                    public boolean hasPermission(Sender sender, String permission) {
                        return true;
                    }
                };
        new CultLabel().register(manager, null);
        return manager;
    }

    private Sender sender(boolean player) {
        return (Sender) Proxy.newProxyInstance(
                Sender.class.getClassLoader(), new Class<?>[] {Sender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args[0] instanceof Component text) {
                        sent.add(PlainTextComponentSerializer.plainText().serialize(text));
                    }
                    if (method.getName().equals("isPlayer")) return player;
                    return method.getReturnType() == boolean.class ? true : null;
                });
    }

    private static List<String> suggest(CommandManager<Sender> manager, Sender sender, String input) {
        return manager.suggestionFactory().suggestImmediately(sender, input).list().stream()
                .map(Suggestion::suggestion)
                .toList();
    }

    private static CommandContext<Sender> parse(CommandManager<Sender> manager, Sender sender, String input) {
        CommandContext<Sender> context = new CommandContext<>(sender, manager);
        assertNotNull(manager.commandTree()
                .parse(context, CommandInput.of(input), Runnable::run)
                .join());
        return context;
    }
}
