package ac.cult.cultac.manager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The punishment groups' flags and used commands of each player, shared by the player's connections and kept after
 * the last one ends while a group's window still holds something.
 */
final class PunishmentLedger {
    static final PunishmentLedger INSTANCE = new PunishmentLedger(10_000);

    private final int capacity;
    private final Map<UUID, Entry> connected = new HashMap<>();
    // Players with no connection, oldest first.
    private final LinkedHashMap<UUID, Entry> left = new LinkedHashMap<>();

    /** @param capacity the most players with no connection whose state is kept */
    PunishmentLedger(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Joins a connection of {@code id} to the state its other connection counts in, or to the state its last
     * connection left while that still counts, as long as the groups are the same; otherwise to {@code fresh}.
     */
    synchronized List<GroupState> attach(UUID id, List<PunishmentRules.Group> rules, List<GroupState> fresh, long now) {
        Entry entry = connected.get(id);
        if (entry == null) {
            entry = left.remove(id);
            if (entry == null || !entry.rules.equals(rules)) {
                entry = new Entry(rules, fresh);
            } else {
                entry.age(now);
            }
            connected.put(id, entry);
        } else if (!entry.rules.equals(rules)) {
            entry.rules = rules;
            entry.state = fresh;
        }
        entry.connections++;
        return entry.state;
    }

    /** Starts the state of {@code id} over with {@code fresh}. */
    synchronized void replace(UUID id, List<PunishmentRules.Group> rules, List<GroupState> fresh) {
        Entry entry = connected.get(id);
        if (entry == null) return;
        entry.rules = rules;
        entry.state = fresh;
    }

    /** Ends a connection of {@code id}; the state stays while a group's window still holds something. */
    synchronized void detach(UUID id, long now) {
        Entry entry = connected.get(id);
        if (entry == null || --entry.connections > 0) return;
        connected.remove(id);
        for (GroupState group : entry.state) {
            synchronized (group.violations()) {
                for (ParsedCommand command : group.commands()) command.waiting = null;
            }
        }
        if (entry.lasts(now)) left.put(id, entry);
        Iterator<Entry> oldest = left.values().iterator();
        while (oldest.hasNext()) {
            Entry next = oldest.next();
            if (left.size() <= capacity && next.lasts(now)) break;
            oldest.remove();
        }
    }

    /** Drops the state of every player with no connection. */
    synchronized void clear() {
        left.clear();
    }

    /** The players with no connection whose state is kept. */
    synchronized int kept() {
        return left.size();
    }

    /** A group's flags and commands, apart from the checks of a connection. */
    record GroupState(List<ParsedCommand> commands, ViolationWindow violations, int removeViolationsAfter) {
        /** Drops what is older than the window; true when something is left. */
        boolean age(long now) {
            synchronized (violations) {
                violations.expire(now, removeViolationsAfter);
                boolean holds = violations.size() > 0;
                for (ParsedCommand command : commands) {
                    if (command.interval > 0 || command.executeCount == 0) continue;
                    if (now - command.usedAt > removeViolationsAfter) {
                        command.executeCount = 0;
                    } else {
                        holds = true;
                    }
                }
                return holds;
            }
        }
    }

    private static final class Entry {
        private List<PunishmentRules.Group> rules;
        private List<GroupState> state;
        private int connections;

        private Entry(List<PunishmentRules.Group> rules, List<GroupState> state) {
            this.rules = rules;
            this.state = state;
        }

        private void age(long now) {
            for (GroupState group : state) group.age(now);
        }

        private boolean lasts(long now) {
            boolean lasts = false;
            for (GroupState group : state) lasts |= group.age(now);
            return lasts;
        }
    }
}
