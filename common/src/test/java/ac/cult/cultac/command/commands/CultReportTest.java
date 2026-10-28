package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ac.cult.cultac.cloud.labels.ReportClient;
import ac.cult.cultac.platform.api.sender.Sender;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.Test;

class CultReportTest {
    private final List<String> sent = new ArrayList<>();

    /** The syntax, a {@code *} marking an argument that completes from its own list rather than the parser's. */
    @Test
    void theSyntaxAndItsPermissionArePinned() {
        CommandManager<Sender> manager = manager();
        assertEquals(
                Set.of("cult report <reporter>* <reported>* <type> [reason]"),
                manager.commands().stream().map(CultReportTest::syntax).collect(Collectors.toSet()));
        assertEquals(
                CloudArguments.REPORT,
                manager.commands().iterator().next().commandPermission().permissionString());
    }

    @Test
    void theReasonTakesTheRestOfTheLine() {
        CommandManager<Sender> manager = manager();
        CommandContext<Sender> full = parse(
                manager,
                "cult report Alice 6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b"
                        + " illegal_crossteaming kept teaming at spawn");
        assertEquals("Alice", full.get("reporter"));
        assertEquals("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b", full.get("reported"));
        assertEquals("illegal_crossteaming", full.get("type"));
        assertEquals("kept teaming at spawn", full.get("reason"));
        assertFalse(parse(manager, "cult report Alice Bob griefing")
                .optional("reason")
                .isPresent());
    }

    @Test
    void aBadTypeOrReasonIsRefusedBeforeAnyCall() {
        CommandManager<Sender> manager = manager();
        manager.commandExecutor()
                .executeCommand(sender(), "cult report Alice Bob grief!ng")
                .join();
        manager.commandExecutor()
                .executeCommand(sender(), "cult report Alice Bob griefing " + "r".repeat(501))
                .join();
        assertEquals(
                List.of(
                        "Report not filed: A report type is 1 to 64 letters, digits, _ . : or -, such as"
                                + " illegal_crossteaming.",
                        "Report not filed: A reason is at most 500 characters."),
                sent);
    }

    @Test
    void theAnswerIsOneLineWithTheReplayState() {
        assertEquals(
                "Report filed: r-1, replay recording.",
                text(CultReport.answer(outcome(new ReportClient.Filed("r-1", "recording", null)))));
        assertEquals(
                "Report filed: r-2, no replay: rate limited.",
                text(CultReport.answer(outcome(new ReportClient.Filed("r-2", "none", "rate_limited")))));
        assertEquals(
                "Report filed: r-3, no replay.",
                text(CultReport.answer(outcome(new ReportClient.Filed("r-3", "none", null)))));
        assertEquals(
                "Report filed: r-4, replay ready.",
                text(CultReport.answer(outcome(new ReportClient.Filed("r-4", "ready", null)))));
        assertEquals("Report filed: ?.", text(CultReport.answer(outcome(new ReportClient.Filed("?", null, null)))));
        assertEquals(
                "Report not filed: HTTP 500: down",
                text(CultReport.answer(new ReportClient.Outcome(null, "HTTP 500: down"))));
    }

    @Test
    void aPlayerNamedByUuidIsSentWithoutAName() {
        assertEquals(null, TargetLookup.uuid("Alice"));
        assertNotNull(TargetLookup.uuid("6a1f3c2e5b4d4e8f9a0b1c2d3e4f5a6b"));
        assertNotNull(TargetLookup.uuid("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b"));
    }

    private static ReportClient.Outcome outcome(ReportClient.Filed filed) {
        return new ReportClient.Outcome(filed, null);
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
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
        new CultReport().register(manager, null);
        return manager;
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

    private Sender sender() {
        return (Sender) Proxy.newProxyInstance(
                Sender.class.getClassLoader(), new Class<?>[] {Sender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args[0] instanceof Component text) {
                        sent.add(text(text));
                    }
                    if (method.getName().equals("isPlayer")) return false;
                    return method.getReturnType() == boolean.class ? true : null;
                });
    }

    private CommandContext<Sender> parse(CommandManager<Sender> manager, String input) {
        CommandContext<Sender> context = new CommandContext<>(sender(), manager);
        assertNotNull(manager.commandTree()
                .parse(context, CommandInput.of(input), Runnable::run)
                .join());
        return context;
    }
}
