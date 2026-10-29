package ac.cult.cultac.manager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code Punishments} groups of {@code punishments.yml}, read up to the first group that cannot be read.
 *
 * @param groups the groups read
 * @param unreadGroupSelectors the selectors of the group whose commands could not be read; its checks still run
 * @param failure why the rest of the groups could not be read, or {@code null}
 */
public record PunishmentRules(
        @NotNull List<Group> groups,
        @NotNull List<String> unreadGroupSelectors,
        @Nullable RuntimeException failure) {
    /**
     * @param threshold the group's active flag count the command first runs at
     * @param interval how many more flags it runs again after, or 0 to run once
     * @param command the console command or built-in action, as written
     */
    public record Command(
            int threshold, int interval, @NotNull String command) {}

    /**
     * @param selectors the group's {@code checks} entries, lower-cased
     * @param removeViolationsAfter how many seconds a flag counts toward the group's thresholds
     */
    public record Group(
            @NotNull List<String> selectors, @NotNull List<Command> commands, int removeViolationsAfter) {
        /** Whether the group holds the check with these lower-cased names. */
        public boolean holds(@Nullable String name, @Nullable String altName) {
            return PunishmentRules.holds(selectors, name, altName);
        }
    }

    @SuppressWarnings("unchecked")
    public static @NotNull PunishmentRules parse(@NotNull List<?> punishments) {
        List<Group> groups = new ArrayList<>();
        List<String> unread = List.of();
        try {
            for (Object element : punishments) {
                LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) element;
                List<String> checks = (List<String>) map.getOrDefault("checks", new ArrayList<>());
                List<String> commands = (List<String>) map.getOrDefault("commands", new ArrayList<>());
                int removeViolationsAfter = (int) map.getOrDefault("remove-violations-after", 300);

                List<String> selectors = new ArrayList<>();
                for (String check : checks) selectors.add(check.toLowerCase(Locale.ROOT));
                unread = List.copyOf(selectors);

                List<Command> parsed = new ArrayList<>();
                for (String command : commands) {
                    int threshold = Integer.parseInt(command.substring(0, command.indexOf(":")));
                    int interval = Integer.parseInt(command.substring(command.indexOf(":"), command.indexOf(" "))
                            .substring(1));
                    parsed.add(new Command(threshold, interval, command.substring(command.indexOf(" ") + 1)));
                }
                groups.add(new Group(unread, List.copyOf(parsed), removeViolationsAfter));
                unread = List.of();
            }
        } catch (RuntimeException unreadable) {
            return new PunishmentRules(List.copyOf(groups), unread, unreadable);
        }
        return new PunishmentRules(List.copyOf(groups), List.of(), null);
    }

    /**
     * Whether selectors hold the check: each matching selector adds it once, and after every selector it is removed
     * once per matching exclusion so far. A check without a name matches nothing.
     */
    public static boolean holds(@NotNull List<String> selectors, @Nullable String name, @Nullable String altName) {
        if (name == null) return false;
        int added = 0;
        int excluded = 0;
        for (String selector : selectors) {
            boolean exclude = selector.startsWith("!");
            String match = exclude ? selector.substring(1) : selector;
            if (name.contains(match) || (altName != null && altName.contains(match))) {
                if (exclude) excluded++;
                else added++;
            }
            added = Math.max(0, added - excluded);
        }
        return added > 0;
    }
}
