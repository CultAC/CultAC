package ac.cult.cultac.command;

import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.handling.ExceptionContext;
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
 * Without a transport, a player who is not here on an instance that looks networked gets the network-disabled
 * message. While a transport runs, player suggestions add the roster's names.
 */
public final class NetworkPlayerArguments implements CloudPlatformCommandArguments {
    static final int MAX_SUGGESTIONS = 25;

    /** The subcommands, after the root, that run on the instance holding their player. */
    private static final List<List<String>> ROUTED = List.of(
            List.of("debug", "mods"),
            List.of("debug", "channels"),
            List.of("consoledebug"),
            List.of("debug", "javapacketlog"),
            List.of("debug", "geyserpacketlog"),
            List.of("profile"),
            List.of("findings"),
            List.of("testwebhook"));

    private final CloudPlatformCommandArguments platform;

    public NetworkPlayerArguments(CloudPlatformCommandArguments platform) {
        this.platform = platform;
    }

    @Override
    public ParserDescriptor<Sender, PlayerSelector> singlePlayerSelectorParser() {
        ArgumentParser<Sender, PlayerSelector> base =
                platform.singlePlayerSelectorParser().parser();
        SuggestionProvider<Sender> players = merged(base.suggestionProvider(), platform.onlinePlayerSuggestions());
        return ParserDescriptor.of(
                new ArgumentParser.FutureArgumentParser<Sender, PlayerSelector>() {
                    @Override
                    public CompletableFuture<ArgumentParseResult<PlayerSelector>> parseFuture(
                            CommandContext<Sender> context, CommandInput input) {
                        String name = input.peekString();
                        return base.parseFuture(context, input).thenApply(result -> {
                            PlayerElsewhereException elsewhere =
                                    result.failure().isEmpty() ? otherGeneration(context, name) : elsewhere(name, true);
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
        return merged(SuggestionProvider.noSuggestions(), platform.onlinePlayerSuggestions());
    }

    /**
     * {@code base}, a player name that may be offline here, failing with a {@link PlayerElsewhereException} for a name
     * the roster places on another instance.
     */
    public static ParserDescriptor<Sender, String> playerName(ParserDescriptor<Sender, String> base) {
        return ParserDescriptor.of(
                base.parser().flatMapSuccess((context, name) -> {
                    PlayerElsewhereException elsewhere = elsewhere(name, false);
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
                            if (!(failure instanceof PlayerElsewhereException elsewhere) || elsewhere.where() == null)
                                return ArgumentParseResult.failure(failure);
                            if (input.cursor() == start) input.readString();
                            return ArgumentParseResult.success(new NetworkTarget(null, elsewhere.where()));
                        });
                    }

                    @Override
                    public SuggestionProvider<Sender> suggestionProvider() {
                        return base.suggestionProvider();
                    }
                },
                NetworkTarget.class);
    }

    /**
     * Why {@code name} is not here: on another instance, or, for an online-player argument, possibly on one this
     * instance cannot reach. Null when it is here or nowhere the network knows.
     */
    private static @Nullable PlayerElsewhereException elsewhere(String name, boolean online) {
        if (name.isEmpty()) return null;
        NetworkRouting routing = NetworkRouting.current();
        if (routing.enabled()) {
            NetworkPlayer where = routing.locate(name).orElse(null);
            return where == null || where.instance().equals(routing.instanceId())
                    ? null
                    : new PlayerElsewhereException(name, where);
        }
        return online && NetworkModule.networked() ? new PlayerElsewhereException(name, null) : null;
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
     * {@code first}'s suggestions, then {@code local}'s; while a transport runs, the roster's names too, all matching
     * the typed prefix, without duplicates and at most {@value #MAX_SUGGESTIONS}.
     */
    private static SuggestionProvider<Sender> merged(
            SuggestionProvider<Sender> first, SuggestionProvider<Sender> local) {
        return (context, input) -> first.suggestionsFuture(context, input.copy())
                .thenCombine(local.suggestionsFuture(context, input.copy()), (base, players) -> {
                    NetworkRouting routing = NetworkRouting.current();
                    boolean network = routing.enabled();
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
        if (where == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "network-disabled-command",
                    "%prefix% &cNetwork-wide commands are not enabled on this server. Ask a server administrator to"
                            + " configure a messaging backend (Redis or NATS) in the CultAC configuration."));
            return;
        }
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

    /** Whether {@code line}, root first, is one of the subcommands that run where their player is. */
    static boolean routed(String line) {
        String[] tokens = line.toLowerCase(Locale.ROOT).split("\\s+");
        for (List<String> path : ROUTED) {
            if (tokens.length <= path.size()) continue;
            boolean matches = true;
            for (int i = 0; i < path.size() && matches; i++) matches = tokens[i + 1].equals(path.get(i));
            if (matches) return true;
        }
        return false;
    }

    /** A typed online player: {@code here} on this instance, otherwise {@code elsewhere} as the roster lists them. */
    public record NetworkTarget(
            @Nullable PlayerSelector here, @Nullable NetworkPlayer elsewhere) {}

    /**
     * A typed player who is not on this instance: on {@code where}, or, when it is null, possibly on an instance this
     * one has no transport to reach.
     */
    public static final class PlayerElsewhereException extends IllegalArgumentException {
        private final transient @Nullable NetworkPlayer where;

        PlayerElsewhereException(String name, @Nullable NetworkPlayer where) {
            super("Player is not on this server: " + name);
            this.where = where;
        }

        public @Nullable NetworkPlayer where() {
            return where;
        }
    }
}
