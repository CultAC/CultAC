package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /cult findplayer} counts the client brands of the players online across the network, most players first;
 * {@code /cult findplayer brand=<text>} lists the online players whose brand contains the text, ignoring case, by
 * name. Without network routing both cover this instance's players only. A brand is only ever shown as plain text.
 */
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
                .literal("findplayer", Description.of("Find online players by client brand"))
                .permission(PERMISSION)
                .optional(
                        "filter",
                        StringParser.greedyStringParser(),
                        SuggestionProvider.blockingStrings((context, input) ->
                                suggestions(NetworkRouting.current().roster(), input.remainingInput())))
                .handler(context -> {
                    Sender sender = context.sender();
                    List<Component> lines = lines(
                            sender,
                            NetworkRouting.current().roster(),
                            context.<String>optional("filter").orElse(""));
                    for (Component line : lines) sender.sendMessage(line);
                }));
    }

    /** What {@code filter}, the text after {@code findplayer}, shows of {@code players}. */
    static List<Component> lines(Sender sender, Collection<NetworkPlayer> players, String filter) {
        String typed = filter.strip();
        if (typed.isEmpty()) return summary(sender, players);
        if (!typed.regionMatches(true, 0, FILTER, 0, FILTER.length()))
            return List.of(CheckInfoLines.render(
                    sender,
                    template(
                            "findplayer-usage",
                            "%prefix% &cTo search, type &f/%command% findplayer brand=lunar &cwith any part of a brand"
                                    + " name, or leave it out to see every brand."),
                    Map.of()));
        String query = typed.substring(FILTER.length()).strip();
        return query.isEmpty() ? summary(sender, players) : matching(sender, players, query);
    }

    /** {@code FILTER} and each brand online, in the summary's order, that start with {@code typed}. */
    static List<String> suggestions(Collection<NetworkPlayer> players, String typed) {
        List<String> suggestions = new ArrayList<>();
        for (Map.Entry<@Nullable String, Integer> brand : counts(players)) {
            String suggestion = FILTER + (brand.getKey() == null ? UNKNOWN : brand.getKey());
            if (suggestion.regionMatches(true, 0, typed, 0, typed.length()) && !suggestions.contains(suggestion))
                suggestions.add(suggestion);
        }
        return suggestions;
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
                    .clickEvent(ClickEvent.runCommand(CloudCommandService.command(
                            "findplayer " + FILTER + (brand.getKey() == null ? UNKNOWN : brand.getKey())))));
        }
        return lines;
    }

    private static List<Component> matching(Sender sender, Collection<NetworkPlayer> players, String query) {
        boolean unknownQuery = query.equalsIgnoreCase(UNKNOWN);
        String needle = query.toLowerCase(Locale.ROOT);
        List<NetworkPlayer> matches = players.stream()
                .filter(player -> player.brand() == null
                        ? unknownQuery
                        : player.brand().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(NetworkPlayer::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(NetworkPlayer::name))
                .toList();
        if (matches.isEmpty())
            return List.of(CheckInfoLines.render(
                    sender,
                    template("findplayer-none", "%prefix% &7Nobody online uses a brand containing &f%query%&7."),
                    Map.of("query", query)));
        List<Component> lines = new ArrayList<>();
        lines.add(CheckInfoLines.render(
                sender,
                template(
                        "findplayer-results",
                        "%prefix% &bPlayers whose brand contains &f%query% &8· &f%count% &7online"),
                Map.of("query", query, "count", matches.size())));
        String unknown = unknownLabel();
        for (NetworkPlayer player : matches.subList(0, Math.min(matches.size(), MAX_LISTED))) {
            Map<String, Object> values = Map.of(
                    "player", player.name(),
                    "server", player.server() == null ? player.instance() : player.server(),
                    "brand", player.brand() == null ? unknown : player.brand());
            lines.add(CheckInfoLines.render(
                            sender,
                            template("findplayer-player", "&8» &f%player% &7on &f%server% &8· &7%brand%"),
                            values)
                    .hoverEvent(HoverEvent.showText(CheckInfoLines.render(
                            sender, template("findplayer-player-hover", "&7Show %player%'s profile"), values)))
                    .clickEvent(ClickEvent.runCommand(CloudCommandService.command("profile " + player.name()))));
        }
        if (matches.size() > MAX_LISTED)
            lines.add(CheckInfoLines.render(
                    sender,
                    template(
                            "findplayer-more", "&7...and %count% more. Type more of the brand name to narrow it down."),
                    Map.of("count", matches.size() - MAX_LISTED)));
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
