package ac.cult.cultac.checks.info;

import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.manager.PunishmentRules;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Finds check names an operator wrote as another name version has them than the version the file is stamped with. */
public final class CheckNameAudit {
    /**
     * The first name version that moves checks out of the category a selector names. A warning about a selector of
     * it or a later version, or in a file of one, names every check the selector selects, and a punishments file of
     * it or a later version is compared with the version before; between older versions a warning names the first.
     */
    private static final int CATEGORY_MOVE = 3;

    private CheckNameAudit() {}

    /** One warning for each such name: it selects or sets nothing, which for a punishment group turns a check off. */
    public static List<String> warnings(ConfigManager config) {
        List<String> warnings = new ArrayList<>();
        selectors(config, warnings);
        settings(config, warnings);
        return warnings;
    }

    private static void selectors(ConfigManager config, List<String> warnings) {
        int version = CheckNames.version(config, CheckNames.PUNISHMENTS_KEY);
        List<String> punishments = config.getStringListElse("Punishments", new ArrayList<>());
        PunishmentRules rules = PunishmentRules.parse(punishments);
        Set<String> seen = new HashSet<>();
        for (PunishmentRules.Group group : rules.groups()) {
            for (String selector : group.selectors()) {
                String match = selector.startsWith("!") ? selector.substring(1) : selector;
                if (match.isEmpty() || !seen.add(match) || named(match, version) != null) continue;
                for (int other = CheckNames.FIRST; other <= CheckNames.LATEST; other++) {
                    CheckCatalog.Entry entry = other == version ? null : named(match, other);
                    if (entry == null) continue;
                    Set<String> names = new LinkedHashSet<>();
                    if (Math.max(version, other) < CATEGORY_MOVE) {
                        names.add(entry.name(version));
                    } else {
                        for (CheckCatalog.Entry each : CheckCatalog.ENTRIES) {
                            if (matches(match, each, other)) names.add(each.name(version));
                        }
                    }
                    warnings.add("punishments.yml: \"" + match
                            + "\" selects no check. It names a check as name version " + other + " does, and "
                            + CheckNames.PUNISHMENTS_KEY + " is " + version + ", where " + those(names));
                    break;
                }
            }
        }
        if (version >= CATEGORY_MOVE) unselected(rules, version, warnings);
    }

    /**
     * Checks no group selects or excludes that a selector selected under the names of the version before, which turns
     * them off: the selector still selects others, so it raises no other warning.
     */
    private static void unselected(PunishmentRules rules, int version, List<String> warnings) {
        int before = version - 1;
        Map<String, Set<String>> bySelector = new LinkedHashMap<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            if (holds(rules.unreadGroupSelectors(), entry, version)) continue;
            if (rules.groups().stream().anyMatch(group -> holds(group.selectors(), entry, version))) continue;
            if (rules.groups().stream().anyMatch(group -> excludes(group.selectors(), entry, version))) continue;
            for (PunishmentRules.Group group : rules.groups()) {
                if (!holds(group.selectors(), entry, before)) continue;
                for (String selector : group.selectors()) {
                    if (selector.startsWith("!") || !matches(selector, entry, before)) continue;
                    if (named(selector, version) == null) continue;
                    bySelector
                            .computeIfAbsent(selector, ignored -> new LinkedHashSet<>())
                            .add(entry.name(version));
                }
            }
        }
        bySelector.forEach((selector, names) -> {
            String them = names.size() == 1 ? "it" : "them";
            warnings.add("punishments.yml: \"" + selector + "\" selects " + String.join(", ", names)
                    + " as name version " + before + " names " + them + ", and " + CheckNames.PUNISHMENTS_KEY + " is "
                    + version + ", where no group selects " + them);
        });
    }

    private static boolean excludes(List<String> selectors, CheckCatalog.Entry entry, int version) {
        for (String selector : selectors) {
            if (selector.startsWith("!") && matches(selector.substring(1), entry, version)) return true;
        }
        return false;
    }

    private static boolean holds(List<String> selectors, CheckCatalog.Entry entry, int version) {
        String altName = entry.altName();
        return PunishmentRules.holds(
                selectors,
                entry.name(version).toLowerCase(Locale.ROOT),
                altName == null ? null : altName.toLowerCase(Locale.ROOT));
    }

    /** The checks a name of another version stands for, which a merge in that version makes several. */
    private static String those(Set<String> names) {
        return names.size() == 1
                ? "that check is " + names.iterator().next()
                : "those checks are " + String.join(", ", names);
    }

    /** The first check a lower-cased selector matches by the name {@code version} gives it, as punishments match. */
    private static @Nullable CheckCatalog.Entry named(String match, int version) {
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            if (matches(match, entry, version)) return entry;
        }
        return null;
    }

    /** Whether a lower-cased selector matches {@code entry} by the name {@code version} gives it. */
    private static boolean matches(String match, CheckCatalog.Entry entry, int version) {
        if (entry.name(version).toLowerCase(Locale.ROOT).contains(match)) return true;
        String altName = entry.altName();
        return altName != null && altName.toLowerCase(Locale.ROOT).contains(match);
    }

    private static void settings(ConfigManager config, List<String> warnings) {
        int version = CheckNames.version(config, CheckNames.CONFIG_KEY);
        Set<String> read = new HashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) read.add(entry.configName(version));
        Set<String> seen = new HashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            for (int other = CheckNames.FIRST; other <= CheckNames.LATEST; other++) {
                String key = entry.configName(other);
                if (read.contains(key) || !seen.add(key) || CheckNames.written(config, key) == null) continue;
                Set<String> names = new LinkedHashSet<>();
                for (CheckCatalog.Entry each : CheckCatalog.ENTRIES) {
                    if (each.configName(other).equals(key)) names.add(each.configName(version));
                }
                warnings.add("config.yml: the settings under " + key
                        + " are not read. They name a check as name version " + other + " does, and "
                        + CheckNames.CONFIG_KEY + " is " + version + ", where " + those(names));
            }
        }
    }
}
