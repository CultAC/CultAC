package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.*;

public final class TrappedChestBehavior extends ChestBehavior {
    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) {
        BlockEntityData entity = level.blockEntityAt(pos);
        int openers = entity != null && (entity.type().equals("minecraft:chest") || entity.type().equals("minecraft:trapped_chest"))
            ? entity.data().integer("openers_count", 0) : 0;
        // Client block events change only ChestLidController, not openersCounter.
        return Math.min(Math.max(openers, 0), 15);
    }
    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return direction == Direction.UP ? signal(level, state, pos, direction) : 0;
    }
}
