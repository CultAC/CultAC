package ac.cult.cultac.command.commands;

import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckCategory;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.checks.info.CheckPolicy.Handling;
import ac.cult.cultac.checks.info.CheckTexts;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

final class CheckInfoLines {
    private static final List<String> DEFAULT_CHECK = List.of(
            "&7======================",
            "%prefix% &b%check%%experimental%",
            "&f%summary%",
            "&7%details%",
            "&bPunishments: &f%punishments%",
            "%buttons%",
            "&7======================");
    private static final int MAX_MATCHES = 10;

    record View(ConfigManager config, CheckPolicy policy, CheckTexts texts, boolean experimentalChecks) {
        String displayName(CheckCatalog.Entry entry) {
            return config.getStringElse(entry.configName() + ".displayname", entry.name());
        }

        Handling handling(CheckCatalog.Entry entry) {
            return policy.status(entry).handling(running(entry));
        }

        boolean running(CheckCatalog.Entry entry) {
            return !entry.experimental() || experimentalChecks;
        }

        @Nullable
        CheckCatalog.Entry find(String query) {
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
                        view.template("info-button-group-hover", "&7Click to see the %category% checks"),
                        Map.of("category", category),
                        "info checks " + entry.category().id()),
                button(
                        sender,
                        view.template("info-button-all", "&8[&bAll checks&8]"),
                        view.template("info-button-all-hover", "&7Click to see all check groups"),
                        Map.of(),
                        "info checks"));
        Component experimental = entry.experimental()
                ? render(sender, view.template("info-experimental", " &7(experimental)"))
                : Component.empty();
        Map<String, Object> values = Map.of(
                "check",
                view.displayName(entry),
                "experimental",
                experimental,
                "category",
                category,
                "summary",
                text == null ? "" : text.summary(),
                "details",
                text == null ? "" : text.details(),
                "punishments",
                render(sender, punishments(view, entry)),
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

    static List<Component> overview(Sender sender, View view) {
        List<Component> lines = new ArrayList<>();
        lines.add(render(
                sender,
                view.template("info-groups-header", "%prefix% &bCheck groups &7- click one to see its checks")));
        for (CheckCategory category : CheckCategory.values()) {
            Map<String, Object> values = Map.of(
                    "category", view.texts().label(category),
                    "count", CheckCatalog.byCategory(category).size());
            lines.add(button(
                    sender,
                    view.template("info-group", "&8» &f%category% &7(%count%)"),
                    view.template("info-group-hover", "&7Click to see the %category% checks"),
                    values,
                    "info checks " + category.id()));
        }
        return lines;
    }

    static List<Component> group(Sender sender, View view, CheckCategory category) {
        List<CheckCatalog.Entry> entries = CheckCatalog.byCategory(category);
        List<Component> lines = new ArrayList<>();
        lines.add(render(
                sender,
                view.template("info-group-header", "%prefix% &b%category% checks &7(%count%) - click one for info"),
                Map.of("category", view.texts().label(category), "count", entries.size())));
        Map<Handling, List<CheckCatalog.Entry>> byHandling = byHandling(view, entries);
        for (Map.Entry<Handling, List<CheckCatalog.Entry>> each : byHandling.entrySet()) {
            String template = switch (each.getKey()) {
                case AUTOMATIC -> view.template("info-handling-automatic", "&bAuto-punish: &f%checks%");
                case ALERTS -> view.template("info-handling-alerts", "&bAlerts only: &f%checks%");
                case RECORDED -> view.template("info-handling-recorded", "&bLogged only: &f%checks%");
                case NONE -> view.template("info-handling-none", "&bNo alerts or punishment: &f%checks%");
                case OFF -> view.template("info-handling-off", "&bOff: &f%checks%");
            };
            lines.add(render(sender, template, Map.of("checks", checkLinks(sender, view, each.getValue(), true))));
        }
        if (entries.stream().anyMatch(CheckCatalog.Entry::experimental)) {
            lines.add(render(
                    sender,
                    view.template("info-experimental-note", "&7%symbol% Experimental (still in testing)"),
                    Map.of("symbol", experimentalSymbol(sender, view))));
        }
        return lines;
    }

    static Map<Handling, List<CheckCatalog.Entry>> byHandling(View view, List<CheckCatalog.Entry> entries) {
        Map<Handling, List<CheckCatalog.Entry>> byHandling = new EnumMap<>(Handling.class);
        for (CheckCatalog.Entry entry : entries) {
            byHandling
                    .computeIfAbsent(view.handling(entry), ignored -> new ArrayList<>())
                    .add(entry);
        }
        return byHandling;
    }

    static List<Component> unknown(Sender sender, View view, String query) {
        List<Component> lines = new ArrayList<>();
        lines.add(render(
                sender, view.template("info-unknown", "%prefix% &cUnknown check: &f%query%"), Map.of("query", query)));
        List<CheckCatalog.Entry> matches = CheckCatalog.containing(query);
        if (matches.isEmpty()) {
            lines.add(render(
                    sender, view.template("info-see-all", "&7Try &f/%command% info checks &7for the full list.")));
        } else {
            Component shown =
                    checkLinks(sender, view, matches.subList(0, Math.min(matches.size(), MAX_MATCHES)), false);
            lines.add(render(
                    sender, view.template("info-did-you-mean", "&7Did you mean: %checks%"), Map.of("checks", shown)));
        }
        return lines;
    }

    static String punishments(View view, CheckCatalog.Entry entry) {
        CheckPolicy.Status status = view.policy().status(entry);
        Handling handling = status.handling(view.running(entry));
        if (handling == Handling.OFF) {
            return view.template("info-punishments-off", "None, experimental checks are off");
        }
        if (handling == Handling.NONE) {
            return view.template("info-punishments-none", "None");
        }
        if (handling == Handling.RECORDED) {
            return status.recorded()
                    ? view.template("info-punishments-logged", "Logged to their history only")
                    : view.template("info-punishments-webhook", "Sent to Discord only");
        }
        Map<Integer, List<String>> byFlags = new TreeMap<>();
        if (status.alertAt() != null) {
            byFlags.computeIfAbsent(status.alertAt(), flags -> new ArrayList<>())
                    .add(view.template("info-punishment-alert", "alert"));
        }
        List<CheckPolicy.Action> actions = new ArrayList<>(status.actions());
        actions.sort(Comparator.comparingInt(CheckPolicy.Action::threshold));
        Set<String> listed = new HashSet<>();
        for (CheckPolicy.Action action : actions) {
            String name = commandName(view, action.command());
            if (listed.add(name))
                byFlags.computeIfAbsent(action.threshold(), flags -> new ArrayList<>())
                        .add(name);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> names : byFlags.entrySet()) {
            String template = names.getKey() == 1
                    ? view.template("info-punishment-first", "%action% on the first flag")
                    : view.template("info-punishment", "%action% at %count% flags");
            String action = String.join(view.template("info-punishment-and", " and "), names.getValue());
            parts.add(template.replace("%action%", action).replace("%count%", Integer.toString(names.getKey())));
        }
        return LabelLines.capitalized(String.join("&7, &f", parts));
    }

    private static String commandName(View view, String command) {
        if (command.contains("ban")) return view.template("info-punishment-ban", "ban");
        if (command.contains("kick")) return view.template("info-punishment-kick", "kick");
        return command;
    }

    private static Component checkLinks(
            Sender sender, View view, List<CheckCatalog.Entry> entries, boolean markExperimental) {
        Component symbol = experimentalSymbol(sender, view);
        List<Component> names = new ArrayList<>();
        for (CheckCatalog.Entry entry : entries) {
            CheckTexts.Text text = view.texts().text(entry.name());
            Map<String, Object> values = Map.of(
                    "check", view.displayName(entry),
                    "experimental", markExperimental && entry.experimental() ? symbol : Component.empty(),
                    "summary", text == null ? "" : text.summary());
            names.add(button(
                    sender,
                    view.template("info-entry", "&f%check%%experimental%"),
                    view.template(
                            "info-entry-hover",
                            "&b%check%%experimental%<newline>&f%summary%<newline>&7Click for more info"),
                    values,
                    "info check " + entry.name()));
        }
        Component separator = render(sender, view.template("info-entry-separator", "&7, "));
        return Component.join(JoinConfiguration.separator(separator), names);
    }

    private static Component experimentalSymbol(Sender sender, View view) {
        return render(sender, view.template("experimental-symbol", "*"));
    }

    private static Component button(
            Sender sender, String template, String hover, Map<String, Object> values, String arguments) {
        return render(sender, template, values)
                .hoverEvent(HoverEvent.showText(render(sender, hover, values)))
                .clickEvent(ClickEvent.runCommand(CloudCommandService.command(arguments)));
    }

    private static Component render(Sender sender, String template) {
        return render(sender, template, Map.of());
    }

    // Values go in unparsed: a typed query or a display name never applies tags, colors or placeholders.
    private static Component render(Sender sender, String template, Map<String, ?> values) {
        String raw = template;
        TagResolver.Builder resolver = TagResolver.builder();
        for (Map.Entry<String, ?> placeholder : values.entrySet()) {
            String key = placeholder.getKey();
            raw = raw.replace("%" + key + "%", "<" + key + ">");
            resolver.resolver(
                    placeholder.getValue() instanceof ComponentLike component
                            ? Placeholder.component(key, component)
                            : Placeholder.unparsed(key, String.valueOf(placeholder.getValue())));
        }
        return MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, raw), resolver.build());
    }
}
