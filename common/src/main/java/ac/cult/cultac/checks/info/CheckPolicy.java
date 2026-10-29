package ac.cult.cultac.checks.info;

import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** What this server's {@code punishments.yml} does with each check, read the way {@code PunishmentManager} reads it. */
public final class CheckPolicy {
    /**
     * @param command the console command's first word with a leading slash, as {@code /ban}
     * @param threshold the group's active flag count it first runs at
     */
    public record Action(@NotNull String command, int threshold) {}

    /**
     * One punishment group holding the check.
     *
     * @param selectors the group's {@code checks} entries, lower-cased
     * @param expireSeconds how long a flag counts toward the group's thresholds
     * @param alertAt the lowest flag count a staff alert ({@code [alert]} or {@code [proxy]}) runs at, or {@code null}
     * @param recorded whether {@code [log]} records the flags in the player's history
     * @param webhook whether {@code [webhook]} sends the flags to the staff webhook
     * @param actions the console commands the group runs
     */
    public record Group(
            @NotNull List<String> selectors,
            int expireSeconds,
            @Nullable Integer alertAt,
            boolean recorded,
            boolean webhook,
            @NotNull List<Action> actions) {}

    /** What a check's flags lead to on this server, most severe first. */
    public enum Handling {
        /** Console commands run. */
        AUTOMATIC,
        /** Staff get alerts; no console commands run. */
        ALERTS,
        /** Flags reach only the player's history or the staff webhook. */
        RECORDED,
        /** Flags lead to nothing configured. */
        NONE,
        /** The check does not flag: it is experimental and experimental checks are off. */
        OFF
    }

    /** The groups holding a check. */
    public record Status(@NotNull List<Group> groups) {
        /** @param running whether the check flags at all, which an experimental check does only when enabled */
        public @NotNull Handling handling(boolean running) {
            if (!running) return Handling.OFF;
            if (!actions().isEmpty()) return Handling.AUTOMATIC;
            if (alertAt() != null) return Handling.ALERTS;
            if (recorded() || webhook()) return Handling.RECORDED;
            return Handling.NONE;
        }

        public @Nullable Integer alertAt() {
            Integer lowest = null;
            for (Group group : groups) {
                if (group.alertAt() != null && (lowest == null || group.alertAt() < lowest)) lowest = group.alertAt();
            }
            return lowest;
        }

        public boolean recorded() {
            return groups.stream().anyMatch(Group::recorded);
        }

        public boolean webhook() {
            return groups.stream().anyMatch(Group::webhook);
        }

        public @NotNull List<Action> actions() {
            List<Action> actions = new ArrayList<>();
            for (Group group : groups) actions.addAll(group.actions());
            return actions;
        }
    }

    private final List<Group> groups;

    private CheckPolicy(List<Group> groups) {
        this.groups = groups;
    }

    public static @NotNull CheckPolicy of(@NotNull ConfigManager config) {
        return parse(config.getStringListElse("Punishments", new ArrayList<>()));
    }

    /** Reads the {@code Punishments} groups up to the first one {@code PunishmentManager} cannot load. */
    @SuppressWarnings("unchecked")
    public static @NotNull CheckPolicy parse(@NotNull List<?> punishments) {
        List<Group> groups = new ArrayList<>();
        try {
            for (Object element : punishments) {
                LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) element;
                List<String> checks = (List<String>) map.getOrDefault("checks", new ArrayList<>());
                List<String> commands = (List<String>) map.getOrDefault("commands", new ArrayList<>());
                int expireSeconds = (int) map.getOrDefault("remove-violations-after", 300);

                List<String> selectors = new ArrayList<>();
                for (String check : checks) selectors.add(check.toLowerCase(Locale.ROOT));
                Integer alertAt = null;
                boolean recorded = false;
                boolean webhook = false;
                List<Action> actions = new ArrayList<>();
                for (String command : commands) {
                    int threshold = Integer.parseInt(command.substring(0, command.indexOf(":")));
                    Integer.parseInt(command.substring(command.indexOf(":"), command.indexOf(" "))
                            .substring(1));
                    String run = command.substring(command.indexOf(" ") + 1);
                    switch (run) {
                        case "[alert]", "[proxy]" -> {
                            if (alertAt == null || threshold < alertAt) alertAt = threshold;
                        }
                        case "[log]" -> recorded = true;
                        case "[webhook]" -> webhook = true;
                        default -> actions.add(new Action(commandWord(run), threshold));
                    }
                }
                groups.add(new Group(
                        List.copyOf(selectors), expireSeconds, alertAt, recorded, webhook, List.copyOf(actions)));
            }
        } catch (RuntimeException unloadable) {
            // PunishmentManager stops loading at the first group it cannot parse.
        }
        return new CheckPolicy(List.copyOf(groups));
    }

    /** The command's first word as {@code /word}, keeping only characters a command name uses. */
    private static String commandWord(String command) {
        String word = command.strip();
        int space = word.indexOf(' ');
        if (space >= 0) word = word.substring(0, space);
        if (word.startsWith("/")) word = word.substring(1);
        return "/" + word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_:.-]", "");
    }

    public @NotNull Status status(@NotNull CheckCatalog.Entry entry) {
        String name = entry.name().toLowerCase(Locale.ROOT);
        String altName = entry.runtimeAltName();
        if (altName != null) altName = altName.toLowerCase(Locale.ROOT);
        List<Group> holding = new ArrayList<>();
        for (Group group : groups) {
            if (inGroup(group.selectors(), name, altName)) holding.add(group);
        }
        return new Status(List.copyOf(holding));
    }

    /**
     * Whether {@code PunishmentManager} puts the check in a group with these selectors: each matching selector adds it
     * once, and after every selector it is removed once per matching exclusion so far.
     */
    private static boolean inGroup(List<String> selectors, String name, @Nullable String altName) {
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
