package ac.cult.cultac.command.commands;

import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.checks.info.CheckCategory;
import ac.cult.cultac.checks.info.CheckPolicy;
import ac.cult.cultac.checks.info.CheckPolicy.Handling;
import ac.cult.cultac.checks.info.CheckTexts;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.manager.config.EnglishDefaults;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final int MAX_MATCHES = 10;

    record View(ConfigManager config, CheckPolicy policy, CheckTexts texts, boolean experimentalChecks) {
        /** The name version {@code config.yml} is written in, which the shown names follow. */
        int nameVersion() {
            return CheckNames.version(config, CheckNames.CONFIG_KEY);
        }

        String displayName(CheckCatalog.Entry entry) {
            int nameVersion = nameVersion();
            return config.getStringElse(entry.configName(nameVersion) + ".displayname", entry.name(nameVersion));
        }

        Handling handling(CheckCatalog.Entry entry) {
            return policy.status(entry).handling(running(entry));
        }

        boolean running(CheckCatalog.Entry entry) {
            return !entry.experimental() || experimentalChecks;
        }

        /** What {@code /cult info check} finds {@code entry} by under the name version of {@code config.yml}. */
        String query(CheckCatalog.Entry entry) {
            int nameVersion = nameVersion();
            return CheckCatalog.query(entry.name(nameVersion), entry.name(), nameVersion);
        }

        @Nullable
        CheckTexts.Text text(CheckCatalog.Entry entry) {
            return texts.text(entry, nameVersion());
        }

        /** How many checks {@code entries} hold; checks the name version merged into one count once. */
        int count(List<CheckCatalog.Entry> entries) {
            Set<String> queries = new HashSet<>();
            for (CheckCatalog.Entry entry : entries) queries.add(query(entry));
            return queries.size();
        }

        @Nullable
        CheckCatalog.Entry find(String query) {
            CheckCatalog.Entry entry = CheckCatalog.find(query, nameVersion());
            if (entry != null) return entry;
            for (CheckCatalog.Entry each : CheckCatalog.ENTRIES) {
                if (displayName(each).equalsIgnoreCase(query)) return each;
            }
            return null;
        }

        /** The server's {@code key} template, English from the shipped {@code messages/en.yml} where it has none. */
        String template(String key) {
            return config.getStringElse(key, EnglishDefaults.string("messages", key));
        }
    }

    private CheckInfoLines() {}

    static List<Component> check(Sender sender, View view, CheckCatalog.Entry entry, @Nullable String query) {
        CheckTexts.Text text = view.text(entry);
        String category = view.texts().label(entry.category());
        Component buttons = Component.join(
                JoinConfiguration.separator(Component.space()),
                button(
                        sender,
                        view.template("info-button-group"),
                        view.template("info-button-group-hover"),
                        Map.of("category", category),
                        "info checks " + entry.category().id()),
                button(
                        sender,
                        view.template("info-button-all"),
                        view.template("info-button-all-hover"),
                        Map.of(),
                        "info checks"));
        Component experimental = entry.experimental()
                ? render(sender, view.template("info-experimental"))
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
        if (template.isEmpty()) template = EnglishDefaults.stringList("messages", "info-check");
        List<Component> names = names(sender, view, entry, query);
        List<Component> lines = new ArrayList<>();
        for (String line : template) {
            if (line == null) continue;
            lines.add(render(sender, line, values));
            // The name lines follow the template line that names the check.
            if (line.contains("%check%")) {
                lines.addAll(names);
                names = List.of();
            }
        }
        return lines;
    }

    /**
     * The names {@code entry} has in the other name versions, and the checks its name, or the {@code query} it was
     * found by where that names no check in the name version of {@code config.yml}, names in other versions.
     */
    static List<Component> names(Sender sender, View view, CheckCatalog.Entry entry, @Nullable String query) {
        int nameVersion = view.nameVersion();
        String name = entry.name(nameVersion);
        Map<String, List<Integer>> earlier = new LinkedHashMap<>();
        Map<String, List<Integer>> later = new LinkedHashMap<>();
        for (CheckCatalog.Entry named : CheckCatalog.ENTRIES) {
            if (!named.name(nameVersion).equals(name)) continue;
            for (int version = CheckNames.FIRST; version <= CheckNames.LATEST; version++) {
                String other = named.name(version);
                if (other.equalsIgnoreCase(name)) continue;
                List<Integer> versions =
                        (version < nameVersion ? earlier : later).computeIfAbsent(other, ignored -> new ArrayList<>());
                if (!versions.contains(version)) versions.add(version);
            }
        }
        List<Component> lines = new ArrayList<>();
        if (!earlier.isEmpty()) {
            lines.add(render(
                    sender, view.template("info-former-names"), Map.of("names", byVersions(sender, view, earlier))));
        }
        if (!later.isEmpty()) {
            lines.add(render(
                    sender, view.template("info-later-names"), Map.of("names", byVersions(sender, view, later))));
        }
        String meant = query != null
                        && CheckCatalog.withName(query, nameVersion).isEmpty()
                        && !view.displayName(entry).equalsIgnoreCase(query)
                ? query
                : name;
        List<CheckCatalog.Entry> here = CheckCatalog.withName(meant, nameVersion);
        Map<List<CheckCatalog.Entry>, List<Integer>> elsewhere = new LinkedHashMap<>();
        for (int version = CheckNames.FIRST; version <= CheckNames.LATEST; version++) {
            List<CheckCatalog.Entry> there = CheckCatalog.withName(meant, version);
            if (!there.isEmpty() && !there.equals(here)) {
                elsewhere.computeIfAbsent(there, ignored -> new ArrayList<>()).add(version);
            }
        }
        for (Map.Entry<List<CheckCatalog.Entry>, List<Integer>> each : elsewhere.entrySet()) {
            Set<String> checks = new LinkedHashSet<>();
            for (CheckCatalog.Entry named : each.getKey()) checks.add(named.name(nameVersion));
            lines.add(render(
                    sender,
                    view.template("info-name-elsewhere"),
                    Map.of(
                            "name", meant,
                            "versions", versions(each.getValue()),
                            "checks", joined(sender, view, checks))));
        }
        return lines;
    }

    private static Component byVersions(Sender sender, View view, Map<String, List<Integer>> names) {
        Map<List<Integer>, List<String>> byVersions = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> name : names.entrySet()) {
            byVersions.computeIfAbsent(name.getValue(), ignored -> new ArrayList<>()).add(name.getKey());
        }
        List<Component> parts = new ArrayList<>();
        for (Map.Entry<List<Integer>, List<String>> each : byVersions.entrySet()) {
            parts.add(render(
                    sender,
                    view.template("info-name-versions"),
                    Map.of("names", joined(sender, view, each.getValue()), "versions", versions(each.getKey()))));
        }
        return Component.join(JoinConfiguration.separator(separator(sender, view)), parts);
    }

    private static Component joined(Sender sender, View view, Collection<String> names) {
        List<Component> parts = new ArrayList<>();
        for (String name : names) parts.add(Component.text(name));
        return Component.join(JoinConfiguration.separator(separator(sender, view)), parts);
    }

    /** Ascending {@code versions} as runs: {@code 1-2, 4}. */
    private static String versions(List<Integer> versions) {
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < versions.size(); ) {
            int first = versions.get(i);
            int last = first;
            while (++i < versions.size() && versions.get(i) == last + 1) last = versions.get(i);
            runs.add(first == last ? Integer.toString(first) : first + "-" + last);
        }
        return String.join(", ", runs);
    }

    private static Component separator(Sender sender, View view) {
        return render(sender, view.template("info-entry-separator"));
    }

    static List<Component> overview(Sender sender, View view) {
        List<Component> lines = new ArrayList<>();
        lines.add(render(
                sender,
                view.template("info-groups-header")));
        for (CheckCategory category : CheckCategory.values()) {
            Map<String, Object> values = Map.of(
                    "category", view.texts().label(category),
                    "count", view.count(CheckCatalog.byCategory(category)));
            lines.add(button(
                    sender,
                    view.template("info-group"),
                    view.template("info-group-hover"),
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
                view.template("info-group-header"),
                Map.of("category", view.texts().label(category), "count", view.count(entries))));
        Map<Handling, List<CheckCatalog.Entry>> byHandling = byHandling(view, entries);
        // A check the name version merged from several is listed once, under the first handling of any of them.
        Set<String> listed = new HashSet<>();
        for (Map.Entry<Handling, List<CheckCatalog.Entry>> each : byHandling.entrySet()) {
            List<CheckCatalog.Entry> unlisted = new ArrayList<>();
            for (CheckCatalog.Entry entry : each.getValue()) {
                if (listed.add(view.query(entry))) unlisted.add(entry);
            }
            if (unlisted.isEmpty()) continue;
            String template = switch (each.getKey()) {
                case AUTOMATIC -> view.template("info-handling-automatic");
                case ALERTS -> view.template("info-handling-alerts");
                case RECORDED -> view.template("info-handling-recorded");
                case NONE -> view.template("info-handling-none");
                case OFF -> view.template("info-handling-off");
            };
            lines.add(render(sender, template, Map.of("checks", checkLinks(sender, view, unlisted, true))));
        }
        if (entries.stream().anyMatch(CheckCatalog.Entry::experimental)) {
            lines.add(render(
                    sender,
                    view.template("info-experimental-note"),
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
                sender, view.template("info-unknown"), Map.of("query", query)));
        List<CheckCatalog.Entry> matches = CheckCatalog.containing(query, view.nameVersion());
        if (matches.isEmpty()) {
            lines.add(render(
                    sender, view.template("info-see-all")));
        } else {
            Component shown =
                    checkLinks(sender, view, matches.subList(0, Math.min(matches.size(), MAX_MATCHES)), false);
            lines.add(render(
                    sender, view.template("info-did-you-mean"), Map.of("checks", shown)));
        }
        return lines;
    }

    static String punishments(View view, CheckCatalog.Entry entry) {
        CheckPolicy.Status status = view.policy().status(entry);
        Handling handling = status.handling(view.running(entry));
        if (handling == Handling.OFF) {
            return view.template("info-punishments-off");
        }
        if (handling == Handling.NONE) {
            return view.template("info-punishments-none");
        }
        if (handling == Handling.RECORDED) {
            return status.recorded()
                    ? view.template("info-punishments-logged")
                    : view.template("info-punishments-webhook");
        }
        Map<Integer, List<String>> byFlags = new TreeMap<>();
        if (status.alertAt() != null) {
            byFlags.computeIfAbsent(status.alertAt(), flags -> new ArrayList<>())
                    .add(view.template("info-punishment-alert"));
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
                    ? view.template("info-punishment-first")
                    : view.template("info-punishment");
            String action = String.join(view.template("info-punishment-and"), names.getValue());
            parts.add(template.replace("%action%", action).replace("%count%", Integer.toString(names.getKey())));
        }
        return LabelLines.capitalized(String.join("&7, &f", parts));
    }

    private static String commandName(View view, String command) {
        if (command.contains("ban")) return view.template("info-punishment-ban");
        if (command.contains("kick")) return view.template("info-punishment-kick");
        return command;
    }

    private static Component checkLinks(
            Sender sender, View view, List<CheckCatalog.Entry> entries, boolean markExperimental) {
        Component symbol = experimentalSymbol(sender, view);
        List<Component> names = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (CheckCatalog.Entry entry : entries) {
            String query = view.query(entry);
            if (!listed.add(query)) continue;
            CheckTexts.Text text = view.text(entry);
            Map<String, Object> values = Map.of(
                    "check", view.displayName(entry),
                    "experimental", markExperimental && entry.experimental() ? symbol : Component.empty(),
                    "summary", text == null ? "" : text.summary());
            names.add(button(
                    sender,
                    view.template("info-entry"),
                    view.template("info-entry-hover"),
                    values,
                    "info check " + query));
        }
        Component separator = render(sender, view.template("info-entry-separator"));
        return Component.join(JoinConfiguration.separator(separator), names);
    }

    private static Component experimentalSymbol(Sender sender, View view) {
        return render(sender, view.template("experimental-symbol"));
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
    static Component render(Sender sender, String template, Map<String, ?> values) {
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
