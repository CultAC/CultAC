package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.labels.LabelRequest;
import ac.cult.cultac.platform.api.sender.Sender;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.Test;

class CultLabelTest {
    private static final UUID STAFF = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private final List<String> sent = new ArrayList<>();

    @Test
    void bothFormsParseTheirArguments() {
        CommandManager<Sender> manager = manager();
        Sender sender = sender(true);
        CommandContext<Sender> clean = parse(manager, sender, "cult label Steve clean high 30 minutes watched");
        assertEquals("Steve", clean.get("player"));
        assertEquals("high", clean.get("confidence"));
        assertFalse(clean.contains("span"));
        assertEquals("30 minutes watched", clean.get("note"));
        assertFalse(parse(manager, sender, "cult label Steve clean extreme")
                .optional("note")
                .isPresent());
        CommandContext<Sender> cheat = parse(manager, sender, "cult label Steve autototem extreme 24h seen twice");
        assertEquals("autototem", cheat.get("cheat"));
        assertEquals("extreme", cheat.get("confidence"));
        assertEquals("24h", cheat.get("span"));
        assertEquals("seen twice", cheat.get("note"));
    }

    /** Each syntax, a {@code *} marking an argument that completes from its own list rather than the parser's. */
    @Test
    void theSyntaxesAndTheirCompletionsArePinned() {
        assertEquals(
                Set.of(
                        "cult label list <player>*",
                        "cult label revoke <id> [reason]",
                        "cult label <player>* clean <confidence>* [note]",
                        "cult label <player>* <cheat>* <confidence>* <span>* [note]"),
                syntaxes(manager()));
    }

    @Test
    void aPlatformWithAMenuOffersItOnThePlayerAlone() {
        CultLabel.menu(menu(false));
        try {
            CommandManager<Sender> manager = manager();
            assertTrue(syntaxes(manager).contains("cult label <player>*"));
            assertEquals(
                    "Steve", parse(manager, sender(true), "cult label Steve").get("player"));
            assertEquals(
                    "high",
                    parse(manager, sender(true), "cult label Steve clean high").get("confidence"));
        } finally {
            CultLabel.menu(null);
        }
        assertFalse(syntaxes(manager()).contains("cult label <player>*"));
    }

    @Test
    void aStaffMemberTheMenuCannotBeShownIsPointedAtTheCommand() {
        CultLabel.menu(menu(false));
        try {
            CommandManager<Sender> manager = manager();
            manager.commandExecutor()
                    .executeCommand(sender(true), "cult label Steve")
                    .join();
        } finally {
            CultLabel.menu(null);
        }
        assertEquals(List.of(LabelLines.javaOnly()), sent);
    }

    @Test
    void cheatConfidenceAndSpanTabComplete() {
        CommandManager<Sender> manager = manager();
        Sender sender = sender(true);
        Set<String> verdicts = new HashSet<>(LabelRequest.CHEATS);
        verdicts.add("clean");
        assertEquals(verdicts, new HashSet<>(suggest(manager, sender, "cult label Steve ")));
        assertEquals(List.of("autototem"), suggest(manager, sender, "cult label Steve autot"));
        assertEquals(List.of("high", "extreme"), suggest(manager, sender, "cult label Steve triggerbot "));
        assertEquals(List.of("high", "extreme"), suggest(manager, sender, "cult label Steve clean "));
        assertEquals(List.of("24h", "3d", "7d", "30d"), suggest(manager, sender, "cult label Steve triggerbot high "));
        assertEquals(List.of("3d", "30d"), suggest(manager, sender, "cult label Steve triggerbot high 3"));
        assertEquals(List.of(), suggest(manager, sender, "cult label Steve clean high "));
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
    void aListedLabelIsOneBoundedLineAndOnlyAUuidIsOfferedForRevoke() {
        JsonObject label = new JsonObject();
        label.addProperty("id", "/op me");
        label.addProperty("cheat", "x".repeat(10_000) + "\n[Server] approved");
        label.addProperty("labeller", "Mod" + Character.toString(0x2028) + "Admin\r\n");
        label.add("source", JsonParser.parseString("{\"nested\":\"" + "y".repeat(10_000) + "\"}"));
        LabelClient.Reply reply = new LabelClient.Reply(200, "");
        Component line = CultLabel.listed(label, reply);
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        assertTrue(plain.length() < 6 * LabelClient.MAX_FIELD, plain.length() + " characters");
        assertTrue(plain.chars().noneMatch(c -> c == '\n' || c == '\r' || c == 0x2028), plain);
        assertNull(line.children().get(0).clickEvent());

        UUID id = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
        label.addProperty("id", id.toString());
        assertEquals(
                ClickEvent.suggestCommand("/cult label revoke " + id + " "),
                CultLabel.listed(label, reply).children().get(0).clickEvent());
    }

    @Test
    void theReplyNamesTheChoices() {
        assertEquals(
                "Labelled Steve triggerbot, extreme (last 24 hours)",
                CultLabel.labelledMessage("Steve", LabelRequest.cheat("triggerbot", "extreme", "24h", null)));
        assertEquals(
                "Labelled Steve clean across all checks, high",
                CultLabel.labelledMessage("Steve", LabelRequest.clean("high", null)));
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

    private static Set<String> syntaxes(CommandManager<Sender> manager) {
        return manager.commands().stream().map(CultLabelTest::syntax).collect(Collectors.toSet());
    }

    private static String syntax(Command<Sender> command) {
        List<String> parts = new ArrayList<>();
        for (CommandComponent<Sender> component : command.components()) {
            if (component.type() == CommandComponent.ComponentType.LITERAL) {
                parts.add(component.name());
                continue;
            }
            String own = component.suggestionProvider() == component.parser().suggestionProvider() ? "" : "*";
            parts.add(component.required() ? "<" + component.name() + ">" + own : "[" + component.name() + "]" + own);
        }
        return String.join(" ", parts);
    }

    /** A menu that is {@code showable} or not, and that fails the test if it is ever opened. */
    private static LabelMenu menu(boolean showable) {
        return new LabelMenu() {
            @Override
            public boolean showable(Sender sender) {
                return showable;
            }

            @Override
            public void open(Sender sender, UUID labellerId, LabelClient client, CloudArguments.Target target) {
                throw new AssertionError("opened");
            }
        };
    }

    private Sender sender(boolean player) {
        return (Sender) Proxy.newProxyInstance(
                Sender.class.getClassLoader(), new Class<?>[] {Sender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args[0] instanceof Component text) {
                        sent.add(PlainTextComponentSerializer.plainText().serialize(text));
                    }
                    if (method.getName().equals("isPlayer")) return player;
                    if (method.getName().equals("getUniqueId")) return STAFF;
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
