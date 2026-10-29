package ac.cult.cultac.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.bridge.protocol.CommandRoots;
import ac.cult.cultac.command.NetworkPlayerArguments.NetworkTarget;
import ac.cult.cultac.command.NetworkPlayerArguments.PlayerElsewhereException;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkPlatform;
import ac.cult.cultac.network.routing.NetworkService;
import ac.cult.cultac.network.routing.PlainPlaceholders;
import ac.cult.cultac.network.routing.TestNetworks;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NetworkPlayerArgumentsTest {
    private static final NetworkPlayer ALICE = new NetworkPlayer(UUID.randomUUID(), "Alice", "p7", "lobby");
    private static final NetworkPlayer DANA = new NetworkPlayer(UUID.randomUUID(), "Dana", "p9", null);

    private final IsolatedCommandManager manager = new IsolatedCommandManager();
    private final Sender sender = mock(Sender.class);
    private final Local local = new Local("Adam", "Bob");
    private final TestNetworks networks = new TestNetworks();
    private final Players here =
            new Players().with(UUID.randomUUID(), "Adam", null).with(UUID.randomUUID(), "Bob", null);
    private final Players onP9 = new Players().with(DANA.uuid(), "Dana", null);
    private final List<String> ranOnP7 = new CopyOnWriteArrayList<>();
    private final List<String> ranOnP9 = new CopyOnWriteArrayList<>();
    private NetworkService p9;
    private CloudCommandService service;
    private PlainPlaceholders placeholders;

    /** A platform knowing only its own players by name, as the proxy's parser does. */
    private static final class Local implements CloudPlatformCommandArguments {
        final Map<String, Sender> players = new LinkedHashMap<>();

        Local(String... names) {
            for (String name : names) {
                Sender player = mock(Sender.class);
                when(player.getName()).thenReturn(name);
                players.put(name.toLowerCase(Locale.ROOT), player);
            }
        }

        @Override
        public ParserDescriptor<Sender, PlayerSelector> singlePlayerSelectorParser() {
            return ParserDescriptor.of(
                    StringParser.<Sender>stringParser().parser().flatMapSuccess((context, name) -> {
                        Sender player = players.get(name.toLowerCase(Locale.ROOT));
                        if (player == null)
                            return ArgumentParseResult.failureFuture(
                                    new IllegalArgumentException("Player is not online: " + name));
                        return ArgumentParseResult.successFuture(new PlayerSelector() {
                            @Override
                            public boolean isSingle() {
                                return true;
                            }

                            @Override
                            public Sender getSinglePlayer() {
                                return player;
                            }

                            @Override
                            public Collection<Sender> getPlayers() {
                                return List.of(player);
                            }

                            @Override
                            public String inputString() {
                                return name;
                            }
                        });
                    }),
                    PlayerSelector.class);
        }

        @Override
        public SuggestionProvider<Sender> onlinePlayerSuggestions() {
            return (context, input) -> CompletableFuture.completedFuture(players.values().stream()
                    .map(player -> Suggestion.suggestion(player.getName()))
                    .toList());
        }
    }

    /** Runs this instance, p3, as the current routing, with Alice on p7 and Dana on p9. */
    private void networked() {
        TestNetworks.install(networks.service("p3").platform(here).start());
        networks.service("p7")
                .platform(new Players().with(ALICE.uuid(), "Alice", "lobby"))
                .recording(ranOnP7)
                .start();
        p9 = networks.service("p9").platform(onP9).recording(ranOnP9).start();
    }

    /** Runs p3 alone, with no transport. */
    private void withoutTransport() {
        TestNetworks.install(
                networks.service("p3").platform(here).transport(null).start());
    }

    /** A proxy with RedisBungee installed. */
    private static final class Networked implements NetworkPlatform {
        @Override
        public Collection<OnlinePlayer> onlinePlayers() {
            return List.of();
        }

        @Override
        public String address() {
            return "proxy:25577";
        }

        @Override
        public @Nullable String redisBungeeId() {
            return "p3";
        }
    }

    @BeforeAll
    static void config() {
        OfflineCultTestBootstrap.installConfig();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        placeholders = new PlainPlaceholders();
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        when(sender.getName()).thenReturn("Mod");
        networked();
        service = new CloudCommandService(() -> manager, local, () -> CommandRoots.DEFAULT);
        service.registerCommands();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        NetworkModule.stop();
        NetworkModule.platform(null);
        placeholders.close();
    }

    private void execute(Sender who, String line) {
        service.execute(who, line)
                .toCompletableFuture()
                .handle((ran, failure) -> null)
                .join();
    }

    private List<String> messages() {
        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    @Test
    void theCommandsThatWorkAcrossInstancesForwardTheWholeLine() {
        List<String> lines = List.of(
                "cult debug mods Alice",
                "cult debug channels alice",
                "grim consoledebug Alice on",
                "cult debug javapacketlog Alice",
                "cult debug geyserpacketlog Alice --onjoin",
                "cult profile Alice",
                "grim findings alice 5",
                "cult testwebhook Alice");
        for (String line : lines) execute(sender, line);
        assertEquals(lines.stream().map(CloudCommandService::onRegisteredRoot).toList(), ranOnP7);
        assertTrue(ranOnP9.isEmpty());
        verify(sender, never()).sendMessage(any(Component.class));
    }

    @Test
    void theOtherPlayerCommandsSayTheyOnlyWorkOnTheSameServer() {
        for (String line : List.of("cult debug Alice", "cult debug itemslow Alice", "cult debug proxyticks Dana"))
            execute(sender, line);
        assertTrue(ranOnP7.isEmpty() && ranOnP9.isEmpty());
        assertEquals(
                List.of(
                        "Alice is on p7 (lobby); this command only works on the same server.",
                        "Alice is on p7 (lobby); this command only works on the same server.",
                        "Dana is on p9; this command only works on the same server."),
                messages());
    }

    @Test
    void aRoutedCommandAboutAPlayerAnotherGenerationHereHoldsRunsThereWithOrWithoutATransport() {
        UUID carol = UUID.randomUUID();
        CloudCommandService commands = new CloudCommandService(
                IsolatedCommandManager::new, new Local("Adam", "Carol"), () -> CommandRoots.DEFAULT);
        commands.registerCommands();
        for (boolean transport : new boolean[] {true, false}) {
            Map<String, Object> shared = new ConcurrentHashMap<>();
            List<String> ranOnOlder = new CopyOnWriteArrayList<>();
            List<String> ranOnCurrent = new CopyOnWriteArrayList<>();
            Players currentView = new Players().with(carol, "Carol", null);
            currentView.holders.put(carol, NetworkPlatform.Holder.OTHER);
            TestNetworks.Builder older = networks.service("p3")
                    .platform(new Players().with(carol, "Carol", null))
                    .host(TestNetworks.host(shared, false))
                    .recording(ranOnOlder);
            TestNetworks.Builder current = networks.service("p3")
                    .platform(currentView)
                    .host(TestNetworks.host(shared, true))
                    .recording(ranOnCurrent);
            if (!transport) {
                older.transport(null);
                current.transport(null);
            }
            NetworkService draining = older.start();
            TestNetworks.install(current.start());

            commands.execute(sender, "cult debug mods Carol")
                    .toCompletableFuture()
                    .handle((ran, failure) -> null)
                    .join();
            String variant = transport ? "with a transport" : "without one";
            assertEquals(List.of(CloudCommandService.onRegisteredRoot("cult debug mods Carol")), ranOnOlder, variant);
            assertTrue(ranOnCurrent.isEmpty(), variant);
            NetworkModule.stop();
            networks.stop(draining);
        }
        verify(sender, never()).sendMessage(any(Component.class));
    }

    @Test
    void aPlayerOnNoInstanceIsTheUsualParseFailure() {
        execute(sender, "cult debug mods Carol");
        assertTrue(ranOnP7.isEmpty() && ranOnP9.isEmpty());
        assertEquals(List.of("Invalid argument: Player is not online: Carol"), messages());
    }

    @Test
    void aLocalPlayerParsesAsBefore() {
        var result = new NetworkPlayerArguments(local)
                .singlePlayerSelectorParser()
                .parser()
                .parseFuture(new CommandContext<>(sender, manager), CommandInput.of("bob"))
                .join();
        assertSame(local.players.get("bob"), result.parsedValue().orElseThrow().getSinglePlayer());
    }

    @Test
    void withoutATransportAPlayerWhoIsNotHereIsTheUsualParseFailureEvenWithRedisBungee() {
        withoutTransport();
        NetworkModule.platform(new Networked());
        execute(sender, "cult debug mods Alice");
        assertTrue(ranOnP7.isEmpty() && ranOnP9.isEmpty());
        assertEquals(List.of("Invalid argument: Player is not online: Alice"), messages());
    }

    @Test
    void withoutATransportOrANetworkAPlayerWhoIsNotHereIsTheUsualParseFailure() {
        withoutTransport();
        execute(sender, "cult debug mods Alice");
        assertEquals(List.of("Invalid argument: Player is not online: Alice"), messages());
    }

    @Test
    void anyInstanceParsesALocalPlayerHereAndARosterPlayerElsewhere() {
        var parser = NetworkPlayerArguments.anyInstance(new NetworkPlayerArguments(local).singlePlayerSelectorParser())
                .parser();
        var context = new CommandContext<>(sender, manager);

        NetworkTarget bob = parser.parseFuture(context, CommandInput.of("bob"))
                .join()
                .parsedValue()
                .orElseThrow();
        assertSame(local.players.get("bob"), bob.here().getSinglePlayer());
        assertNull(bob.elsewhere());

        CommandInput input = CommandInput.of("alice next");
        NetworkTarget alice =
                parser.parseFuture(context, input).join().parsedValue().orElseThrow();
        assertNull(alice.here());
        assertEquals(ALICE, alice.elsewhere());
        assertEquals("next", input.skipWhitespace().readString());

        assertEquals(
                "Player is not online: Carol",
                parser.parseFuture(context, CommandInput.of("Carol"))
                        .join()
                        .failure()
                        .orElseThrow()
                        .getMessage());
    }

    @Test
    void anyInstanceTakesExactlyTheNameWhenThePlatformParserFailsWithoutReadingIt() {
        CloudPlatformCommandArguments peeking = new CloudPlatformCommandArguments() {
            @Override
            public ParserDescriptor<Sender, PlayerSelector> singlePlayerSelectorParser() {
                return ParserDescriptor.of(
                        (context, input) -> ArgumentParseResult.failure(
                                new IllegalArgumentException("Player is not online: " + input.peekString())),
                        PlayerSelector.class);
            }

            @Override
            public SuggestionProvider<Sender> onlinePlayerSuggestions() {
                return SuggestionProvider.noSuggestions();
            }
        };
        var parser = NetworkPlayerArguments.anyInstance(
                        new NetworkPlayerArguments(peeking).singlePlayerSelectorParser())
                .parser();

        CommandInput input = CommandInput.of("alice next");
        NetworkTarget alice = parser.parseFuture(new CommandContext<>(sender, manager), input)
                .join()
                .parsedValue()
                .orElseThrow();
        assertEquals(ALICE, alice.elsewhere());
        assertEquals("next", input.skipWhitespace().readString());
    }

    @Test
    void spectatingAPlayerElsewhereStillNeedsThePermissionAndAPlayerSender() {
        Sender denied = mock(Sender.class);
        when(denied.isPlayer()).thenReturn(true);
        execute(denied, "cult spectate Alice");
        verify(denied).sendMessage(any(Component.class));

        when(sender.isPlayer()).thenReturn(false);
        execute(sender, "cult spectate Alice");
        assertTrue(messages().getFirst().contains("can only be used by players"), messages()::toString);
        assertTrue(ranOnP7.isEmpty() && ranOnP9.isEmpty());
    }

    @Test
    void anyInstanceWithoutATransportFailsAsTheSelectorDoes() {
        withoutTransport();
        NetworkModule.platform(new Networked());
        var parser = NetworkPlayerArguments.anyInstance(new NetworkPlayerArguments(local).singlePlayerSelectorParser())
                .parser();

        assertEquals(
                "Player is not online: Alice",
                parser.parseFuture(new CommandContext<>(sender, manager), CommandInput.of("Alice"))
                        .join()
                        .failure()
                        .orElseThrow()
                        .getMessage());
    }

    @Test
    void aPacketLogNameFailsOnlyForAPlayerOnAnotherInstance() {
        var parser = NetworkPlayerArguments.playerName(StringParser.quotedStringParser())
                .parser();
        var context = new CommandContext<>(sender, manager);
        assertEquals(
                "Carol",
                parser.parseFuture(context, CommandInput.of("Carol"))
                        .join()
                        .parsedValue()
                        .orElseThrow());
        assertEquals(
                "Bob",
                parser.parseFuture(context, CommandInput.of("Bob"))
                        .join()
                        .parsedValue()
                        .orElseThrow());
        PlayerElsewhereException elsewhere = assertInstanceOf(
                PlayerElsewhereException.class,
                parser.parseFuture(context, CommandInput.of("Alice"))
                        .join()
                        .failure()
                        .orElseThrow());
        assertEquals(ALICE, elsewhere.where());

        withoutTransport();
        NetworkModule.platform(new Networked());
        assertEquals(
                "Alice",
                parser.parseFuture(context, CommandInput.of("Alice"))
                        .join()
                        .parsedValue()
                        .orElseThrow());
    }

    @Test
    void playerSuggestionsAddTheRostersNamesByPrefixWithoutDuplicates() {
        NetworkPlayerArguments arguments = new NetworkPlayerArguments(local);
        onP9.with(UUID.randomUUID(), "alex", null).with(UUID.randomUUID(), "BOB", null);
        networks.tick(p9);

        assertEquals(List.of("Alice", "alex"), suggestions(arguments.singlePlayerSelectorParser(), "al"));
        assertEquals(
                List.of("Adam", "Bob", "Alice", "Dana", "alex"),
                suggestions(arguments.singlePlayerSelectorParser(), ""));
        assertEquals(List.of("Bob"), suggestions(arguments.onlinePlayerSuggestions(), "b"));

        for (int i = 0; i < 40; i++) onP9.with(UUID.randomUUID(), "Al" + i, null);
        networks.tick(p9);
        List<String> many = suggestions(arguments.onlinePlayerSuggestions(), "AL");
        assertEquals(NetworkPlayerArguments.MAX_SUGGESTIONS, many.size());
        assertEquals(List.of("Alice", "alex", "Al0"), many.subList(0, 3));
    }

    @Test
    void withoutATransportSuggestionsAreThisInstancesPlayersOnly() {
        withoutTransport();
        NetworkPlayerArguments arguments = new NetworkPlayerArguments(local);
        assertEquals(List.of("Adam", "Bob"), suggestions(arguments.singlePlayerSelectorParser(), "a"));
        assertEquals(List.of("Adam", "Bob"), suggestions(arguments.onlinePlayerSuggestions(), ""));
    }

    private List<String> suggestions(ParserDescriptor<Sender, ?> descriptor, String typed) {
        return suggestions(descriptor.parser().suggestionProvider(), typed);
    }

    private List<String> suggestions(SuggestionProvider<Sender> provider, String typed) {
        List<String> names = new ArrayList<>();
        provider.suggestionsFuture(new CommandContext<>(true, sender, manager), CommandInput.of(typed))
                .join()
                .forEach(suggestion -> names.add(suggestion.suggestion()));
        return names;
    }

    @Test
    void theRoutedSubcommandsAreTheAllowlistedLiterals() {
        for (String line : List.of(
                "cult debug mods x",
                "GRIM Debug Channels x",
                "gl consoledebug x on",
                "cult debug javapacketlog x",
                "cult debug geyserpacketlog x",
                "Cult Profile x",
                "gl findings x 5",
                "cult testwebhook x")) assertTrue(NetworkPlayerArguments.routed(line), line);
        for (String line : List.of(
                "cult debug x",
                "cult debug rewind x",
                "cult debug places x",
                "cult debug itemslow x",
                "cult debug proxyticks x",
                "cult spectate x",
                "cult top")) assertFalse(NetworkPlayerArguments.routed(line), line);
    }

    @Test
    void permissionNodesListEveryRegisteredCommandsNode() {
        var nodes = service.permissionNodes();
        for (String node : List.of(
                "cult.debug", "cult.consoledebug", "cult.profile", "cult.cloud", "cult.cloud.view", "cult.cloud.label"))
            assertTrue(nodes.contains(node), node);
        assertFalse(nodes.contains(""));
        assertTrue(new CloudCommandService(IsolatedCommandManager::new, local, () -> CommandRoots.DEFAULT)
                .permissionNodes()
                .isEmpty());
    }
}
