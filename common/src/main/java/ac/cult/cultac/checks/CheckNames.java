package ac.cult.cultac.checks;

import ac.cult.cultac.manager.config.ConfigManagerFileImpl;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The names one check has had, by name version.
 *
 * <p>Operators write check names into {@code config.yml}, their permission nodes and {@code punishments.yml}. Each of
 * the two files carries the name version it is written in and is read against the names of that version alone, so a
 * rename changes nothing for a file until its operator rewrites the names and raises the version. Cult never raises
 * one itself: every build then reads a file the same way, whichever build wrote it last. A file without a version is
 * written in {@link #FIRST}.
 *
 * <p>To rename a check, split one in two or merge several into one: raise {@link #LATEST}, give the check its new
 * name and a {@link FormerName} lasting until the version before, list the new permission ids in
 * {@link CheckPermissions}, and describe the new version beside both keys in every bundled {@code config} and
 * {@code punishments} default. A check split off another takes the other's name as its former name, so files of an
 * older version go on naming both. Checks merged into one each take the merged name and keep their own former name,
 * so files of an older version go on naming each apart, and a file of the new version sets, exempts and selects them
 * under the one name. The check's {@code stableKey} never changes, so the flags of merged checks stay keyed apart.
 */
public final class CheckNames {
    /** The names checks had before any was renamed. */
    public static final int FIRST = 1;

    public static final int LATEST = 3;

    /**
     * {@code config.yml}: the version of the prefix of each check's own settings, of its permission nodes and of the
     * name the check reports.
     */
    public static final String CONFIG_KEY = "check-name-version";

    /** {@code punishments.yml}: the version of the names its groups select checks by. */
    public static final String PUNISHMENTS_KEY = "punishment-check-name-version";

    private final String latest;
    // Ascending by the last version each former name was held in.
    private final int[] lastVersions;
    private final String[] formerNames;

    private CheckNames(String latest, int[] lastVersions, String[] formerNames) {
        this.latest = latest;
        this.lastVersions = lastVersions;
        this.formerNames = formerNames;
    }

    public static @NotNull CheckNames of(@NotNull String latest) {
        return new CheckNames(latest, new int[0], new String[0]);
    }

    public static @NotNull CheckNames of(@NotNull CheckData data) {
        CheckNames names = of(data.name());
        for (FormerName former : data.formerNames()) names = names.formerly(former.until(), former.name());
        return names;
    }

    /** These names, with {@code name} in every version up to {@code lastVersion} that has no older name. */
    public @NotNull CheckNames formerly(int lastVersion, @NotNull String name) {
        if (lastVersion < FIRST || lastVersion >= LATEST) {
            throw new IllegalArgumentException(
                    latest + " cannot have been " + name + " until name version " + lastVersion);
        }
        if (name.isEmpty()) throw new IllegalArgumentException(latest + " has an empty former name");
        int at = 0;
        while (at < lastVersions.length && lastVersions[at] < lastVersion) at++;
        if (at < lastVersions.length && lastVersions[at] == lastVersion) {
            throw new IllegalArgumentException(latest + " has two names in name version " + lastVersion);
        }
        int[] versions = new int[lastVersions.length + 1];
        String[] names = new String[formerNames.length + 1];
        System.arraycopy(lastVersions, 0, versions, 0, at);
        System.arraycopy(formerNames, 0, names, 0, at);
        versions[at] = lastVersion;
        names[at] = name;
        System.arraycopy(lastVersions, at, versions, at + 1, lastVersions.length - at);
        System.arraycopy(formerNames, at, names, at + 1, formerNames.length - at);
        return new CheckNames(latest, versions, names);
    }

    public @NotNull String latest() {
        return latest;
    }

    /** The check's name in {@code version}; a version outside the known ones reads as the nearest known one. */
    public @NotNull String in(int version) {
        for (int i = 0; i < lastVersions.length; i++) {
            if (version <= lastVersions[i]) return formerNames[i];
        }
        return latest;
    }

    /**
     * The name version {@code key} stamps the operator's file with: {@link #FIRST} without a stamp, and the nearest
     * known version for one {@link #validate} rejects.
     */
    public static int version(@NotNull ConfigManager config, @NotNull String key) {
        Integer written = whole(written(config, key));
        return written == null ? FIRST : Math.max(FIRST, Math.min(LATEST, written));
    }

    /**
     * Refuses a configuration stamped with a name version this build cannot read. A build that read it as the nearest
     * version it knows would leave every check renamed since selected by nothing, which turns the check off.
     */
    public static void validate(@NotNull ConfigManager config) {
        List<String> unreadable = new ArrayList<>();
        refuse(config, CONFIG_KEY, "config.yml", unreadable);
        refuse(config, PUNISHMENTS_KEY, "punishments.yml", unreadable);
        if (!unreadable.isEmpty()) throw new IllegalStateException(String.join("; ", unreadable));
    }

    private static void refuse(ConfigManager config, String key, String file, List<String> unreadable) {
        Object written = written(config, key);
        if (written == null) return;
        Integer version = whole(written);
        if (version != null && version >= FIRST && version <= LATEST) return;
        unreadable.add(file + ": " + key + " is " + written + ", and this build reads check names of versions " + FIRST
                + " to " + LATEST);
    }

    /**
     * The operator's own value of {@code key}, or null without one: a bundled default must not stamp a file its
     * operator never stamped, nor stand in for a setting written under a name of another version.
     */
    public static @Nullable Object written(ConfigManager config, String key) {
        if (config instanceof ConfigManagerFileImpl files) return files.written(key);
        try {
            return config.getElse(key, null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static @Nullable Integer whole(@Nullable Object written) {
        if (written instanceof Integer || written instanceof Long || written instanceof Short) {
            long value = ((Number) written).longValue();
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (int) value : null;
        }
        if (!(written instanceof String text)) return null;
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException notWhole) {
            return null;
        }
    }
}
