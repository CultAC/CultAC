package ac.cult.cultac.platform.velocity.bridge.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bridge.protocol.BridgeMessage.Command;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandDone;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandOutput;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggest;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggestions;
import ac.cult.cultac.bridge.protocol.CommandRoots;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.FakeBridgeSession;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeCommandHandlerTest {
    private static final UUID STAFF = UUID.fromString("2b5f1e7a-6a39-4d63-9a3f-0f1e2d3c4b5a");
    private static final List<String> LOADER_LINES = List.of(
            "cult runtimes #1 lpv user x permission set * true",
            "CULTAC Update x.jar",
            "grim  devswap",
            "grimac RUNTIMES",
            "/cult runtime\u017f #1 lpv user x",
            "cult devswap\u0000",
            "cultac runtimes\u001c");
    private static final List<String> CLOUD_LINES = List.of(
            "cult label 0f6c1a52-3b1d-4b0e-8f43-7d2a9c5e1b20 killaura high 24h fabricated",
            "cult label revoke 5d8e2f10-9a4b-4c7d-b1e3-2f6a8c0d4e97 fabricated",
            "/CULTAC Label list Victim",
            "grim findings Victim 200",
            "grimac  TOP",
            "cult top killaura",
            "Cult Cloud",
            "cult findings ",
            "cult \u2003label 0f6c1a52-3b1d-4b0e-8f43-7d2a9c5e1b20 killaura high 24h fabricated",
            "cult \u00a0findings Victim 200",
            "cult \u3000runtimes #1 lpv user x");

    private final CommandManager commands = mock(CommandManager.class);
    private final Player staff = mock(Player.class);
    private final FakeBridgeSession session = new FakeBridgeSession(UUID.randomUUID(), "lobby");
    private long now;
    private Set<String> foreignRoots = Set.of();
    private final BridgeCommandHandler handler = new BridgeCommandHandler(
            commands,
            () -> CommandRoots.DEFAULT,
            root -> !foreignRoots.contains(root),
            id -> STAFF.equals(id) ? staff : null,
            () -> now,
            Logger.getLogger("command-test"));

    @BeforeEach
    void connectStaff() {
        connect(staff, "lobby");
    }

    @Test
    void nonCultAcRootIsRefused() {
        handler.onMessage(session, new Command(11, Command.CONSOLE, "CONSOLE", "/lpv user evil permission set * true"));
        handler.onMessage(session, new Command(12, STAFF, "Staff", "shutdown"));

        verify(commands, never()).executeAsync(any(), anyString());
        CommandOutput refused =
                new CommandOutput(11, GsonComponentSerializer.gson().serialize(BridgeCommandHandler.NOT_CULTAC));
        assertEquals(
                List.of(
                        refused,
                        new CommandDone(11),
                        new CommandOutput(12, refused.componentJson()),
                        new CommandDone(12)),
                session.sent);
    }

    @Test
    void onlyOwnedCultAcRootsAreForwardableInAnyCase() {
        Predicate<String> owned = root -> !root.equals("grim");
        List<String> roots = CommandRoots.DEFAULT;
        assertTrue(BridgeCommandHandler.forwardable("CULT", roots, owned));
        assertFalse(BridgeCommandHandler.forwardable("gl", roots, owned));
        assertTrue(BridgeCommandHandler.forwardable("GrimAC alerts", roots, owned));
        assertTrue(BridgeCommandHandler.forwardable("cultac reload", roots, owned));
        assertFalse(BridgeCommandHandler.forwardable("GRIM reload", roots, owned));
        assertFalse(BridgeCommandHandler.forwardable("cultacx reload", roots, owned));
        assertFalse(BridgeCommandHandler.forwardable("velocity reload", roots, owned));
        assertFalse(BridgeCommandHandler.forwardable("", roots, owned));
    }

    @Test
    void onlyTheProxysConfiguredRootsAreForwardable() {
        List<String> roots = List.of("guard", "gd");
        Predicate<String> owned = roots::contains;
        assertTrue(BridgeCommandHandler.forwardable("guard alerts", roots, owned));
        assertTrue(BridgeCommandHandler.forwardable("GD reload", roots, owned));
        assertFalse(BridgeCommandHandler.forwardable("guard update x.jar", roots, owned));
        assertFalse(BridgeCommandHandler.forwardable("guard findings Victim", roots, owned));
        for (String root : CommandRoots.DEFAULT) {
            assertFalse(BridgeCommandHandler.forwardable(root + " alerts", roots, registered -> true), root);
        }
        assertFalse(BridgeCommandHandler.forwardable("guard alerts", roots, root -> false));
    }

    @Test
    void rateLimitedCommandsGetTheirReply() {
        session.admitted = 0;

        handler.onMessage(session, new Command(20, Command.CONSOLE, "CONSOLE", "lpv user x"));
        handler.onMessage(session, new Command(21, Command.CONSOLE, "CONSOLE", "cult reload"));
        handler.onMessage(session, new Suggest(22, Command.CONSOLE, "cult "));

        verifyNoInteractions(commands);
        assertEquals(List.of(true, true), session.admitRequests);
        assertEquals(
                List.of(
                        new CommandOutput(
                                20, GsonComponentSerializer.gson().serialize(BridgeCommandHandler.NOT_CULTAC)),
                        new CommandDone(20),
                        new CommandOutput(
                                21, GsonComponentSerializer.gson().serialize(BridgeCommandHandler.RATE_LIMITED)),
                        new CommandDone(21),
                        new Suggestions(22, List.of())),
                session.sent);
    }

    @Test
    void senderOnAnotherServerIsRefused() {
        connect(staff, "survival");

        handler.onMessage(session, new Command(13, STAFF, "Staff", "cult alerts"));
        handler.onMessage(session, new Suggest(14, STAFF, "cult "));

        verify(commands, never()).executeAsync(any(), anyString());
        verify(commands, never()).offerSuggestions(any(), anyString());
        assertInstanceOf(CommandOutput.class, session.sent.get(0));
        assertEquals(new CommandDone(13), session.sent.get(1));
        assertEquals(new Suggestions(14, List.of()), session.sent.get(2));
    }

    @Test
    void cultAcRootTakenOverByAnotherPluginIsRefused() {
        foreignRoots = Set.of("grim");

        handler.onMessage(session, new Command(16, Command.CONSOLE, "CONSOLE", "grim reload"));
        handler.onMessage(session, new Suggest(17, Command.CONSOLE, "GRIM "));

        verify(commands, never()).executeAsync(any(), anyString());
        verify(commands, never()).offerSuggestions(any(), anyString());
        assertEquals(
                List.of(
                        new CommandOutput(
                                16, GsonComponentSerializer.gson().serialize(BridgeCommandHandler.NOT_CULTAC)),
                        new CommandDone(16),
                        new Suggestions(17, List.of())),
                session.sent);
    }

    @Test
    void commandsFromABackendWithoutTheCommandsCapabilityAreIgnored() {
        session.capabilities.remove(ServerCapability.COMMANDS);

        handler.onMessage(session, new Command(18, Command.CONSOLE, "CONSOLE", "cult reload"));
        handler.onMessage(session, new Suggest(19, Command.CONSOLE, "cult "));

        verifyNoInteractions(commands);
        assertTrue(session.sent.isEmpty());
    }

    @Test
    void ownedRootsAreTheOnesCultAcsPluginRegistered() {
        Object cultAc = new Object();
        CommandMeta cult = meta(cultAc);
        for (String root : List.of("cult", "cultac", "grim", "grimac")) {
            when(commands.getCommandMeta(root)).thenReturn(cult);
        }
        CommandMeta foreign = meta(new Object());
        when(commands.getCommandMeta("gl")).thenReturn(foreign);
        Predicate<String> owned = BridgeCommandService.ownedRoots(commands, cultAc);
        assertTrue(owned.test("cult"));
        assertFalse(owned.test("gl"));

        CommandMeta pluginless = meta(null);
        when(commands.getCommandMeta("grim")).thenReturn(pluginless);
        when(commands.getCommandMeta("cultac")).thenReturn(null);
        assertFalse(owned.test("grim"));
        assertFalse(owned.test("cultac"));
        assertTrue(owned.test("grimac"));
    }

    private static CommandMeta meta(Object plugin) {
        CommandMeta meta = mock(CommandMeta.class);
        when(meta.getPlugin()).thenReturn(plugin);
        return meta;
    }

    @Test
    void nonCultAcSuggestionsAreRefused() {
        handler.onMessage(session, new Suggest(15, Command.CONSOLE, "lpv user "));

        verify(commands, never()).offerSuggestions(any(), anyString());
        assertEquals(List.of(new Suggestions(15, List.of())), session.sent);
    }

    private static void connect(Player player, String server) {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo())
                .thenReturn(new ServerInfo(server, InetSocketAddress.createUnresolved(server, 1)));
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
    }

    @Test
    void consoleRunsAsARelayWithCultAcPermissions() {
        CommandSource[] source = new CommandSource[1];
        when(commands.executeAsync(any(), eq("cult history"))).thenAnswer(invocation -> {
            source[0] = invocation.getArgument(0);
            source[0].sendMessage(Component.text("first"));
            source[0].sendMessage(Component.text("second"));
            return CompletableFuture.completedFuture(true);
        });

        handler.onMessage(session, new Command(7, Command.CONSOLE, "CONSOLE", "/cult history"));

        RelayCommandSource relay = assertInstanceOf(RelayCommandSource.class, source[0]);
        assertEquals(Tristate.TRUE, relay.getPermissionValue("cult.admin"));
        assertTrue(relay.hasPermission("anything.at.all"));
        assertEquals(List.of(output(7, "first"), output(7, "second"), new CommandDone(7)), session.sent);
    }

    @Test
    void relayedConsoleCannotAdministerRuntimes() {
        CommandSource[] source = new CommandSource[1];
        when(commands.executeAsync(any(), anyString())).thenAnswer(invocation -> {
            source[0] = invocation.getArgument(0);
            return CompletableFuture.completedFuture(true);
        });

        handler.onMessage(session, new Command(33, Command.CONSOLE, "CONSOLE", "/cult reload"));

        assertInstanceOf(RelayCommandSource.class, source[0]);
        for (String node : List.of("cult.runtimes", "cult.update", "cult.devswap")) {
            assertEquals(Tristate.FALSE, source[0].getPermissionValue(node), node);
            assertFalse(source[0].hasPermission(node), node);
        }
        assertTrue(source[0].hasPermission("cult.reload"));
    }

    @Test
    void loaderCommandsAreRefusedFromAPrivilegedPlayer() {
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(commands.executeAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(true));

        for (int i = 0; i < LOADER_LINES.size(); i++) {
            handler.onMessage(session, new Command(40 + i, STAFF, "Staff", LOADER_LINES.get(i)));
        }

        verify(commands, never()).executeAsync(any(), anyString());
        assertEquals(refusals(LOADER_LINES, 40), session.sent);
    }

    @Test
    void loaderCommandsAreRefusedFromTheConsole() {
        when(commands.executeAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(true));

        for (int i = 0; i < LOADER_LINES.size(); i++) {
            handler.onMessage(session, new Command(50 + i, Command.CONSOLE, "CONSOLE", LOADER_LINES.get(i)));
        }

        verify(commands, never()).executeAsync(any(), anyString());
        assertEquals(refusals(LOADER_LINES, 50), session.sent);
    }

    @Test
    void loaderCommandsGetNoSuggestions() {
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(commands.offerSuggestions(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("lpv")));

        List<Suggestions> expected = new ArrayList<>();
        for (int i = 0; i < LOADER_LINES.size(); i++) {
            handler.onMessage(session, new Suggest(60 + i, STAFF, LOADER_LINES.get(i)));
            handler.onMessage(session, new Suggest(70 + i, Command.CONSOLE, LOADER_LINES.get(i)));
            expected.add(new Suggestions(60 + i, List.of()));
            expected.add(new Suggestions(70 + i, List.of()));
        }

        verify(commands, never()).offerSuggestions(any(), anyString());
        assertEquals(expected, session.sent);
    }

    @Test
    void cloudCommandsAreRefusedFromAPrivilegedPlayer() {
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(commands.executeAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(true));

        for (int i = 0; i < CLOUD_LINES.size(); i++) {
            handler.onMessage(session, new Command(80 + i, STAFF, "Staff", CLOUD_LINES.get(i)));
        }

        verify(commands, never()).executeAsync(any(), anyString());
        assertEquals(refusals(CLOUD_LINES, 80), session.sent);

        handler.onMessage(session, new Command(99, STAFF, "Staff", "cult alerts"));
        verify(commands).executeAsync(same(staff), eq("cult alerts"));
    }

    @Test
    void cloudCommandsAreRefusedFromTheConsole() {
        when(commands.executeAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(true));

        for (int i = 0; i < CLOUD_LINES.size(); i++) {
            handler.onMessage(session, new Command(100 + i, Command.CONSOLE, "CONSOLE", CLOUD_LINES.get(i)));
        }

        verify(commands, never()).executeAsync(any(), anyString());
        assertEquals(refusals(CLOUD_LINES, 100), session.sent);
    }

    @Test
    void cloudCommandsGetNoSuggestions() {
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(commands.offerSuggestions(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("Victim")));

        List<Suggestions> expected = new ArrayList<>();
        for (int i = 0; i < CLOUD_LINES.size(); i++) {
            handler.onMessage(session, new Suggest(120 + i, STAFF, CLOUD_LINES.get(i)));
            handler.onMessage(session, new Suggest(140 + i, Command.CONSOLE, CLOUD_LINES.get(i)));
            expected.add(new Suggestions(120 + i, List.of()));
            expected.add(new Suggestions(140 + i, List.of()));
        }

        verify(commands, never()).offerSuggestions(any(), anyString());
        assertEquals(expected, session.sent);
    }

    private static List<Object> refusals(List<String> lines, int firstId) {
        String refused = GsonComponentSerializer.gson().serialize(BridgeCommandHandler.NOT_CULTAC);
        List<Object> frames = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            frames.add(new CommandOutput(firstId + i, refused));
            frames.add(new CommandDone(firstId + i));
        }
        return frames;
    }

    @Test
    void backendTextIsSanitisedInRepliesAndLogs() {
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger logger = Logger.getLogger("command-test");
        logger.addHandler(capture);
        try {
            when(commands.executeAsync(any(), anyString()))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

            handler.onMessage(session, new Command(34, UUID.randomUUID(), "Gh\nost\u00a7c", "cult reload"));
            handler.onMessage(session, new Command(35, STAFF, "St\u00a7aff\r", "cult reload \u00a7c[INFO] forged"));
        } finally {
            logger.removeHandler(capture);
        }

        CommandOutput refused = assertInstanceOf(CommandOutput.class, session.sent.getFirst());
        assertEquals(
                "Gh?ost?c is not connected to the CultAC proxy on lobby.",
                PlainTextComponentSerializer.plainText()
                        .serialize(GsonComponentSerializer.gson().deserialize(refused.componentJson())));
        assertEquals(1, records.size());
        assertEquals(
                "Command forwarded from lobby by St?aff? failed: cult reload ?c[INFO] forged",
                records.getFirst().getMessage());
    }

    @Test
    void consoleOutputLingersThirtySecondsAfterDone() {
        CommandSource[] source = new CommandSource[1];
        when(commands.executeAsync(any(), anyString())).thenAnswer(invocation -> {
            source[0] = invocation.getArgument(0);
            return CompletableFuture.completedFuture(true);
        });
        handler.onMessage(session, new Command(3, Command.CONSOLE, "CONSOLE", "cult reload"));

        now += TimeUnit.SECONDS.toNanos(29);
        source[0].sendMessage(Component.text("async reply"));
        now += TimeUnit.SECONDS.toNanos(2);
        source[0].sendMessage(Component.text("too late"));

        assertEquals(List.of(new CommandDone(3), output(3, "async reply")), session.sent);
    }

    @Test
    void playerCommandRunsAsTheProxyPlayer() {
        when(commands.executeAsync(same(staff), eq("cult alerts"))).thenReturn(CompletableFuture.completedFuture(true));

        handler.onMessage(session, new Command(4, STAFF, "Staff", "cult alerts"));

        verify(commands).executeAsync(same(staff), eq("cult alerts"));
        assertEquals(List.of(new CommandDone(4)), session.sent);
    }

    @Test
    void refusedPlayerCommandGetsOneUnknownCommandLine() {
        when(commands.executeAsync(same(staff), eq("cult reload")))
                .thenReturn(CompletableFuture.completedFuture(false));

        handler.onMessage(session, new Command(10, STAFF, "Staff", "/cult reload"));

        assertEquals(
                List.of(
                        new CommandOutput(
                                10, GsonComponentSerializer.gson().serialize(BridgeCommandHandler.UNKNOWN_COMMAND)),
                        new CommandDone(10)),
                session.sent);
        assertEquals(
                "Unknown command. Type \"/help\" for help.",
                PlainTextComponentSerializer.plainText().serialize(BridgeCommandHandler.UNKNOWN_COMMAND));
    }

    @Test
    void absentPlayerIsNeverPromotedToConsole() {
        handler.onMessage(session, new Command(5, UUID.randomUUID(), "Ghost", "cult reload"));

        verify(commands, never()).executeAsync(any(), anyString());
        assertInstanceOf(CommandOutput.class, session.sent.get(0));
        assertEquals(new CommandDone(5), session.sent.get(1));
    }

    @Test
    void sessionsThatAreNotReadyAreIgnored() {
        session.state = BridgeSession.State.HANDSHAKE;

        handler.onMessage(session, new Command(6, Command.CONSOLE, "CONSOLE", "cult reload"));
        handler.onMessage(session, new Suggest(7, Command.CONSOLE, "cult "));

        verifyNoInteractions(commands);
        assertTrue(session.sent.isEmpty());
    }

    @Test
    void suggestionsAreCapped() {
        List<String> many = IntStream.range(0, 400).mapToObj(i -> "player" + i).toList();
        when(commands.offerSuggestions(any(RelayCommandSource.class), eq("cult spectate ")))
                .thenReturn(CompletableFuture.completedFuture(many));

        handler.onMessage(session, new Suggest(8, Command.CONSOLE, "cult spectate "));

        Suggestions offered = assertInstanceOf(Suggestions.class, session.sent.getFirst());
        assertEquals(8, offered.requestId());
        assertEquals(CommandFrames.MAX_SUGGESTIONS, offered.suggestions().size());
        assertEquals(many.subList(0, CommandFrames.MAX_SUGGESTIONS), offered.suggestions());
    }

    @Test
    void oversizedOutputIsSplitIntoFrames() {
        String text = "x".repeat(CommandFrames.PAYLOAD_BYTES * 2);

        List<CommandOutput> outputs = CommandFrames.outputs(9, Component.text(text));

        StringBuilder joined = new StringBuilder();
        for (CommandOutput output : outputs) {
            assertTrue(output.componentJson().length() <= CommandFrames.PAYLOAD_BYTES);
            joined.append(PlainTextComponentSerializer.plainText()
                    .serialize(GsonComponentSerializer.gson().deserialize(output.componentJson())));
        }
        assertTrue(outputs.size() > 1);
        assertEquals(text, joined.toString());
    }

    private static CommandOutput output(int id, String text) {
        return new CommandOutput(id, GsonComponentSerializer.gson().serialize(Component.text(text)));
    }
}
