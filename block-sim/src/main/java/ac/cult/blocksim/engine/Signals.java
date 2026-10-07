package ac.cult.blocksim.engine;


/** SignalGetter queries only compensated states; it never runs neighborChanged. */
public final class Signals {
    private final SimLevel level;
    public Signals(SimLevel level) { this.level = level; }
    public int controlInputSignal(BlockPos pos, Direction direction, boolean onlyDiodes) {
        int state = level.stateAt(pos);
        if (onlyDiodes) return level.behavior(state) instanceof ac.cult.blocksim.behavior.DiodeBehavior ? directSignal(pos, direction) : 0;
        if (level.registry().block(state).key().equals("minecraft:redstone_block")) return 15;
        if (level.registry().block(state).key().equals("minecraft:redstone_wire")) return Integer.parseInt(level.registry().value(state, "power"));
        return level.registry().facts(state).has(ac.cult.blocksim.data.StateFacts.SIGNAL_SOURCE) ? directSignal(pos, direction) : 0;
    }

    public int directSignal(BlockPos pos, Direction direction) {
        int state = level.stateAt(pos);
        return level.behavior(state).directSignal(level, state, pos, direction);
    }

    public int directSignalTo(BlockPos pos) {
        int signal = 0;
        for (Direction direction : Direction.values()) {
            signal = Math.max(signal, directSignal(pos.relative(direction), direction));
            if (signal >= 15) return signal;
        }
        return signal;
    }

    public int signal(BlockPos pos, Direction direction) {
        int state = level.stateAt(pos);
        int own = level.behavior(state).signal(level, state, pos, direction);
        return level.isRedstoneConductor(state, pos) ? Math.max(own, directSignalTo(pos)) : own;
    }

    public boolean hasNeighborSignal(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (signal(pos.relative(direction), direction) > 0) return true;
        }
        return false;
    }
}
