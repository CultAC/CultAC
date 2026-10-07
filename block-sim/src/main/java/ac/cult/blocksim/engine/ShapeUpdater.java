package ac.cult.blocksim.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** CollectingNeighborUpdater's shape-only queue, with its depth-first layer ordering. */
public final class ShapeUpdater {
    private final SimLevel level;
    private final int maxChainedUpdates;
    private final ArrayDeque<Update> stack = new ArrayDeque<>();
    private final ArrayList<Update> addedThisLayer = new ArrayList<>();
    private long count;

    public ShapeUpdater(SimLevel level, int maxChainedUpdates) {
        this.level = level;
        this.maxChainedUpdates = maxChainedUpdates;
    }

    public void shapeUpdate(Direction direction, int neighborState, BlockPos pos, BlockPos neighborPos, int flags, int limit) {
        boolean runningAlready = count > 0;
        boolean tooMany = maxChainedUpdates >= 0 && count >= maxChainedUpdates;
        count++;
        if (!tooMany) {
            Update update = new Update(direction, neighborState, pos, neighborPos, flags, limit);
            if (runningAlready) addedThisLayer.add(update);
            else stack.push(update);
        }
        if (!runningAlready) runUpdates();
    }

    private void runUpdates() {
        try {
            while (!stack.isEmpty() || !addedThisLayer.isEmpty()) {
                for (int i = addedThisLayer.size() - 1; i >= 0; i--) stack.push(addedThisLayer.get(i));
                addedThisLayer.clear();
                Update next = stack.peek();
                execute(next);
                stack.pop();
            }
        } finally {
            stack.clear();
            addedThisLayer.clear();
            count = 0;
        }
    }

    private void execute(Update update) {
        int state = level.stateAt(update.pos);
        if ((update.flags & 128) != 0 && level.registry().block(state).key().equals("minecraft:redstone_wire")) return;
        int changed = level.behavior(state).updateShape(level, state, update.pos, update.direction, update.neighborPos, update.neighborState);
        level.updateOrDestroy(state, changed, update.pos, update.flags, update.limit);
    }

    private record Update(Direction direction, int neighborState, BlockPos pos, BlockPos neighborPos, int flags, int limit) { }
}
