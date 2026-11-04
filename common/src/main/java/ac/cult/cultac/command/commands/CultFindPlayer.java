package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.network.NetworkRouting.SearchResult;
import ac.cult.cultac.network.PlayerSearchFilters;
import ac.cult.cultac.network.SearchBusyException;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.Nullable;

public class CultFindPlayer implements BuildableCommand {
    static final String PERMISSION = "cult.findplayer";
    static final String FILTER = "brand=";
    /** The filter value that lists the players whose brand is not known yet. */
    static final String UNKNOWN = "unknown";

    static final int MAX_LISTED = 100;

    private static final Comparator<@Nullable String> BRAND_ORDER =
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("findplayer", Description.of("Find online players by profile data"))
                .permission(PERMISSION)
                .optional(
                        "filter",
                        StringParser.greedyStringParser(),
                        SuggestionProvider.blockingStrings((context, input) ->
                                suggestions(NetworkRouting.current().roster(), input.remainingInput())))
                .futureHandler(context -> {
                    Sender sender = context.sender();
                    String query = context.<String>optional("filter").orElse("").strip();
                    if (query.isEmpty() || query.equalsIgnoreCase(FILTER)) {
                        summary(sender, NetworkRouting.current().roster()).forEach(sender::sendMessage);
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        PlayerSearchFilters.parse(query);
                    } catch (IllegalArgumentException invalid) {
                        usage(sender).forEach(sender::sendMessage);
                        return CompletableFuture.completedFuture(null);
                    }
                    return NetworkRouting.current()
                            .search(query, sender.getUniqueId() == null ? Sender.CONSOLE_UUID : sender.getUniqueId())
                            .thenAccept(
                                    result -> matching(sender, result, query).forEach(sender::sendMessage))
                            .exceptionally(failure -> {
                                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                                if (!(cause instanceof SearchBusyException)) throw new CompletionException(cause);
                                sender.sendMessage(CheckInfoLines.render(
                                        sender,
                                        template(
                                                "findplayer-wait",
                                                "%prefix% &7Please wait a moment before searching again."),
                                        Map.of()));
                                return null;
                            });
                }));
    }

    private static List<Component> usage(Sender sender) {
        return List.of(CheckInfoLines.render(
                sender,
                template(
                        "findplayer-usage",
                        "%prefix% &cUse /%command% findplayer brand=lunar [key=value ...]. Keys: brand, mod, channel, sens, vsens, ping, version, platform, server, proxy, name, uuid, fastmath. Text: case-insensitive substring; combine filters with AND. Numbers: x (within 0.5), >x, <x, a-b (inclusive). Sensitivity: 0-200 percent, unknown on Bedrock. Text values support double quotes and backslash escapes. No filters: brand counts."),
                Map.of()));
    }

    static List<String> suggestions(Collection<NetworkPlayer> players, String typed) {
        return PlayerSearchFilters.suggestions(players, typed);
    }

    private static List<Component> summary(Sender sender, Collection<NetworkPlayer> players) {
        List<Component> lines = new ArrayList<>();
        lines.add(CheckInfoLines.render(
                sender,
                template(
                        "findplayer-summary",
                        "%prefix% &bClient brands in use &8· &f%count% &7online &8· &7click a brand to see who uses"
                                + " it"),
                Map.of("count", players.size())));
        String unknown = unknownLabel();
        for (Map.Entry<@Nullable String, Integer> brand : counts(players)) {
            Map<String, Object> values =
                    Map.of("brand", brand.getKey() == null ? unknown : brand.getKey(), "count", brand.getValue());
            lines.add(CheckInfoLines.render(sender, template("findplayer-brand", "&8» &f%brand% &7(%count%)"), values)
                    .hoverEvent(HoverEvent.showText(CheckInfoLines.render(
                            sender, template("findplayer-brand-hover", "&7Show who uses %brand%"), values)))
                    .clickEvent(ClickEvent.runCommand(CloudCommandService.command("findplayer " + FILTER
                            + PlayerSearchFilters.quote(brand.getKey() == null ? UNKNOWN : brand.getKey())))));
        }
        return lines;
    }

    private static List<Component> matching(Sender sender, SearchResult result, String query) {
        var filters = PlayerSearchFilters.parse(query);
        boolean brandOnly = filters.size() == 1 && filters.getFirst().key().equals("brand");
        String displayQuery = brandOnly ? filters.getFirst().query() : query;
        List<NetworkPlayer> matches = result.players().stream()
                .sorted(Comparator.comparing(
                                (NetworkPlayer p) -> brandOnly ? "" : p.server() == null ? p.instance() : p.server(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(NetworkPlayer::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(NetworkPlayer::name))
                .toList();
        List<Component> warnings = result.unavailable() == 0
                ? List.of()
                : List.of(CheckInfoLines.render(
                        sender,
                        template(
                                "findplayer-unavailable",
                                "%prefix% &e%count% instance(s) did not answer; results are incomplete."),
                        Map.of("count", result.unavailable())));
        if (matches.isEmpty() && warnings.isEmpty())
            return List.of(CheckInfoLines.render(
                    sender,
                    template(
                            brandOnly ? "findplayer-none" : "findplayer-filter-none",
                            brandOnly
                                    ? "%prefix% &7Nobody online uses a brand containing &f%query%&7."
                                    : "%prefix% &7Nobody online matches &f%query%&7."),
                    Map.of("query", displayQuery)));
        List<Component> lines = new ArrayList<>();
        lines.add(CheckInfoLines.render(
                sender,
                template(
                        brandOnly ? "findplayer-results" : "findplayer-filter-results",
                        brandOnly
                                ? "%prefix% &bPlayers whose brand contains &f%query% &8· &f%count% &7online"
                                : "%prefix% &bPlayers matching &f%query% &8· &f%count% &7online"),
                Map.of("query", displayQuery, "count", result.total())));
        lines.addAll(warnings);
        String unknown = unknownLabel();
        for (NetworkPlayer player : matches.subList(0, Math.min(matches.size(), MAX_LISTED))) {
            Map<String, Object> values = Map.of(
                    "player",
                    player.name(),
                    "server",
                    player.server() == null ? player.instance() : player.server(),
                    "brand",
                    player.brand() == null ? unknown : player.brand(),
                    "values",
                    filters.stream()
                            .map(PlayerSearchFilters.Filter::key)
                            .distinct()
                            .map(key -> key + "="
                                    + String.join(", ", player.details().getOrDefault(key, List.of("unknown"))))
                            .reduce((a, b) -> a + "; " + b)
                            .orElse(""));
            lines.add(CheckInfoLines.render(
                            sender,
                            template(
                                    brandOnly ? "findplayer-player" : "findplayer-filter-player",
                                    brandOnly
                                            ? "&8» &f%player% &7on &f%server% &8· &7%brand%"
                                            : "&8» &f%player% &7on &f%server% &8· &7%values%"),
                            values)
                    .hoverEvent(HoverEvent.showText(CheckInfoLines.render(
                            sender, template("findplayer-player-hover", "&7Show %player%'s profile"), values)))
                    .clickEvent(ClickEvent.runCommand(CloudCommandService.command("profile " + player.name()))));
        }
        if (result.total() > MAX_LISTED)
            lines.add(CheckInfoLines.render(
                    sender,
                    template(
                            brandOnly ? "findplayer-more" : "findplayer-filter-more",
                            brandOnly
                                    ? "&7...and %count% more. Type more of the brand name to narrow it down."
                                    : "&7...and %count% more. Add filters to narrow it down."),
                    Map.of("count", result.total() - MAX_LISTED)));
        return lines;
    }

    /** Each brand online, null for not known yet, with its player count: most players first, then by brand. */
    private static List<Map.Entry<@Nullable String, Integer>> counts(Collection<NetworkPlayer> players) {
        Map<@Nullable String, Integer> counts = new HashMap<>();
        for (NetworkPlayer player : players) counts.merge(player.brand(), 1, Integer::sum);
        List<Map.Entry<@Nullable String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<@Nullable String, Integer>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey(BRAND_ORDER)));
        return sorted;
    }

    private static String unknownLabel() {
        return template("findplayer-unknown", UNKNOWN);
    }

    private static String template(String key, String fallback) {
        return CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallback);
    }
}
