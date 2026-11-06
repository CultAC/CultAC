package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.IsolatedCommandManager;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.scheduler.AsyncScheduler;
import ac.cult.cultac.platform.api.scheduler.PlatformScheduler;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.debug.DebugText;
import ac.grim.grimac.api.plugin.GrimPlugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.exception.CommandParseException;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class VelocityCommandsTest {
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final VelocitySenders senders = mock(VelocitySenders.class);
    private final Sender sender = mock(Sender.class);
    private final Sender target = mock(Sender.class);
    private final Player nativePlayer = mock(Player.class);
    private final VelocityCommands arguments = new VelocityCommands(proxy, senders);
    private final IsolatedCommandManager manager = new IsolatedCommandManager();

    @BeforeEach
    void setUp() {
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(proxy.getPlayer(anyString())).thenReturn(Optional.empty());
        when(proxy.getPlayer("Target")).thenReturn(Optional.of(nativePlayer));
        when(senders.wrap(nativePlayer)).thenReturn(target);
    }

    @Test
    void unavailablePlayerIsANormalParseFailureForExecutionAndSuggestions() {
        var parser = arguments.singlePlayerSelectorParser().parser();
        for (boolean suggestions : List.of(false, true)) {
            for (String name : List.of("proxyticks", "OfflinePlayer")) {
                var context = new CommandContext<>(suggestions, sender, manager);
                var result = assertDoesNotThrow(
                        () -> parser.parseFuture(context, CommandInput.of(name)).join());
                assertTrue(result.parsedValue().isEmpty());
                assertEquals(
                        "Player is not online: " + name,
                        result.failure().orElseThrow().getMessage());
            }
        }
    }

    @Test
    void onlinePlayerStillResolvesToTheWrappedSender() {
        var result = arguments
                .singlePlayerSelectorParser()
                .parser()
                .parseFuture(new CommandContext<>(sender, manager), CommandInput.of("Target"))
                .join();
        assertTrue(result.failure().isEmpty());
        var selector = result.parsedValue().orElseThrow();
        assertTrue(selector.isSingle());
        assertSame(target, selector.getSinglePlayer());
        assertEquals(List.of(target), selector.getPlayers());
        assertEquals("Target", selector.inputString());
    }

    @Test
    void allProxyCommandsRegisterTogether() {
        var service = new CloudCommandService(() -> manager, arguments);
        assertDoesNotThrow(service::registerCommands);
        assertTrue(suggestions("grim ").containsAll(List.of("history", "log", "list")));
    }

    @Test
    void invalidCommandsAndArgumentsGiveTheSenderFeedback() {
        var service = new CloudCommandService(() -> manager, arguments);
        service.registerCommands();
        for (var example : Map.of(
                        "cult asdf", "Invalid command",
                        "grim asdf", "Invalid command",
                        "grim log", "flagId",
                        "grim log -l", "-l",
                        "grim log 42 --bogus", "bogus",
                        "gl -l", "-l",
                        "cult debug OfflinePlayer", "Player is not online: OfflinePlayer")
                .entrySet()) {
            clearInvocations(sender);
            // Cloud reports the failure to the sender and still completes its future exceptionally.
            var failure = assertThrows(
                    CompletionException.class,
                    () -> service.execute(sender, example.getKey())
                            .toCompletableFuture()
                            .join());
            assertInstanceOf(CommandParseException.class, failure.getCause());
            assertTrue(messages().stream().anyMatch(message -> message.contains(example.getValue())), example.getKey());
        }
    }

    @Test
    void deniedCommandGivesPermissionFeedback() {
        var service = new CloudCommandService(() -> manager, arguments);
        service.registerCommands();
        when(sender.hasPermission("cult.log")).thenReturn(false);
        assertThrows(
                CompletionException.class,
                () -> service.execute(sender, "grim log 42 -l")
                        .toCompletableFuture()
                        .join());
        assertTrue(messages().stream().anyMatch(message -> message.contains("permission")));
    }

    @Test
    void failedHandlerNotifiesTheSenderAndLogsTheCause() {
        var service = new CloudCommandService(() -> manager, arguments);
        service.registerCommands();
        var cause = new IllegalStateException("Test command failure");
        manager.command(manager.commandBuilder("testfailure").handler(context -> {
            throw cause;
        }));
        try (var logs = mockStatic(LogUtil.class)) {
            assertThrows(
                    CompletionException.class,
                    () -> service.execute(sender, "testfailure")
                            .toCompletableFuture()
                            .join());
            assertTrue(messages().stream().anyMatch(message -> message.contains("see console for details")));
            logs.verify(() -> LogUtil.error("Failed to execute Cult command", cause));
        }
    }

    @Test
    void localLogIsWrittenAfterRegisteringTheFullProxyCommandTree(@TempDir Path dataFolder) throws Exception {
        var service = new CloudCommandService(() -> manager, arguments);
        service.registerCommands();
        PlatformLoader loader = mock(PlatformLoader.class);
        GrimPlugin plugin = mock(GrimPlugin.class);
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        AsyncScheduler async = mock(AsyncScheduler.class);
        when(loader.getPlugin()).thenReturn(plugin);
        when(loader.getScheduler()).thenReturn(scheduler);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(scheduler.getAsyncScheduler()).thenReturn(async);
        doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return null;
                })
                .when(async)
                .runNow(eq(plugin), any(Runnable.class));

        var loaderField = CultAPI.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        Object previousLoader = loaderField.get(CultAPI.INSTANCE);
        var flagsField = SuperDebug.class.getDeclaredField("flags");
        flagsField.setAccessible(true);
        DebugText[] flags = (DebugText[]) flagsField.get(null);
        DebugText previousLog = flags[42];
        String log = "Player: ProxyTest\nDebug: saved locally ✓\n";
        try {
            loaderField.set(CultAPI.INSTANCE, loader);
            flags[42] = new DebugText.Builder().append(log).toText();
            service.execute(sender, "grim log 42 -l").toCompletableFuture().join();
            try (var files = Files.list(dataFolder.resolve("debug-logs"))) {
                var paths = files.toList();
                assertEquals(1, paths.size());
                assertTrue(paths.getFirst().getFileName().toString().startsWith("flag-42-"));
                assertEquals(log, Files.readString(paths.getFirst()));
            }
            assertTrue(messages().stream().anyMatch(message -> message.contains("Wrote debug log to:")));
        } finally {
            flags[42] = previousLog;
            loaderField.set(CultAPI.INSTANCE, previousLoader);
        }
    }

    @Test
    void resetFlagDoesNotRequireAnExplicitPlayer() {
        new CloudCommandService(() -> manager, arguments).registerCommands();
        var selfContext = new CommandContext<>(sender, manager);
        var selfCommand = manager.commandTree()
                .parse(selfContext, CommandInput.of("grim debug proxyticks --reset"), Runnable::run)
                .join();
        assertNotNull(selfCommand);
        assertTrue(selfCommand.components().stream()
                .anyMatch(component -> component.name().equals("--reset")));
        assertTrue(selfContext.optional("target").isEmpty());

        var targetContext = new CommandContext<>(sender, manager);
        assertNotNull(manager.commandTree()
                .parse(targetContext, CommandInput.of("grim debug proxyticks Target --reset"), Runnable::run)
                .join());
        assertTrue(targetContext.flags().hasFlag("reset"));
        assertTrue(targetContext.optional("target").isPresent());
    }

    @Test
    void debugSubcommandsAndTheirFlagsCanBeCompletedAlongsideTheOptionalPlayer() {
        new CloudCommandService(() -> manager, arguments).registerCommands();
        for (String command : List.of(
                "cult debug proxyticks --reset",
                "cult debug proxyticks Target --reset",
                "grim debug proxyticks Target",
                "cult debug itemslow Target",
                "cult debug places Target",
                "cult debug OfflinePlayer ")) {
            for (int end = 1; end <= command.length(); end++) {
                String input = command.substring(0, end);
                assertDoesNotThrow(() -> suggestions(input), input);
            }
        }
        assertTrue(suggestions("cult debug prox").contains("proxyticks"));
        var resetSuggestions = suggestions("cult debug proxyticks --r");
        var targetedResetSuggestions = suggestions("cult debug proxyticks Target --r");
        assertTrue(resetSuggestions.contains("--reset"), resetSuggestions::toString);
        assertTrue(targetedResetSuggestions.contains("--reset"), targetedResetSuggestions::toString);
    }

    private List<String> suggestions(String input) {
        return manager.suggestionFactory().suggest(sender, input).join().list().stream()
                .map(Suggestion::suggestion)
                .toList();
    }

    private List<String> messages() {
        var messages = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }
}
