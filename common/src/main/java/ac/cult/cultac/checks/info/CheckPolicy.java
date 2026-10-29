package ac.cult.cultac.checks.info;

import ac.cult.cultac.manager.PunishmentRules;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** What this server's {@code punishments.yml} does with each check, from the groups {@code PunishmentManager} loads. */
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

    /** The {@code Punishments} groups {@link PunishmentRules} reads. */
    public static CheckPolicy parse(List<?> punishments) {
        List<Group> groups = new ArrayList<>();
        for (PunishmentRules.Group rule : PunishmentRules.parse(punishments).groups()) {
            Integer alertAt = null;
            boolean recorded = false;
            boolean webhook = false;
            List<Action> actions = new ArrayList<>();
            for (PunishmentRules.Command command : rule.commands()) {
                int threshold = command.threshold();
                switch (command.command()) {
                    case "[alert]", "[proxy]" -> {
                        if (alertAt == null || threshold < alertAt) alertAt = threshold;
                    }
                    case "[log]" -> recorded = true;
                    case "[webhook]" -> webhook = true;
                    default -> actions.add(new Action(commandWord(command.command()), threshold));
                }
            }
            groups.add(new Group(rule.selectors(), alertAt, recorded, webhook, List.copyOf(actions)));
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
        String altName = entry.altName();
        if (altName != null) altName = altName.toLowerCase(Locale.ROOT);
        List<Group> matched = new ArrayList<>();
        for (Group group : groups) {
            if (PunishmentRules.holds(group.selectors(), name, altName)) matched.add(group);
        }
        return new Status(List.copyOf(matched));
    }
}
