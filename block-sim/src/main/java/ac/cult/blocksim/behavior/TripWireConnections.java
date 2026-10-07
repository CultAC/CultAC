package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Arrays;
import java.util.Locale;

/** The client-reachable hook placement and its removal recheck share one calculator. */
final class TripWireConnections {
    private TripWireConnections() { }
    private static boolean is(SimLevel level, int state, String block) {
        return level.registry().block(state).key().equals("minecraft:" + block);
    }
    private static boolean flag(SimLevel level, int state, String property) {
        return Boolean.parseBoolean(level.registry().value(state, property));
    }
    private static Direction direction(SimLevel level, int state) {
        return Direction.valueOf(level.registry().value(state, "facing").toUpperCase(Locale.ROOT));
    }

    static void calculateState(SimLevel level, BlockPos pos, int state, boolean isBeingDestroyed) {
        Direction facing = direction(level, state);
        boolean wasAttached = flag(level, state, "attached");
        boolean attached = !isBeingDestroyed;
        boolean powered = false;
        int receiver = 0;
        int[] wireStates = new int[42];
        Arrays.fill(wireStates, -1);
        // Client setPlacedBy and onRemoved always supply wireSource=-1. updateSource
        // from TripWireBlock.onPlace/affectNeighborsAfterRemoval is server-only.
        for (int i = 1; i < 42; i++) {
            int wire = level.stateAt(pos.relative(facing, i));
            if (is(level, wire, "tripwire_hook")) {
                if (direction(level, wire) == facing.opposite()) receiver = i;
                break;
            }
            if (!is(level, wire, "tripwire")) attached = false;
            else {
                powered |= !flag(level, wire, "disarmed") && flag(level, wire, "powered");
                wireStates[i] = wire;
            }
        }
        attached &= receiver > 1;
        powered &= attached;
        int next = level.registry().block(state).defaultState();
        next = level.registry().with(next, "attached", Boolean.toString(attached));
        next = level.registry().with(next, "powered", Boolean.toString(powered));
        if (receiver > 0) {
            level.setBlock(pos.relative(facing, receiver), level.registry().with(next, "facing", facing.opposite().name().toLowerCase(Locale.ROOT)), 3);
            // notifyNeighbors is a client no-op; emitState produces sound/events only.
            if (!is(level, level.stateAt(pos), "tripwire_hook")) {
                onRemoved(level, pos, next);
                return;
            }
        }
        if (!isBeingDestroyed) {
            level.setBlock(pos, level.registry().with(next, "facing", facing.name().toLowerCase(Locale.ROOT)), 3);
        }
        if (wasAttached != attached) {
            for (int i = 1; i < receiver; i++) {
                if (wireStates[i] < 0) continue;
                BlockPos wirePos = pos.relative(facing, i);
                int current = level.stateAt(wirePos);
                if (is(level, current, "tripwire") || is(level, current, "tripwire_hook")) {
                    level.setBlock(wirePos, level.registry().with(wireStates[i], "attached", Boolean.toString(attached)), 3);
                }
            }
        }
    }

    private static void onRemoved(SimLevel level, BlockPos pos, int state) {
        if (flag(level, state, "attached") || flag(level, state, "powered")) calculateState(level, pos, state, true);
    }
}
