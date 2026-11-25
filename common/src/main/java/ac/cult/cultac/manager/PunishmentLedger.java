package ac.cult.cultac.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The punishment groups' flags and used commands of each player, shared by the player's connections and kept after
 * the last one ends while a group's window still holds something.
 */
final class PunishmentLedger {
    static final PunishmentLedger INSTANCE = new PunishmentLedger(10_000);

    private final int capacity;
    private final Map<UUID, Entry> connected = new HashMap<>();
    // Players with no connection whose kept state feeds only built-in actions, oldest first.
    private final LinkedHashMap<UUID, Departed> left = new LinkedHashMap<>();
    // Players with no connection whose kept state feeds a console command, oldest first; dropped last.
    private final LinkedHashMap<UUID, Departed> leftPunishable = new LinkedHashMap<>();
    private List<PunishmentRules.Group> sharedRules = List.of();
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
            Departed departed = left.remove(id);
            if (departed == null) departed = leftPunishable.remove(id);
            List<GroupState> state =
                    departed != null && departed.rules.equals(rules) ? departed.restore(fresh, now) : fresh;
            entry = new Entry(rules, state, generation);
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

    /**
     * Ends the connection {@code owner} of {@code id}; the groups whose window still holds something stay. Past the
     * capacity, the players whose state feeds only built-in actions go first, oldest first.
     */
    synchronized void detach(UUID id, Object owner, long now) {
        Entry entry = connected.get(id);
        if (entry == null || !entry.owners.remove(owner) || !entry.owners.isEmpty()) return;
        connected.remove(id);
        if (entry.generation == generation) {
            if (!entry.rules.equals(sharedRules)) sharedRules = entry.rules;
            Departed departed = Departed.of(sharedRules, entry.state, now);
            if (departed != null) (departed.punishable ? leftPunishable : left).put(id, departed);
        }
        dropExpired(left, now, true);
        dropExpired(leftPunishable, now, true);
        if (kept() <= capacity) return;
        dropExpired(left, now, false);
        dropExpired(leftPunishable, now, false);
        dropOldest(left);
        dropOldest(leftPunishable);
    }

    /** Drops the expired players, up to the first one that lasts when {@code headOnly}. */
    private static void dropExpired(LinkedHashMap<UUID, Departed> players, long now, boolean headOnly) {
        Iterator<Departed> oldest = players.values().iterator();
        while (oldest.hasNext()) {
            if (oldest.next().lasts(now)) {
                if (headOnly) return;
            } else {
                oldest.remove();
            }
        }
    }

    private void dropOldest(LinkedHashMap<UUID, Departed> players) {
        Iterator<Departed> oldest = players.values().iterator();
        while (kept() > capacity && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** Drops the state of every player with no connection, and of each connection that has not reloaded since. */
    synchronized void clear() {
        left.clear();
        leftPunishable.clear();
        generation++;
    }

    /** The players with no connection whose state is kept. */
    synchronized int kept() {
        return left.size() + leftPunishable.size();
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

        private boolean runsConsoleCommands() {
            for (ParsedCommand command : commands) {
                if (!PunishmentManager.BUILTIN_ACTIONS.contains(command.action())) return true;
            }
            return false;
        }
    }

    /** The groups of a player with no connection whose window still holds something, by their place in the rules. */
    private record Departed(
            List<PunishmentRules.Group> rules, int[] groups, GroupState[] held, boolean punishable) {
        private static @Nullable Departed of(List<PunishmentRules.Group> rules, List<GroupState> state, long now) {
            int[] groups = new int[state.size()];
            GroupState[] held = new GroupState[state.size()];
            int count = 0;
            boolean punishable = false;
            for (int g = 0; g < state.size(); g++) {
                GroupState group = state.get(g);
                if (!group.age(now)) continue;
                groups[count] = g;
                held[count++] = group;
                punishable |= group.runsConsoleCommands();
            }
            if (count == 0) return null;
            return new Departed(rules, Arrays.copyOf(groups, count), Arrays.copyOf(held, count), punishable);
        }

        private List<GroupState> restore(List<GroupState> fresh, long now) {
            List<GroupState> state = new ArrayList<>(fresh);
            for (int i = 0; i < held.length; i++) {
                held[i].age(now);
                state.set(groups[i], held[i]);
            }
            return state;
        }

        private boolean lasts(long now) {
            boolean lasts = false;
            for (GroupState group : held) lasts |= group.age(now);
            return lasts;
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
    }
}
