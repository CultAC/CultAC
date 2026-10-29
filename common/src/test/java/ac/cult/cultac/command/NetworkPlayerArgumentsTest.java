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
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.RoutingHolder;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkPlatform;
import ac.cult.cultac.network.routing.PlainPlaceholders;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final Routing routing = new Routing();
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

    /** A routing whose roster lists this instance's players and others' on p7 and p9; runs are recorded. */
    private final class Routing implements NetworkRouting {
        boolean enabled = true;
        final List<NetworkPlayer> remote = new ArrayList<>(List.of(ALICE, DANA));
        final List<String> ran = new ArrayList<>();
        final List<NetworkPlayer> ranOn = new ArrayList<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String instanceId() {
            return "p3";
        }

        @Override
        public Optional<NetworkPlayer> locate(String nameOrUuid) {
            return roster().stream()
                    .filter(player -> player.name().equalsIgnoreCase(nameOrUuid))
                    .findFirst();
        }

        @Override
        public Collection<NetworkPlayer> roster() {
            List<NetworkPlayer> roster = new ArrayList<>();
            for (Sender player : local.players.values())
                roster.add(new NetworkPlayer(UUID.randomUUID(), player.getName(), "p3", null));
            if (enabled) roster.addAll(remote);
            return roster;
        }

        @Override
        public CompletableFuture<Boolean> run(Sender who, String line, NetworkPlayer where) {
            ran.add(line);
            ranOn.add(where);
            return CompletableFuture.completedFuture(true);
        }
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
        RoutingHolder.install(routing);
        service = new CloudCommandService(() -> manager, local, () -> CommandRoots.DEFAULT);
        service.registerCommands();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        RoutingHolder.uninstall(null);
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
        assertEquals(lines, routing.ran);
        assertTrue(routing.ranOn.stream().allMatch(ALICE::equals));
        verify(sender, never()).sendMessage(any(Component.class));
    }

    @Test
    void theOtherPlayerCommandsSayTheyOnlyWorkOnTheSameProxy() {
        for (String line : List.of("cult debug Alice", "cult debug itemslow Alice", "cult debug proxyticks Dana"))
            execute(sender, line);
        assertTrue(routing.ran.isEmpty());
        assertEquals(
                List.of(
                        "Alice is on p7 (lobby); this command only works on the same proxy.",
                        "Alice is on p7 (lobby); this command only works on the same proxy.",
                        "Dana is on p9; this command only works on the same proxy."),
                messages());
    }

    @Test
    void aPlayerOnNoInstanceIsTheUsualParseFailure() {
        execute(sender, "cult debug mods Carol");
        assertTrue(routing.ran.isEmpty());
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
    void withoutATransportAPlayerWhoIsNotHereGetsTheNetworkDisabledMessageOnANetwork() {
        routing.enabled = false;
        NetworkModule.platform(new Networked());
        execute(sender, "cult debug mods Alice");
        assertTrue(routing.ran.isEmpty());
        assertTrue(messages().getFirst().contains("Network-wide commands are not enabled"), messages()::toString);
    }

    @Test
    void withoutATransportOrANetworkAPlayerWhoIsNotHereIsTheUsualParseFailure() {
        routing.enabled = false;
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
        assertTrue(routing.ran.isEmpty());
    }

    @Test
    void anyInstanceWithoutATransportFailsAsTheSelectorDoes() {
        routing.enabled = false;
        NetworkModule.platform(new Networked());
        var parser = NetworkPlayerArguments.anyInstance(new NetworkPlayerArguments(local).singlePlayerSelectorParser())
                .parser();

        PlayerElsewhereException elsewhere = assertInstanceOf(
                PlayerElsewhereException.class,
                parser.parseFuture(new CommandContext<>(sender, manager), CommandInput.of("Alice"))
                        .join()
                        .failure()
                        .orElseThrow());
        assertNull(elsewhere.where());
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

        routing.enabled = false;
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
        routing.remote.add(new NetworkPlayer(UUID.randomUUID(), "alex", "p9", null));
        routing.remote.add(new NetworkPlayer(UUID.randomUUID(), "BOB", "p9", null));

        assertEquals(List.of("Alice", "alex"), suggestions(arguments.singlePlayerSelectorParser(), "al"));
        assertEquals(
                List.of("Adam", "Bob", "Alice", "Dana", "alex"),
                suggestions(arguments.singlePlayerSelectorParser(), ""));
        assertEquals(List.of("Bob"), suggestions(arguments.onlinePlayerSuggestions(), "b"));

        for (int i = 0; i < 40; i++) routing.remote.add(new NetworkPlayer(UUID.randomUUID(), "Al" + i, "p7", null));
        List<String> many = suggestions(arguments.onlinePlayerSuggestions(), "AL");
        assertEquals(NetworkPlayerArguments.MAX_SUGGESTIONS, many.size());
        assertEquals(List.of("Alice", "alex", "Al0"), many.subList(0, 3));
    }

    @Test
    void withoutATransportSuggestionsAreThisInstancesPlayersOnly() {
        routing.enabled = false;
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
