package ac.cult.cultac.manager;

import java.util.ArrayList;
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
    private long generation;

    /** @param capacity the most players with no connection whose state is kept */
    PunishmentLedger(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Joins the connection {@code owner} of {@code id} to the state its other connection counts in, or to the state
     * its last connection left while that still counts, as long as the groups are the same and no reload came between;
     * otherwise to {@code fresh}.
     */
    synchronized List<GroupState> attach(
            UUID id, Object owner, List<PunishmentRules.Group> rules, List<GroupState> fresh, long now) {
        Entry entry = connected.get(id);
        if (entry == null) {
            entry = left.remove(id);
            if (entry == null || !entry.rules.equals(rules)) {
                entry = new Entry(rules, fresh, generation);
            } else {
                entry.age(now);
            }
            connected.put(id, entry);
        } else if (entry.generation != generation || !entry.rules.equals(rules)) {
            entry.start(rules, fresh, generation);
        }
        entry.owners.add(owner);
        return entry.state;
    }

    /**
     * Starts the state of {@code id} over with {@code fresh} for a reload of the connection {@code owner}, which counts
     * in {@code current}; joins the state another connection of {@code id} already started over, and leaves the
     * shared state alone for a connection that has ended.
     */
    synchronized List<GroupState> restart(
            UUID id,
            Object owner,
            List<GroupState> current,
            List<PunishmentRules.Group> rules,
            List<GroupState> fresh) {
        Entry entry = connected.get(id);
        if (entry == null || !entry.owners.contains(owner)) return fresh;
        if (entry.state == current || entry.generation != generation || !entry.rules.equals(rules)) {
            entry.start(rules, fresh, generation);
        }
        return entry.state;
    }

    /** Ends the connection {@code owner} of {@code id}; the state stays while a group's window still holds something. */
    synchronized void detach(UUID id, Object owner, long now) {
        Entry entry = connected.get(id);
        if (entry == null || !entry.owners.remove(owner) || !entry.owners.isEmpty()) return;
        connected.remove(id);
        if (entry.generation == generation && entry.lasts(now)) left.put(id, entry);
        Iterator<Entry> oldest = left.values().iterator();
        while (oldest.hasNext()) {
            Entry next = oldest.next();
            if (left.size() <= capacity && next.lasts(now)) break;
            oldest.remove();
        }
    }

    /** Drops the state of every player with no connection, and of each connection that has not reloaded since. */
    synchronized void clear() {
        left.clear();
        generation++;
    }

    /** The players with no connection whose state is kept. */
    synchronized int kept() {
        return left.size();
    }

    /** A group's flags and commands, apart from the checks of a connection. */
    record GroupState(List<ParsedCommand> commands, ViolationWindow violations, int removeViolationsAfter) {
        /** Drops what is older than the window and the runs waiting for a server; true when something is left. */
        boolean age(long now) {
            synchronized (violations) {
                violations.expire(now, removeViolationsAfter);
                boolean holds = violations.size() > 0;
                for (ParsedCommand command : commands) {
                    command.waiting = null;
                    if (command.interval > 0 || command.executeCount == 0) continue;
                    if (now - command.usedAt > removeViolationsAfter) {
                        command.executeCount = 0;
                        command.withheldThrough = violations.added();
                    } else {
                        holds = true;
                    }
                }
                return holds;
            }
        }
    }

    private static final class Entry {
        private final List<Object> owners = new ArrayList<>(1);
        private List<PunishmentRules.Group> rules;
        private List<GroupState> state;
        // The ledger's generation when the state started; a clear moves the ledger to the next one.
        private long generation;

        private Entry(List<PunishmentRules.Group> rules, List<GroupState> state, long generation) {
            start(rules, state, generation);
        }

        private void start(List<PunishmentRules.Group> rules, List<GroupState> state, long generation) {
            this.rules = rules;
            this.state = state;
            this.generation = generation;
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
