package ac.cult.cultac.command.commands;

import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckCategory;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.checks.info.CheckPolicy.Handling;
import ac.cult.cultac.checks.info.CheckTexts;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.render.HistoryComponentRenderer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How {@code /cult info} reads in chat. Every line is a {@code messages.yml} template with an English default; values
 * go into a template as plain text after it is parsed, so they never format or expand.
 */
final class CheckInfoLines {
    static final List<String> DEFAULT_CHECK = List.of(
            "&7======================",
            "%prefix% &b%check%%experimental% &8· &7%category%",
            "&f%summary%",
            "&7%details%",
            "&bOn this server: %status%",
            "&bWhat to do: &7%advice%",
            "%buttons%",
            "&7======================");
    static final int MAX_MATCHES = 10;

    /** The server's settings a view reads, taken once per command. */
    record View(
            @NotNull ConfigManager config,
            @NotNull CheckPolicy policy,
            @NotNull CheckTexts texts,
            boolean observeOnly,
            boolean experimentalChecks) {
        @NotNull
        String displayName(@NotNull CheckCatalog.Entry entry) {
            return config.getStringElse(entry.configName() + ".displayname", entry.name());
        }

        @NotNull
        Handling handling(@NotNull CheckCatalog.Entry entry) {
            return policy.status(entry).handling(!entry.experimental() || experimentalChecks);
        }

        /** The check named {@code query} by its name, config name, alternative name or display name, ignoring case. */
        @Nullable
        CheckCatalog.Entry find(@NotNull String query) {
            CheckCatalog.Entry entry = CheckCatalog.find(query);
            if (entry != null) return entry;
            for (CheckCatalog.Entry each : CheckCatalog.ENTRIES) {
                if (displayName(each).equalsIgnoreCase(query)) return each;
            }
            return null;
        }

        String template(String key, String fallback) {
            return config.getStringElse(key, fallback);
        }
    }

    private CheckInfoLines() {}

    static List<Component> check(Sender sender, View view, CheckCatalog.Entry entry) {
        CheckTexts.Text text = view.texts().text(entry.name());
        String category = view.texts().label(entry.category());
        Component buttons = Component.join(
                JoinConfiguration.separator(Component.space()),
                button(
                        sender,
                        view.template("info-button-group", "&8[&b%category% checks&8]"),
                        view.template("info-button-group-hover", "&7Show every %category% check"),
                        Map.of("category", category),
                        "info checks " + entry.category().id()),
                button(
                        sender,
                        view.template("info-button-all", "&8[&bAll checks&8]"),
                        view.template("info-button-all-hover", "&7Show every group of checks"),
                        Map.of(),
                        "info checks"));
        Map<String, Object> values = Map.of(
                "check",
                view.displayName(entry),
                "experimental",
                entry.experimental()
                        ? render(sender, view.template("info-experimental", " &8· &eExperimental"), Map.of())
                        : Component.empty(),
                "category",
                category,
                "summary",
                text == null ? "" : text.summary(),
                "details",
                text == null ? "" : text.details(),
                "status",
                render(sender, status(view, entry), Map.of()),
                "advice",
                render(sender, advice(view, entry), Map.of()),
                "buttons",
                buttons);
        List<String> template = view.config().getStringList("info-check");
        if (template.isEmpty()) template = DEFAULT_CHECK;
        List<Component> lines = new ArrayList<>();
        for (String line : template) {
            if (line != null) lines.add(render(sender, line, values));
        }
        return lines;
    }

    /** The groups of checks, each with how many it holds. */
    static List<Component> groups(Sender sender, View view) {
        List<Component> lines = new ArrayList<>();
        lines.add(render(
                sender,
                view.template("info-groups-header", "%prefix% &bAnticheat checks &7· click a group to see its checks"),
                Map.of()));
        for (CheckCategory category : CheckCategory.values()) {
            Map<String, Object> values = Map.of(
                    "category", view.texts().label(category),
                    "count", CheckCatalog.in(category).size());
            lines.add(button(
                    sender,
                    view.template("info-group", "&8» &b%category% &7(%count%)"),
                    view.template("info-group-hover", "&7Show the %category% checks"),
                    values,
                    "info checks " + category.id()));
        }
        return lines;
    }

    /** The checks of one group, coloured by what this server does with their flags. */
    static List<Component> group(Sender sender, View view, CheckCategory category) {
        List<CheckCatalog.Entry> entries = CheckCatalog.in(category);
        List<Component> lines = new ArrayList<>();
        lines.add(render(
                sender,
                view.template("info-group-header", "%prefix% &b%category% &7· %count% checks, click one to learn more"),
                Map.of("category", view.texts().label(category), "count", entries.size())));
        lines.add(entries(sender, view, entries));
        lines.add(render(
                sender,
                view.template(
                        "info-legend",
                        "&c■ &7runs commands &e■ &7alerts staff &7■ recorded only &8■ &7no alerts &f%symbol%"
                                + " &7experimental"),
                Map.of("symbol", symbol(sender, view))));
        return lines;
    }

    /** Why nothing matched {@code query}, with the checks whose names contain it. */
    static List<Component> unknown(Sender sender, View view, String query) {
        List<Component> lines = new ArrayList<>();
        lines.add(render(
                sender,
                view.template("info-unknown", "%prefix% &cNo check is called &f%query%&c."),
                Map.of("query", query)));
        List<CheckCatalog.Entry> matches = CheckCatalog.containing(query);
        if (matches.isEmpty()) {
            lines.add(render(
                    sender,
                    view.template("info-see-all", "&7Run &f/%command% info checks &7to see every check."),
                    Map.of()));
        } else {
            Component shown = entries(sender, view, matches.subList(0, Math.min(matches.size(), MAX_MATCHES)));
            lines.add(render(
                    sender, view.template("info-did-you-mean", "&7Did you mean: %checks%"), Map.of("checks", shown)));
        }
        return lines;
    }

    /** What this server does with the check's flags, from its punishments and observe-only setting. */
    static String status(View view, CheckCatalog.Entry entry) {
        CheckPolicy.Status status = view.policy().status(entry);
        Handling handling = status.handling(!entry.experimental() || view.experimentalChecks());
        switch (handling) {
            case OFF -> {
                return view.template(
                        "info-status-experimental-off", "&8Off, because experimental checks are turned off");
            }
            case NONE -> {
                return view.template("info-status-none", "&8No alerts or commands are set up for it here");
            }
            case RECORDED -> {
                return status.recorded()
                        ? view.template("info-status-recorded", "&7Recorded in the player's history, no staff alerts")
                        : view.template("info-status-webhook", "&7Sent to the staff webhook, no staff alerts in game");
            }
            default -> {}
        }
        List<String> parts = new ArrayList<>();
        for (CheckPolicy.Group group : status.groups()) {
            List<String> thresholds = new ArrayList<>();
            if (group.alertAt() != null) {
                thresholds.add(view.template("info-status-alerts", "&eStaff alerts from %count% flags")
                        .replace("%count%", Integer.toString(group.alertAt())));
            }
            for (CheckPolicy.Action action : group.actions()) {
                thresholds.add(view.template("info-status-action", "&c%action% &7runs at &f%count% &7flags")
                        .replace("%action%", action.command())
                        .replace("%count%", Integer.toString(action.threshold())));
            }
            if (thresholds.isEmpty()) continue;
            parts.add(String.join("&7, ", thresholds) + " "
                    + view.template("info-status-window", "&8(flags from related checks add up over %window%)")
                            .replace(
                                    "%window%",
                                    HistoryComponentRenderer.formatDuration(
                                            TimeUnit.SECONDS.toMillis(group.expireSeconds()))));
        }
        if (handling == Handling.ALERTS) {
            parts.add(view.template("info-status-no-punishment", "&7no automatic punishment"));
        }
        String line = String.join(" &8· ", parts);
        if (handling == Handling.AUTOMATIC && view.observeOnly()) {
            line += " " + view.template("info-status-observe-only", "&8(observe-only mode: nothing is punished now)");
        }
        return line;
    }

    /** What a moderator does about a flag of this check here. */
    static String advice(View view, CheckCatalog.Entry entry) {
        Handling handling = view.handling(entry);
        if (handling == Handling.OFF) {
            return view.template("info-advice-off", "Nothing. Experimental checks are turned off here.");
        }
        if (handling == Handling.NONE) {
            return view.template("info-advice-none", "Nothing. This server does not alert staff about it.");
        }
        if (entry.experimental()) {
            return view.template(
                    "info-advice-experimental",
                    "This check is still being tested and can flag players who aren't cheating. Never punish for it"
                            + " alone: look for other checks flagging too, or spectate the player.");
        }
        return switch (handling) {
            case AUTOMATIC ->
                view.observeOnly()
                        ? view.template(
                                "info-advice-observe-only",
                                "Nothing is punished automatically right now. If the player keeps flagging, spectate"
                                        + " them or look at their history.")
                        : view.template(
                                "info-advice-automatic",
                                "The server runs the commands above by itself. If the player keeps flagging, spectate"
                                        + " them or look at their history.");
            case ALERTS ->
                view.template(
                        "info-advice-alerts",
                        "One flag alone is not proof. Many flags, or flags from other checks too, are worth spectating"
                                + " and reporting.");
            default ->
                view.template(
                        "info-advice-recorded",
                        "Nothing right away. It helps when reviewing the player's flags later.");
        };
    }

    private static Component entries(Sender sender, View view, List<CheckCatalog.Entry> entries) {
        Component symbol = symbol(sender, view);
        List<Component> names = new ArrayList<>();
        for (CheckCatalog.Entry entry : entries) {
            String template = switch (view.handling(entry)) {
                case AUTOMATIC -> view.template("info-entry-automatic", "&c%check%");
                case ALERTS -> view.template("info-entry-alerts", "&e%check%");
                case RECORDED -> view.template("info-entry-recorded", "&7%check%");
                case NONE, OFF -> view.template("info-entry-none", "&8%check%");
            };
            CheckTexts.Text text = view.texts().text(entry.name());
            Map<String, Object> values = Map.of(
                    "check", view.displayName(entry),
                    "experimental", entry.experimental() ? symbol : Component.empty(),
                    "summary", text == null ? "" : text.summary());
            names.add(button(
                    sender,
                    template + "%experimental%",
                    view.template(
                            "info-entry-hover",
                            "&b%check%%experimental%<newline>&f%summary%<newline>&7Click to learn more"),
                    values,
                    "info check " + entry.name()));
        }
        return Component.join(JoinConfiguration.separator(Component.text(", ")), names);
    }

    private static Component symbol(Sender sender, View view) {
        return render(sender, view.template("experimental-symbol", "*"), Map.of());
    }

    private static Component button(
            Sender sender, String template, String hover, Map<String, Object> values, String arguments) {
        return render(sender, template, values)
                .hoverEvent(HoverEvent.showText(render(sender, hover, values)))
                .clickEvent(ClickEvent.runCommand(CloudCommandService.command(arguments)));
    }

    /**
     * {@code template} with each {@code %key%} replaced by its value: a component as it is, anything else as plain
     * text.
     */
    private static Component render(Sender sender, String template, Map<String, ?> values) {
        String raw = template;
        TagResolver.Builder resolver = TagResolver.builder();
        for (Map.Entry<String, ?> value : values.entrySet()) {
            String key = value.getKey();
            raw = raw.replace("%" + key + "%", "<" + key + ">");
            resolver.resolver(
                    value.getValue() instanceof ComponentLike component
                            ? Placeholder.component(key, component)
                            : Placeholder.unparsed(key, String.valueOf(value.getValue())));
        }
        return MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, raw), resolver.build());
    }
}
