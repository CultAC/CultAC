package ac.cult.cultac.command;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.Command;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.handling.ExceptionContext;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.Nullable;

/**
 * The platform's player arguments, aware of the network. A typed player the roster places on another instance fails
 * the parse with a {@link PlayerElsewhereException}; {@link #handle} then forwards the whole command line to that
 * instance or engine generation for the commands that work across instances, and tells the sender the others only work
 * on the same server.
 * Without a transport, a player who is not here fails as with the platform's parser. While a transport runs, player
 * suggestions of the commands that work across instances add the roster's names.
 */
public final class NetworkPlayerArguments implements CloudPlatformCommandArguments {
    static final int MAX_SUGGESTIONS = 25;

    /** Marks a command that runs on this instance whichever instance its player is on. */
    public static final CloudKey<Boolean> LOCAL = CloudKey.of("cultac:local", Boolean.class);

    private static volatile List<Route> routes = List.of();

    private final CloudPlatformCommandArguments platform;

    public NetworkPlayerArguments(CloudPlatformCommandArguments platform) {
        this.platform = platform;
    }

    @Override
    public ParserDescriptor<Sender, PlayerSelector> singlePlayerSelectorParser() {
        ArgumentParser<Sender, PlayerSelector> base =
                platform.singlePlayerSelectorParser().parser();
        SuggestionProvider<Sender> players =
                merged(base.suggestionProvider(), platform.onlinePlayerSuggestions(), false);
        return ParserDescriptor.of(
                new ArgumentParser.FutureArgumentParser<Sender, PlayerSelector>() {
                    @Override
                    public CompletableFuture<ArgumentParseResult<PlayerSelector>> parseFuture(
                            CommandContext<Sender> context, CommandInput input) {
                        String name = input.peekString();
                        return base.parseFuture(context, input).thenApply(result -> {
                            PlayerElsewhereException elsewhere =
                                    result.failure().isEmpty() ? otherGeneration(context, name) : elsewhere(name);
                            return elsewhere == null ? result : ArgumentParseResult.failure(elsewhere);
                        });
                    }

                    @Override
                    public SuggestionProvider<Sender> suggestionProvider() {
                        return players;
                    }
                },
                PlayerSelector.class);
    }

    @Override
    public SuggestionProvider<Sender> onlinePlayerSuggestions() {
        return merged(SuggestionProvider.noSuggestions(), platform.onlinePlayerSuggestions(), false);
    }

    /**
     * {@code base}, a player name that may be offline here, failing with a {@link PlayerElsewhereException} for a name
     * the roster places on another instance.
     */
    public static ParserDescriptor<Sender, String> playerName(ParserDescriptor<Sender, String> base) {
        return ParserDescriptor.of(
                base.parser().flatMapSuccess((context, name) -> {
                    PlayerElsewhereException elsewhere = elsewhere(name);
                    if (elsewhere == null) elsewhere = otherGeneration(context, name);
                    return elsewhere == null
                            ? ArgumentParseResult.successFuture(name)
                            : ArgumentParseResult.failureFuture(elsewhere);
                }),
                String.class);
    }

    /**
     * {@code selector}, also accepting a player the roster places on another instance. Without a transport, a player
     * who is not here fails as with {@code selector}.
     */
    public static ParserDescriptor<Sender, NetworkTarget> anyInstance(
            ParserDescriptor<Sender, PlayerSelector> selector) {
        ArgumentParser<Sender, PlayerSelector> base = selector.parser();
        return ParserDescriptor.of(
                new ArgumentParser.FutureArgumentParser<Sender, NetworkTarget>() {
                    @Override
                    public CompletableFuture<ArgumentParseResult<NetworkTarget>> parseFuture(
                            CommandContext<Sender> context, CommandInput input) {
                        int start = input.cursor();
                        return base.parseFuture(context, input).thenApply(result -> {
                            PlayerSelector here = result.parsedValue().orElse(null);
                            if (here != null) return ArgumentParseResult.success(new NetworkTarget(here, null));
                            Throwable failure = result.failure().orElseThrow();
                            if (!(failure instanceof PlayerElsewhereException elsewhere))
                                return ArgumentParseResult.failure(failure);
                            if (input.cursor() == start) input.readString();
                            return ArgumentParseResult.success(new NetworkTarget(null, elsewhere.where()));
                        });
                    }

                    @Override
                    public SuggestionProvider<Sender> suggestionProvider() {
                        return merged(base.suggestionProvider(), SuggestionProvider.noSuggestions(), true);
                    }
                },
                NetworkTarget.class);
    }

    /** {@code name} on another instance; null when it is here, nowhere the network knows, or no transport runs. */
    private static @Nullable PlayerElsewhereException elsewhere(String name) {
        if (name.isEmpty()) return null;
        NetworkRouting routing = NetworkRouting.current();
        if (!routing.enabled()) return null;
        NetworkPlayer where = routing.locate(name).orElse(null);
        return where == null || where.instance().equals(routing.instanceId())
                ? null
                : new PlayerElsewhereException(name, where);
    }

    /** {@code name} here but held by another engine generation, for a command that runs where its player is. */
    private static @Nullable PlayerElsewhereException otherGeneration(CommandContext<Sender> context, String name) {
        if (name.isEmpty()) return null;
        NetworkRouting routing = NetworkRouting.current();
        NetworkPlayer where = routing.locate(name).orElse(null);
        return where != null
                        && where.instance().equals(routing.instanceId())
                        && routing.otherGeneration(where.uuid())
                        && routed(line(context))
                ? new PlayerElsewhereException(name, where)
                : null;
    }

    private static String line(CommandContext<Sender> context) {
        String line = context.rawInput().input().strip();
        return line.startsWith("/") ? line.substring(1).strip() : line;
    }

    /**
     * {@code first}'s suggestions, then {@code local}'s; while a transport runs and the command works across instances
     * or {@code always} holds, the roster's names too, all matching the typed prefix, without duplicates and at most
     * {@value #MAX_SUGGESTIONS}.
     */
    private static SuggestionProvider<Sender> merged(
            SuggestionProvider<Sender> first, SuggestionProvider<Sender> local, boolean always) {
        return (context, input) -> first.suggestionsFuture(context, input.copy())
                .thenCombine(local.suggestionsFuture(context, input.copy()), (base, players) -> {
                    NetworkRouting routing = NetworkRouting.current();
                    boolean network = routing.enabled() && (always || routed(line(context)));
                    String prefix = input.copy().skipWhitespace().peekString().toLowerCase(Locale.ROOT);
                    Set<String> seen = new HashSet<>();
                    List<Suggestion> out = new ArrayList<>();
                    for (Iterable<? extends Suggestion> each : List.of(base, players)) {
                        for (Suggestion suggestion : each) {
                            if (network && !matches(suggestion.suggestion(), prefix)) continue;
                            if (seen.add(suggestion.suggestion().toLowerCase(Locale.ROOT))) out.add(suggestion);
                        }
                    }
                    if (!network) return out;
                    for (NetworkPlayer player : routing.roster()) {
                        if (matches(player.name(), prefix)
                                && seen.add(player.name().toLowerCase(Locale.ROOT)))
                            out.add(Suggestion.suggestion(player.name()));
                    }
                    return out.size() > MAX_SUGGESTIONS ? List.copyOf(out.subList(0, MAX_SUGGESTIONS)) : out;
                });
    }

    private static boolean matches(String suggestion, String prefix) {
        return suggestion.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    /**
     * Forwards a command whose player argument is on another instance, when the command works across instances.
     * Other parse failures go on to the next handler.
     */
    static void handle(ExceptionContext<Sender, ArgumentParseException> context) throws Throwable {
        if (!(context.exception().getCause() instanceof PlayerElsewhereException elsewhere)) throw context.exception();
        Sender sender = context.context().sender();
        NetworkPlayer where = elsewhere.where();
        String line = line(context.context());
        if (!routed(line)) {
            sender.sendMessage(whereabouts(
                    sender,
                    where,
                    "network-command-same-server",
                    "&c%player% is on %location%; this command only works on the same server."));
            return;
        }
        NetworkRouting.current().run(sender, line, where);
    }

    /** The message at {@code key}, {@code %player%} and {@code %location%} naming {@code where}. */
    public static Component whereabouts(Sender sender, NetworkPlayer where, String key, String fallback) {
        String location = where.instance() + (where.server() == null ? "" : " (" + where.server() + ")");
        return MessageUtil.getParsedComponent(
                sender, key, fallback, Map.of("player", where.name(), "location", location));
    }

    /**
     * Takes the literal paths after the root of {@code commands}. A command runs where its player is unless it carries
     * {@link #LOCAL}.
     */
    static void register(Collection<Command<Sender>> commands) {
        List<Route> registered = new ArrayList<>();
        for (Command<Sender> command : commands) {
            List<Set<String>> path = new ArrayList<>();
            List<CommandComponent<Sender>> components = command.components();
            for (int i = 1; i < components.size(); i++) {
                CommandComponent<Sender> component = components.get(i);
                if (component.type() != CommandComponent.ComponentType.LITERAL) break;
                Set<String> names = new HashSet<>();
                names.add(component.name().toLowerCase(Locale.ROOT));
                for (String alias : component.aliases()) names.add(alias.toLowerCase(Locale.ROOT));
                path.add(names);
            }
            if (!path.isEmpty())
                registered.add(new Route(path, !command.commandMeta().getOrDefault(LOCAL, false)));
        }
        routes = List.copyOf(registered);
    }

    /**
     * Whether {@code line}, root first, names a registered command that runs where its player is. The longest literal
     * path matching the line decides; a line no command matches is not routed.
     */
    static boolean routed(String line) {
        String[] tokens = line.toLowerCase(Locale.ROOT).split("\\s+");
        int longest = 0;
        boolean routed = false;
        for (Route route : routes) {
            List<Set<String>> path = route.path();
            if (tokens.length <= path.size() || path.size() < longest) continue;
            boolean matches = true;
            for (int i = 0; i < path.size() && matches; i++) matches = path.get(i).contains(tokens[i + 1]);
            if (!matches) continue;
            routed = path.size() > longest ? route.routed() : routed && route.routed();
            longest = path.size();
        }
        return routed;
    }

    private record Route(List<Set<String>> path, boolean routed) {}

    /** A typed online player: {@code here} on this instance, otherwise {@code elsewhere} as the roster lists them. */
    public record NetworkTarget(
            @Nullable PlayerSelector here, @Nullable NetworkPlayer elsewhere) {}

    /** A typed player who is held elsewhere: on {@code where}, another instance or another engine generation here. */
    public static final class PlayerElsewhereException extends IllegalArgumentException {
        private final transient NetworkPlayer where;

        PlayerElsewhereException(String name, NetworkPlayer where) {
            super("Player is not on this server: " + name);
            this.where = where;
        }

        public NetworkPlayer where() {
            return where;
        }
    }
}
