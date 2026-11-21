package ac.cult.cultac.checks.info;

import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.manager.PunishmentRules;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Finds check names an operator wrote as another name version has them than the version the file is stamped with. */
public final class CheckNameAudit {
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
        Set<String> seen = new HashSet<>();
        for (PunishmentRules.Group group : PunishmentRules.parse(punishments).groups()) {
            for (String selector : group.selectors()) {
                String match = selector.startsWith("!") ? selector.substring(1) : selector;
                if (match.isEmpty() || !seen.add(match) || named(match, version) != null) continue;
                for (int other = CheckNames.FIRST; other <= CheckNames.LATEST; other++) {
                    CheckCatalog.Entry entry = other == version ? null : named(match, other);
                    if (entry == null) continue;
                    warnings.add("punishments.yml: \"" + match
                            + "\" selects no check. It names a check as name version " + other + " does, and "
                            + CheckNames.PUNISHMENTS_KEY + " is " + version + ", where that check is "
                            + entry.name(version));
                    break;
                }
            }
        }
    }

    /** The first check a lower-cased selector matches by the name {@code version} gives it, as punishments match. */
    private static @Nullable CheckCatalog.Entry named(String match, int version) {
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            if (entry.name(version).toLowerCase(Locale.ROOT).contains(match)) return entry;
            String altName = entry.altName();
            if (altName != null && altName.toLowerCase(Locale.ROOT).contains(match)) return entry;
        }
        return null;
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
                warnings.add("config.yml: the settings under " + key
                        + " are not read. They name a check as name version " + other + " does, and "
                        + CheckNames.CONFIG_KEY + " is " + version + ", where that check is "
                        + entry.configName(version));
            }
        }
    }
}
