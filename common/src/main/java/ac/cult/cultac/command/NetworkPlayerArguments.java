package ac.cult.cultac.command;

import ac.cult.cultac.command.ShownNames.Match;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
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
 * suggestions of the commands that work across instances add the roster's names. A player typed by a name a server lists
 * them under rather than their account name matches that account ({@link ShownNames}), unless the name is also the
 * stored account of another player: the parse then fails with a {@link NameRefusedException} naming both.
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
                        int start = input.cursor();
                        return base.parseFuture(context, input).thenCompose(result -> {
                            PlayerElsewhereException elsewhere =
                                    result.failure().isEmpty() ? otherGeneration(context, name) : elsewhere(name);
                            if (elsewhere != null)
                                return CompletableFuture.completedFuture(ArgumentParseResult.failure(elsewhere));
                            if (result.failure().isEmpty()) return CompletableFuture.completedFuture(result);
                            return ShownNames.find(context.sender(), name).thenCompose(match -> {
                                if (match == null) return CompletableFuture.completedFuture(result);
                                if (input.cursor() == start) input.readString();
                                PlayerElsewhereException there = elsewhere(match.account());
                                if (there != null)
                                    return CompletableFuture.completedFuture(
                                            ArgumentParseResult.failure(there.typed(match)));
                                return base.parseFuture(context, CommandInput.of(match.account()))
                                        .thenApply(found -> {
                                            PlayerElsewhereException other = found.failure().isEmpty()
                                                    ? otherGeneration(context, match.account())
                                                    : null;
                                            if (other != null) return ArgumentParseResult.failure(other.typed(match));
                                            if (found.failure().isEmpty()) ShownNames.remember(context, match);
                                            return found;
                                        });
                            });
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
                    PlayerElsewhereException elsewhere = placed(context, name);
                    if (elsewhere != null) return ArgumentParseResult.failureFuture(elsewhere);
                    if (NetworkRouting.current().locate(name).isPresent())
                        return ArgumentParseResult.successFuture(name);
                    return listed(context.sender(), name, true, ShownNames::find, ShownNames::known)
                            .thenApply(listed -> {
                                if (listed.refused() != null) return ArgumentParseResult.failure(listed.refused());
                                Match match = listed.match();
                                if (match == null) return ArgumentParseResult.success(name);
                                PlayerElsewhereException there = placed(context, match.account());
                                if (there != null) return ArgumentParseResult.failure(there.typed(match));
                                ShownNames.remember(context, match);
                                return ArgumentParseResult.success(match.account());
                            });
                }),
                String.class);
    }

    /**
     * {@code base}, taking the account of an online player typed by the name the sender sees them listed under. Unlike
     * {@link #playerName} it never sends the command elsewhere.
     */
    public static ParserDescriptor<Sender, String> shownAs(ParserDescriptor<Sender, String> base) {
        return shownAs(base, true, ShownNames::find, ShownNames::known);
    }

    /**
     * {@code base}, failing with a {@link NameRefusedException} that names the account for a name the sender sees
     * listed for an online player of another account, for commands that change what is kept about the player.
     */
    public static ParserDescriptor<Sender, String> typedAccount(ParserDescriptor<Sender, String> base) {
        return shownAs(base, false, ShownNames::find, ShownNames::known);
    }

    static ParserDescriptor<Sender, String> shownAs(
            ParserDescriptor<Sender, String> base,
            boolean substitute,
            BiFunction<Sender, String, CompletableFuture<@Nullable Match>> find,
            Function<String, CompletableFuture<Optional<UUID>>> known) {
        return ParserDescriptor.of(
                base.parser().flatMapSuccess((context, name) -> {
                    if (NetworkRouting.current().locate(name).isPresent())
                        return ArgumentParseResult.successFuture(name);
                    return listed(context.sender(), name, substitute, find, known).thenApply(listed -> {
                        if (listed.refused() != null) return ArgumentParseResult.failure(listed.refused());
                        if (listed.match() == null) return ArgumentParseResult.success(name);
                        ShownNames.remember(context, listed.match());
                        return ArgumentParseResult.success(listed.match().account());
                    });
                }),
                String.class);
    }

    /** What a typed name is listed for: the account it matched, the reason it is refused, or neither. */
    private record Listed(@Nullable Match match, @Nullable NameRefusedException refused) {
        static final Listed NONE = new Listed(null, null);
    }

    /**
     * The account {@code find} gives {@code name}, refused when {@code known} gives a stored account of another player
     * by that name, or when {@code substitute} is off.
     */
    private static CompletableFuture<Listed> listed(
            Sender sender,
            String name,
            boolean substitute,
            BiFunction<Sender, String, CompletableFuture<@Nullable Match>> find,
            Function<String, CompletableFuture<Optional<UUID>>> known) {
        return find.apply(sender, name).thenCompose(match -> {
            if (match == null) return CompletableFuture.completedFuture(Listed.NONE);
            return known.apply(name).thenApply(stored -> {
                UUID account = stored.orElse(null);
                if (account != null && !account.equals(match.uuid()))
                    return new Listed(null, NameRefusedException.ambiguous(match, account));
                return substitute ? new Listed(match, null) : new Listed(null, NameRefusedException.listed(match));
            });
        });
    }

    /** {@code name} on another instance or held by another engine generation here; null otherwise. */
    private static @Nullable PlayerElsewhereException placed(CommandContext<Sender> context, String name) {
        PlayerElsewhereException elsewhere = elsewhere(name);
        return elsewhere != null ? elsewhere : otherGeneration(context, name);
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
        Sender sender = context.context().sender();
        if (context.exception().getCause() instanceof NameRefusedException refused) {
            sender.sendMessage(refused.message(sender));
            return;
        }
        if (!(context.exception().getCause() instanceof PlayerElsewhereException elsewhere)) throw context.exception();
        NetworkPlayer where = elsewhere.where();
        String line = line(context.context());
        if (!routed(line)) {
            sender.sendMessage(whereabouts(
                    sender,
                    where,
                    elsewhere.shown() == null ? where.name() : elsewhere.shown(),
                    "network-command-same-server",
                    "&c%player% is on %location%; this command only works on the same server."));
            return;
        }
        NetworkRouting.current().run(sender, line, where, elsewhere.shown());
    }

    /** The message at {@code key}, {@code %player%} and {@code %location%} naming {@code where}. */
    public static Component whereabouts(Sender sender, NetworkPlayer where, String key, String fallback) {
        return whereabouts(sender, where, where.name(), key, fallback);
    }

    /** The message at {@code key}, {@code %player%} being {@code player} and {@code %location%} where they are. */
    private static Component whereabouts(
            Sender sender, NetworkPlayer where, String player, String key, String fallback) {
        String location = where.instance() + (where.server() == null ? "" : " (" + where.server() + ")");
        return MessageUtil.getParsedComponent(sender, key, fallback, Map.of("player", player, "location", location));
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

    /**
     * A typed player who is held elsewhere: on {@code where}, another instance or another engine generation here,
     * typed as {@code shown}, a name a server lists them under, or by account when it is null.
     */
    public static final class PlayerElsewhereException extends IllegalArgumentException {
        private final transient NetworkPlayer where;
        private final @Nullable String shown;

        PlayerElsewhereException(String name, NetworkPlayer where) {
            this(name, where, null);
        }

        private PlayerElsewhereException(String name, NetworkPlayer where, @Nullable String shown) {
            super("Player is not on this server: " + name);
            this.where = where;
            this.shown = shown;
        }

        /** This player as typed by the shown name {@code match} was found for. */
        PlayerElsewhereException typed(Match match) {
            return new PlayerElsewhereException(match.shown(), where, match.shown());
        }

        public NetworkPlayer where() {
            return where;
        }

        public @Nullable String shown() {
            return shown;
        }
    }

    /** A typed name a command does not take for the account it is listed for; {@link #handle} tells the sender why. */
    public static final class NameRefusedException extends IllegalArgumentException {
        private final String key;
        private final String fallback;
        private final transient Map<String, String> placeholders;

        private NameRefusedException(String key, String fallback, Map<String, String> placeholders) {
            super("Player name is ambiguous: " + placeholders.get("shown"));
            this.key = key;
            this.fallback = fallback;
            this.placeholders = placeholders;
        }

        /** {@code match}'s name is also the stored account {@code account}. */
        static NameRefusedException ambiguous(Match match, UUID account) {
            return new NameRefusedException(
                    "player-name-ambiguous",
                    "%prefix% &c%shown% is an account (%uuid%) and also what %player% is listed as. "
                            + "Type %player% or the UUID instead.",
                    Map.of("shown", match.shown(), "player", match.account(), "uuid", account.toString()));
        }

        /** {@code match}'s name, for a command that takes only the account. */
        static NameRefusedException listed(Match match) {
            return new NameRefusedException(
                    "player-listed-as-type-account",
                    "%prefix% &f%shown% &7is &f%player%&7. Type &f%player% &7to run this on them.",
                    Map.of("shown", match.shown(), "player", match.account()));
        }

        public Component message(Sender sender) {
            return MessageUtil.getParsedComponent(sender, key, fallback, placeholders);
        }
    }
}
