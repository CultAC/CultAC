package ac.cult.cultac.checks.info;

import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** Reads {@code punishments.yml} exactly as {@code PunishmentManager.reload} does; change the two together. */
public final class CheckPolicy {
    public record Action(String command, int threshold) {}

    public record Group(
            List<String> selectors,
            @Nullable Integer alertAt,
            boolean recorded,
            boolean webhook,
            List<Action> actions) {}

    public enum Handling {
        AUTOMATIC,
        ALERTS,
        RECORDED,
        NONE,
        OFF
    }

    public record Status(List<Group> groups) {
        public Handling handling(boolean running) {
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

        public List<Action> actions() {
            List<Action> actions = new ArrayList<>();
            for (Group group : groups) actions.addAll(group.actions());
            return actions;
        }
    }

    private final List<Group> groups;

    private CheckPolicy(List<Group> groups) {
        this.groups = groups;
    }

    public static CheckPolicy of(ConfigManager config) {
        return parse(config.getStringListElse("Punishments", new ArrayList<>()));
    }

    @SuppressWarnings("unchecked")
    public static CheckPolicy parse(List<?> punishments) {
        List<Group> groups = new ArrayList<>();
        try {
            for (Object element : punishments) {
                LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) element;
                List<String> checks = (List<String>) map.getOrDefault("checks", new ArrayList<>());
                List<String> commands = (List<String>) map.getOrDefault("commands", new ArrayList<>());
                // Read only to fail where PunishmentManager fails on a window that is not a whole number.
                int window = (int) map.getOrDefault("remove-violations-after", 300);

                List<String> selectors = new ArrayList<>();
                for (String check : checks) selectors.add(check.toLowerCase(Locale.ROOT));
                Integer alertAt = null;
                boolean recorded = false;
                boolean webhook = false;
                List<Action> actions = new ArrayList<>();
                for (String command : commands) {
                    String firstNum = command.substring(0, command.indexOf(":"));
                    String secondNum = command.substring(command.indexOf(":"), command.indexOf(" "));
                    int threshold = Integer.parseInt(firstNum);
                    // Parsed only to fail where PunishmentManager fails on a bad interval.
                    Integer.parseInt(secondNum.substring(1));
                    String commandString = command.substring(command.indexOf(" ") + 1);
                    switch (commandString) {
                        case "[alert]", "[proxy]" -> {
                            if (alertAt == null || threshold < alertAt) alertAt = threshold;
                        }
                        case "[log]" -> recorded = true;
                        case "[webhook]" -> webhook = true;
                        default -> actions.add(new Action(commandWord(commandString), threshold));
                    }
                }
                groups.add(new Group(List.copyOf(selectors), alertAt, recorded, webhook, List.copyOf(actions)));
            }
        } catch (RuntimeException ignored) {
            // PunishmentManager stops loading at the first group it cannot parse.
        }
        return new CheckPolicy(List.copyOf(groups));
    }

    // The word lands in a MiniMessage template: strip anything that could be a tag, color code or placeholder.
    private static String commandWord(String command) {
        String word = command.strip();
        int space = word.indexOf(' ');
        if (space >= 0) word = word.substring(0, space);
        if (word.startsWith("/")) word = word.substring(1);
        return "/" + word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_:.-]", "");
    }

    public Status status(CheckCatalog.Entry entry) {
        String name = entry.name().toLowerCase(Locale.ROOT);
        String altName = entry.runtimeAltName();
        if (altName != null) altName = altName.toLowerCase(Locale.ROOT);
        List<Group> matched = new ArrayList<>();
        for (Group group : groups) {
            if (inGroup(group.selectors(), name, altName)) matched.add(group);
        }
        return new Status(List.copyOf(matched));
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
